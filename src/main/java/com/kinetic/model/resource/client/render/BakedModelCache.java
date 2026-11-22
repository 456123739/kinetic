package com.kinetic.model.resource.client.render;

import com.kinetic.format.AssetLoadException;
import com.kinetic.format.schema.baked.model.BakedModelConstant;
import com.kinetic.format.schema.baked.model.BakedModelView;
import com.kinetic.format.schema.baked.model.BakedModelWriter;
import com.kinetic.geckolib3.geo.render.built.GeoLocatorType;
import com.kinetic.geckolib3.geo.render.built.GeoModel;
import com.kinetic.model.domain.Hash256;
import com.kinetic.model.storage.AtomicSharedCache;
import com.kinetic.model.storage.ModelHashing;
import com.kinetic.natives.render.NativeBakedModel;
import com.mojang.blaze3d.platform.NativeImage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

public final class BakedModelCache {
    static final String CACHE_SUFFIX = ".geo.kinetic-cache";
    static final String CACHE_ABI = "renderer-0.2.0-unstable";
    private static final byte[] HASH_DOMAIN =
            ("kinetic." + CACHE_ABI + "\0").getBytes(StandardCharsets.UTF_8);

    private final Path bakedRoot;
    private final AtomicSharedCache cache;
    private final String capabilityKey;

    public BakedModelCache(Path bakedRoot, AtomicSharedCache cache) {
        this.bakedRoot = bakedRoot;
        this.cache = cache;
        this.capabilityKey = sanitize(System.getProperty("os.arch", "unknown")) + "-"
                + sanitize(System.getProperty("os.name", "unknown")) + "-simd-"
                + Integer.toUnsignedString(NativeBakedModel.capability()) + "-bake-"
                + BakedModelConstant.CURRENT_VERSION;
    }

    String profileKey() {
        return CACHE_ABI + "/" + capabilityKey;
    }

    public GeoModel loadOrBake(Hash256 containerId, String resourceName,
                               byte[] textureHash, com.kinetic.proto.mixel.asset.model.data.GeoModel source,
                               TexturePixelsSupplier texture, int originVersion, boolean forceCulling,
                               boolean forceTranslucent, boolean hasPbr, GeoLocatorType locatorType) throws IOException {
        return loadOrBake(containerId, resourceName, textureHash, () -> source, texture,
                originVersion, forceCulling, forceTranslucent, hasPbr, locatorType);
    }

    /** Source geometry is decoded only when a verified cache entry must be rebuilt. */
    public GeoModel loadOrBake(Hash256 containerId, String resourceName,
                               byte[] textureHash, GeometrySupplier source,
                               TexturePixelsSupplier texture, int originVersion, boolean forceCulling,
                               boolean forceTranslucent, boolean hasPbr, GeoLocatorType locatorType) throws IOException {
        var bakeHash = bakeHash(containerId, resourceName, textureHash);
        var target = path(containerId, bakeHash);
        // 命中时只开一次文件：validate() 之后再 read() 会把清单和原生负载各读两遍，
        // 而热启动/热切换几乎全是命中。失效（缺失、截断、哈希不符）仍走下面带锁的重建路径。
        var cached = readIfValid(target, bakeHash, locatorType);
        if (cached != null) {
            return cached;
        }
        try {
            cache.materialize("baked", containerId + "/" + bakeHash, target,
                    candidate -> validate(candidate, bakeHash),
                    candidate -> bake(candidate, bakeHash, source.get(), texture, originVersion,
                            forceCulling, forceTranslucent, hasPbr));
        } catch (AssetLoadException error) {
            throw error;
        } catch (IOException error) {
            throw AssetLoadException.access("Failed to materialize baked model cache", error);
        }
        return read(target, bakeHash, locatorType);
    }

    public GeoModel loadExisting(Hash256 containerId, String resourceName,
                                 byte[] textureHash,
                                 GeoLocatorType locatorType) throws IOException {
        var bakeHash = bakeHash(containerId, resourceName, textureHash);
        var target = path(containerId, bakeHash);
        var cached = readIfValid(target, bakeHash, locatorType);
        if (cached == null) {
            throw AssetLoadException.access("Baked model cache is unavailable");
        }
        return cached;
    }

    Path path(Hash256 containerId, Hash256 bakeHash) {
        return bakedRoot.resolve(CACHE_ABI).resolve(capabilityKey)
                .resolve(containerId.toString()).resolve(bakeHash + CACHE_SUFFIX);
    }

    public static GeoModel bakeResident(com.kinetic.proto.mixel.asset.model.data.GeoModel source,
                                        String resourceName,
                                        TexturePixelsSupplier textureSource,
                                        int originVersion, boolean forceCulling,
                                        boolean forceTranslucent, boolean hasPbr,
                                        GeoLocatorType locatorType) throws IOException {
        var texture = textureSource.get();
        final NativeBakedModel.BakeResult baked;
        try {
            baked = NativeBakedModel.bake(source, texture, originVersion,
                    forceCulling, forceTranslucent, hasPbr);
        } catch (RuntimeException error) {
            throw AssetLoadException.content("Failed to bake resident model", error);
        }
        try (var bakedData = baked.bakedData()) {
            final NativeBakedModel.ReadResult nativeModel;
            try {
                nativeModel = NativeBakedModel.read(
                        bakedData, baked.sortedBoneIndices().length);
            } catch (RuntimeException error) {
                throw AssetLoadException.content("Failed to read resident baked model", error);
            }
            return new GeoModel("default:" + resourceName, source, locatorType, nativeModel);
        }
    }

    private static void bake(Path destination, Hash256 bakeHash, com.kinetic.proto.mixel.asset.model.data.GeoModel source,
                             TexturePixelsSupplier textureSource, int originVersion, boolean forceCulling,
                             boolean forceTranslucent, boolean hasPbr) throws IOException {
        var texture = textureSource.get();
        final NativeBakedModel.BakeResult baked;
        try {
            baked = NativeBakedModel.bake(source, texture, originVersion,
                    forceCulling, forceTranslucent, hasPbr);
        } catch (RuntimeException error) {
            throw AssetLoadException.content("Failed to bake model", error);
        }
        try (var result = baked.bakedData();
             var writer = new BakedModelWriter();
             var channel = FileChannel.open(destination, StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            writer.setData(bakeHash.bytes(), source, result);
            writer.write(channel);
            if (com.kinetic.model.storage.CacheDurability.FORCE_DERIVED_CACHE_WRITES) {
                channel.force(true);
            }
        }
    }

    private static GeoModel read(Path file, Hash256 expected, GeoLocatorType locatorType) throws IOException {
        final FileChannel opened;
        try {
            opened = FileChannel.open(file, StandardOpenOption.READ);
        } catch (IOException error) {
            throw AssetLoadException.access("Failed to open baked model cache", error);
        }
        try (var channel = opened) {
            var view = new BakedModelView(channel);
            if (!view.bakeHash().equals(expected)) {
                throw AssetLoadException.content("Baked model cache key mismatch");
            }
            var data = view.readModelData(channel);
            if (data == null) {
                throw AssetLoadException.content("Baked model cache has no native payload");
            }
            try (data) {
                final NativeBakedModel.ReadResult nativeModel;
                try {
                    nativeModel = NativeBakedModel.read(
                            data, view.model().bones().size());
                } catch (RuntimeException error) {
                    throw AssetLoadException.content("Failed to decode baked model cache", error);
                }
                return new GeoModel(expected.toString(), view.model(), locatorType, nativeModel);
            }
        } catch (AssetLoadException error) {
            throw error;
        } catch (IOException | RuntimeException error) {
            throw AssetLoadException.content("Invalid baked model cache", error);
        }
    }

    /**
     * 打开一次就完成"校验 + 解码"。
     *
     * <p>返回 {@code null} 表示这份缓存不可用（缺失、截断、哈希不符、原生负载损坏），调用方应重建；
     * 真正的 I/O 错误仍然抛出 {@link AssetLoadException}，不会被误当成"没有缓存"。
     */
    private static @Nullable GeoModel readIfValid(Path file, Hash256 expected,
                                                  GeoLocatorType locatorType) throws IOException {
        final FileChannel opened;
        try {
            opened = FileChannel.open(file, StandardOpenOption.READ);
        } catch (NoSuchFileException missing) {
            return null;
        } catch (IOException error) {
            throw AssetLoadException.access("Failed to open baked model cache", error);
        }
        try (var channel = opened) {
            var view = new BakedModelView(channel);
            if (!view.bakeHash().equals(expected)) {
                return null;
            }
            try (var data = view.readModelData(channel)) {
                if (data == null) {
                    return null;
                }
                final NativeBakedModel.ReadResult nativeModel;
                try {
                    nativeModel = NativeBakedModel.read(data, view.model().bones().size());
                } catch (RuntimeException error) {
                    return null;
                }
                return new GeoModel(expected.toString(), view.model(), locatorType, nativeModel);
            }
        } catch (AssetLoadException error) {
            if (error.reason() == AssetLoadException.Reason.ACCESS) {
                throw error;
            }
            return null;
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }

    private static boolean validate(Path file, Hash256 expected) throws IOException {
        final FileChannel opened;
        try {
            opened = FileChannel.open(file, StandardOpenOption.READ);
        } catch (NoSuchFileException missing) {
            return false;
        } catch (IOException error) {
            throw AssetLoadException.access("Failed to open baked model cache", error);
        }
        try (var channel = opened) {
            var view = new BakedModelView(channel);
            if (!view.bakeHash().equals(expected)) {
                return false;
            }
            try (var data = view.readModelData(channel)) {
                return data != null;
            }
        } catch (AssetLoadException error) {
            if (error.reason() == AssetLoadException.Reason.ACCESS) {
                throw error;
            }
            return false;
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    static Hash256 bakeHash(Hash256 containerId, String resourceName, byte[] textureHash) {
        var name = resourceName.getBytes(StandardCharsets.UTF_8);
        var input = ByteBuffer.allocate(HASH_DOMAIN.length + Hash256.SIZE + Integer.BYTES * 2
                + name.length
                + textureHash.length);
        input.put(HASH_DOMAIN).put(containerId.bytes())
                .putInt(name.length).put(name)
                .putInt(textureHash.length).put(textureHash);
        return ModelHashing.blake3(input.array());
    }

    private static String sanitize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
    }

    @FunctionalInterface
    public interface GeometrySupplier {
        com.kinetic.proto.mixel.asset.model.data.GeoModel get() throws IOException;
    }

    @FunctionalInterface
    public interface TexturePixelsSupplier {
        /** Returns borrowed pixels; the caller that created the supplier retains ownership. */
        NativeImage get() throws IOException;
    }
}
