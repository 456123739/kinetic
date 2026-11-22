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

    /**
     * 进程级实例。玩家模型的烘焙缓存由 {@code ClientModelService} 在资源重载时重建，
     * 而后台烘焙（原先的枪械 LOD 构建器）跑在 worker 线程、拿不到那个对象，
     * 所以在这里留一个进程级引用：两边共用同一个根目录、同一套 {@code CACHE_ABI} 与
     * capabilityKey，因此磁盘缓存天然互通。
     *
     * <p>只在客户端初始化/资源重载时写入，读方是 worker 线程，所以用 volatile。
     */
    private static volatile BakedModelCache shared;

    /** 安装进程级实例；传 {@code null} 表示卸载（资源重载期间）。 */
    public static void installShared(@Nullable BakedModelCache instance) {
        shared = instance;
    }

    /** 进程级实例；未安装时返回 {@code null}（此时枪械 LOD 退回不落盘的行为）。 */
    public static @Nullable BakedModelCache shared() {
        return shared;
    }

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

    /**
     * 只把烘焙结果落到磁盘，<b>不读回原生模型</b>。
     *
     * <p>给后台预热用：预热只关心"这个键对应的缓存文件存在且合法"，读回会立刻在原生堆上
     * 分配一份模型（每把枪约 200~300 KB），而预热的结果通常几十分钟内都不会被渲染到。
     * 走这条路预热期间的原生占用是零。
     *
     * <p>缓存已存在且通过校验时 {@code source} 与 {@code texture} 都不会被触碰。
     */
    public void bakeOnly(Hash256 containerId, String resourceName, byte[] textureHash,
                         com.kinetic.proto.mixel.asset.model.data.GeoModel source,
                         TexturePixelsSupplier texture, int originVersion, boolean forceCulling,
                         boolean forceTranslucent, boolean hasPbr) throws IOException {
        var bakeHash = bakeHash(containerId, resourceName, textureHash);
        var target = path(containerId, bakeHash);
        try {
            cache.materialize("baked", containerId + "/" + bakeHash, target,
                    candidate -> validate(candidate, bakeHash),
                    candidate -> bake(candidate, bakeHash, source, texture, originVersion,
                            forceCulling, forceTranslucent, hasPbr));
        } catch (AssetLoadException error) {
            throw error;
        } catch (IOException error) {
            throw AssetLoadException.access("Failed to materialize baked model cache", error);
        }
    }

    /**
     * 该键的缓存文件路径。给"随缓存一起存活的附属文件"用（枪械 LOD 的摆放锚点），
     * 附属文件的路径由缓存文件路径派生，两者天然同生共死。
     */
    public Path resolveCachePath(Hash256 containerId, String resourceName, byte[] textureHash) {
        return path(containerId, bakeHash(containerId, resourceName, textureHash));
    }

    /** 该键是否已经有合法的缓存文件（不读回原生模型）。 */
    public boolean isCached(Hash256 containerId, String resourceName, byte[] textureHash)
            throws IOException {
        var bakeHash = bakeHash(containerId, resourceName, textureHash);
        return validate(path(containerId, bakeHash), bakeHash);
    }

    public GeoModel loadExisting(Hash256 containerId, String resourceName,
                                 byte[] textureHash,
                                 GeoLocatorType locatorType) throws IOException {
        var bakeHash = bakeHash(containerId, resourceName, textureHash);
        var target = path(containerId, bakeHash);
        if (!validate(target, bakeHash)) {
            throw AssetLoadException.access("Baked model cache is unavailable");
        }
        return read(target, bakeHash, locatorType);
    }

    Path path(Hash256 containerId, Hash256 bakeHash) {
        return bakedRoot.resolve(CACHE_ABI).resolve(capabilityKey)
                .resolve(containerId.toString()).resolve(bakeHash + CACHE_SUFFIX);
    }

    /**
     * 本 profile 的目录：{@code <bakedRoot>/<CACHE_ABI>/<capabilityKey>}。
     *
     * <p>烘焙产物都在这下面，所以"与烘焙产物同生共死"的附属记录（枪械预热的枪包索引）也放在这里：
     * 缓存 ABI 或原生能力一变，整个目录就换了，旧记录不会被拿去套新格式。
     */
    public Path profileRoot() {
        return bakedRoot.resolve(CACHE_ABI).resolve(capabilityKey);
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
            channel.force(true);
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
