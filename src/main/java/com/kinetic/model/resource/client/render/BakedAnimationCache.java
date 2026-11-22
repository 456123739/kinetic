package com.kinetic.model.resource.client.render;

import com.kinetic.format.AssetLoadException;
import com.kinetic.format.schema.baked.asset.BakedAssetView;
import com.kinetic.format.schema.baked.asset.BakedAssetWriter;
import com.kinetic.model.domain.Hash256;
import com.kinetic.model.resource.client.AnimationStore;
import com.kinetic.model.resource.client.ModelResourceFailureGate;
import com.kinetic.model.storage.AtomicSharedCache;
import com.kinetic.model.storage.ModelHashing;
import com.kinetic.proto.mixel.asset.model.data.AnimationFile;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** Process-shared immutable baked-animation cache; loaded bindings remain animation-set-scoped. */
public final class BakedAnimationCache {
    static final String CACHE_SUFFIX = ".anim.kinetic-cache";
    static final String CACHE_ABI = "animation-0.3.0-unstable";
    private static final byte[] HASH_DOMAIN =
            ("kinetic." + CACHE_ABI + "\0").getBytes(StandardCharsets.UTF_8);

    private final Path bakedRoot;
    private final AtomicSharedCache cache;

    public BakedAnimationCache(Path bakedRoot, AtomicSharedCache cache) {
        this.bakedRoot = bakedRoot;
        this.cache = cache;
    }

    static String profileKey() {
        return CACHE_ABI;
    }

    public AnimationStore loadOrBake(Hash256 containerId, String targetId,
                                     String animationSet, Hash256 definitionHash,
                                     Iterable<? extends Map.Entry<String,
                                             AnimationFile>> animationFiles,
                                     AnimationStore fallback,
                                     Function<String, ModelResourceFailureGate> failureGates) throws IOException {
        var animations = flatten(animationFiles);
        var inputHash = bakeInputHash(containerId, definitionHash, targetId, animationSet);
        var target = path(containerId, inputHash);
        try {
            cache.materialize("baked-animation", containerId + "/" + inputHash,
                    target, candidate -> validate(candidate, containerId, targetId, inputHash),
                    candidate -> write(candidate, containerId, targetId, inputHash, animations));
        } catch (AssetLoadException error) {
            throw error;
        } catch (IOException error) {
            throw AssetLoadException.access(
                    "Failed to materialize baked animation cache", error);
        }
        var view = open(target);
        if (!view.matches(containerId, targetId, inputHash)) {
            throw AssetLoadException.content("Baked animation cache identity mismatch");
        }
        return view.createAnimationStore(() -> FileChannel.open(target, StandardOpenOption.READ),
                AnimationProtoMapper::animation, fallback, failureGates);
    }

    /**
     * 内置默认模型的动画集。
     *
     * <p>默认模型携带 352 个动画（约 6 MB JSON）。它们只在模型的条件求值真正选中时才会被播放，
     * 启动时全量绑定（解析 molang + 构建关键帧对象）会制造大量长寿对象，是启动内存的主要来源。
     * 这里只保留 proto 列表，{@link AnimationStore#lazy} 在第一次取用某个动画时才绑定；
     * 契约校验（{@code observer}）也推迟到同一次绑定里，保证校验的仍是实际使用的数据。
     */
    public static AnimationStore bindResident(
            Iterable<? extends Map.Entry<String, AnimationFile>> animationFiles,
            BoundAnimationObserver observer) throws IOException {
        var protos = new LinkedHashMap<String,
                com.kinetic.proto.mixel.asset.model.data.Animation>();
        for (var animation : flatten(animationFiles)) {
            protos.put(animation.name(), animation);
        }
        return AnimationStore.lazy(protos.keySet(), new AnimationStore.AnimationSource() {
            @Override
            public com.kinetic.proto.mixel.asset.model.data.Animation load(String animationName) {
                return protos.get(animationName);
            }

            @Override
            public void release(String animationName) {
                protos.remove(animationName);
            }
        }, animation -> {
            final com.kinetic.geckolib3.core.builder.Animation value;
            try {
                value = AnimationProtoMapper.animation(animation);
            } catch (RuntimeException error) {
                throw AssetLoadException.content(
                        "Invalid animation: " + animation.name(), error);
            }
            observer.accept(animation, value);
            return value;
        });
    }

    @FunctionalInterface
    public interface BoundAnimationObserver {
        void accept(com.kinetic.proto.mixel.asset.model.data.Animation source,
                    com.kinetic.geckolib3.core.builder.Animation bound)
                throws IOException;
    }

    Path path(Hash256 containerId, Hash256 inputHash) {
        return bakedRoot.resolve(CACHE_ABI).resolve(containerId.toString())
                .resolve(inputHash + CACHE_SUFFIX);
    }

    static ArrayList<com.kinetic.proto.mixel.asset.model.data.Animation> flatten(
            Iterable<? extends Map.Entry<String,
                    AnimationFile>> animationFiles) throws IOException {
        var result = new LinkedHashMap<String,
                com.kinetic.proto.mixel.asset.model.data.Animation>();
        for (var file : animationFiles) {
            for (var animation : file.getValue().animations()) {
                if (animation.name().isBlank()) {
                    throw AssetLoadException.content(
                            "Empty animation name in animation set: "
                                    + animation.name());
                }
                result.put(animation.name(), animation);
            }
        }
        return new ArrayList<>(result.values());
    }

    static Hash256 bakeInputHash(Hash256 containerId, Hash256 definitionHash,
                                 String targetId, String animationSet) {
        var target = targetId.getBytes(StandardCharsets.UTF_8);
        var setName = animationSet.getBytes(StandardCharsets.UTF_8);
        var input = ByteBuffer.allocate(HASH_DOMAIN.length + Hash256.SIZE * 2
                        + Integer.BYTES + target.length + Integer.BYTES + setName.length)
                .put(HASH_DOMAIN).put(containerId.bytes()).put(definitionHash.bytes())
                .putInt(target.length).put(target)
                .putInt(setName.length).put(setName);
        return ModelHashing.blake3(input.array());
    }

    private static void write(Path file, Hash256 containerId, String targetId,
                              Hash256 inputHash, Iterable<com.kinetic.proto.mixel.asset.model.data.Animation> animations)
            throws IOException {
        try (var writer = new BakedAssetWriter();
             var channel = FileChannel.open(file, StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            writer.setData(containerId, targetId, inputHash, animations);
            writer.write(channel);
            if (com.kinetic.model.storage.CacheDurability.FORCE_DERIVED_CACHE_WRITES) {
                channel.force(true);
            }
        }
    }

    private static boolean validate(Path file, Hash256 containerId,
                                    String targetId, Hash256 inputHash) throws IOException {
        try {
            return open(file).matches(containerId, targetId, inputHash);
        } catch (AssetLoadException error) {
            if (error.reason() == AssetLoadException.Reason.ACCESS) {
                throw error;
            }
            return false;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static BakedAssetView open(Path file) throws IOException {
        final FileChannel opened;
        try {
            opened = FileChannel.open(file, StandardOpenOption.READ);
        } catch (IOException error) {
            throw AssetLoadException.access("Failed to open baked animation cache", error);
        }
        try (var channel = opened) {
            return new BakedAssetView(channel);
        } catch (IOException | RuntimeException error) {
            throw AssetLoadException.content("Invalid baked animation cache", error);
        }
    }
}
