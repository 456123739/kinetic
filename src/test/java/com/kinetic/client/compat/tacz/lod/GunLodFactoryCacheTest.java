package com.kinetic.client.compat.tacz.lod;

import com.kinetic.model.domain.Hash256;
import com.kinetic.model.resource.client.render.BakedModelCache;
import com.kinetic.model.storage.AtomicSharedCache;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 枪械 LOD 磁盘缓存的"命中/未命中"契约。
 *
 * <p>这里刻意<b>不</b>依赖原生烘焙：验证的是"什么时候允许认为缓存可用"。
 * 唯一允许的结论是"确定可用"或"当作未命中回落冷路径"——绝不能出现"以为是命中、拿到的却是
 * 半成品或错位姿"。
 */
class GunLodFactoryCacheTest {
    private static final ResourceLocation TEXTURE =
            new ResourceLocation("tacz", "textures/gun/uv/ak47.png");

    @Test
    void withoutAnInstalledCacheEverythingMisses(@TempDir Path temp) {
        var container = container(1);
        var hash = textureHash(2);
        var name = GunLodCacheKey.resourceName(3.0f, false);

        assertNull(GunLodFactory.loadCached(null, container, name, hash, TEXTURE, new Vector3f()));
        assertFalse(GunLodFactory.isWarm(null, container, name, hash));
        assertFalse(GunLodFactory.bakeOnly(null, container, name, hash, null, new byte[0],
                new Vector3f()));
    }

    @Test
    void anEmptyCacheMisses(@TempDir Path temp) {
        var cache = cache(temp);
        var container = container(1);
        var hash = textureHash(2);
        var name = GunLodCacheKey.resourceName(3.0f, false);

        assertFalse(GunLodFactory.isWarm(cache, container, name, hash));
        assertNull(assertDoesNotThrow(
                () -> GunLodFactory.loadCached(cache, container, name, hash, TEXTURE,
                        new Vector3f())));
    }

    @Test
    void anchorAloneIsNotAWarmCache(@TempDir Path temp) {
        var cache = cache(temp);
        var container = container(1);
        var hash = textureHash(2);
        var name = GunLodCacheKey.resourceName(3.0f, false);
        // 只写了附属文件、没有烘焙产物：必须仍然算未命中。
        GunLodFactory.writeAnchor(cache.resolveCachePath(container, name, hash), new Vector3f(1, 2, 3));

        assertFalse(GunLodFactory.isWarm(cache, container, name, hash));
        assertNull(assertDoesNotThrow(
                () -> GunLodFactory.loadCached(cache, container, name, hash, TEXTURE,
                        new Vector3f())));
    }

    @Test
    void aCorruptCacheFileMissesInsteadOfThrowing(@TempDir Path temp) throws Exception {
        var cache = cache(temp);
        var container = container(1);
        var hash = textureHash(2);
        var name = GunLodCacheKey.resourceName(3.0f, false);
        var path = cache.resolveCachePath(container, name, hash);
        Files.createDirectories(path.getParent());
        Files.write(path, new byte[]{9, 9, 9, 9, 9, 9, 9, 9});
        GunLodFactory.writeAnchor(path, new Vector3f(1, 2, 3));

        // 损坏的缓存必须被当成未命中，而不是让异常冒到渲染路径。
        assertFalse(GunLodFactory.isWarm(cache, container, name, hash));
        assertNull(assertDoesNotThrow(
                () -> GunLodFactory.loadCached(cache, container, name, hash, TEXTURE,
                        new Vector3f())));
    }

    @Test
    void cachePathFollowsTheKey(@TempDir Path temp) {
        var cache = cache(temp);
        var container = container(1);
        var hash = textureHash(2);

        var a = cache.resolveCachePath(container, GunLodCacheKey.resourceName(3.0f, false), hash);
        var b = cache.resolveCachePath(container, GunLodCacheKey.resourceName(3.0f, false), hash);
        var other = cache.resolveCachePath(container, GunLodCacheKey.resourceName(4.0f, false), hash);

        assertEquals(a, b, "同一个键必须落到同一个文件");
        assertNotEqualsPath(a, other, "换减面参数必须落到不同的文件");
    }

    @Test
    void anchorRoundTripsExactly(@TempDir Path temp) {
        var path = temp.resolve("model.geo.kinetic-cache");
        var anchor = new Vector3f(0.125f, -17.5f, 3.0f);

        GunLodFactory.writeAnchor(path, anchor);
        var read = GunLodFactory.readAnchor(path);

        assertNotNull(read);
        assertEquals(anchor.x, read.x);
        assertEquals(anchor.y, read.y);
        assertEquals(anchor.z, read.z);
    }

    @Test
    void anchorIsRejectedWhenMissingTruncatedOrNotFinite(@TempDir Path temp) throws Exception {
        var path = temp.resolve("model.geo.kinetic-cache");
        var sidecar = path.resolveSibling(path.getFileName() + GunLodFactory.ANCHOR_SUFFIX);

        assertNull(GunLodFactory.readAnchor(path), "文件不存在时必须未命中");

        Files.write(sidecar, new byte[GunLodFactory.ANCHOR_BYTES - 1]);
        assertNull(GunLodFactory.readAnchor(path), "长度不对时必须未命中");

        Files.write(sidecar, ByteBuffer.allocate(GunLodFactory.ANCHOR_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .putFloat(Float.NaN).putFloat(0.0f).putFloat(0.0f).array());
        assertNull(GunLodFactory.readAnchor(path), "非有限数必须未命中");

        Files.write(sidecar, ByteBuffer.allocate(GunLodFactory.ANCHOR_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .putFloat(Float.POSITIVE_INFINITY).putFloat(0.0f).putFloat(0.0f).array());
        assertNull(GunLodFactory.readAnchor(path), "无穷必须未命中");
    }

    @Test
    void anchorWriteReplacesPreviousContent(@TempDir Path temp) {
        var path = temp.resolve("model.geo.kinetic-cache");
        GunLodFactory.writeAnchor(path, new Vector3f(1, 1, 1));
        GunLodFactory.writeAnchor(path, new Vector3f(2, 2, 2));

        var read = GunLodFactory.readAnchor(path);
        assertNotNull(read);
        assertEquals(2.0f, read.x);
    }

    @Test
    void aFailedAnchorWriteIsVisibleToTheCaller(@TempDir Path temp) throws Exception {
        // 目标位置被一个非空目录占住，写入必然失败。
        var path = temp.resolve("model.geo.kinetic-cache");
        var sidecar = path.resolveSibling(path.getFileName() + GunLodFactory.ANCHOR_SUFFIX);
        Files.createDirectory(sidecar);
        Files.writeString(sidecar.resolve("blocker"), "x");

        GunLodFactory.writeAnchor(path, new Vector3f(1, 2, 3));

        // 这是"预热不能虚报成功"的前提：写不进去就必须读不到，调用方才能据此不把枪记成已热。
        assertNull(GunLodFactory.readAnchor(path),
                "写失败的锚点必须读不出来，否则会把没热的枪记成热的、以后再也不扫它");
    }

    @Test
    void aPartialAnchorIsRejected(@TempDir Path temp) throws Exception {
        // 模拟"写到一半"：长度不对就必须当成未命中，绝不能按错位姿渲染。
        var path = temp.resolve("model.geo.kinetic-cache");
        var sidecar = path.resolveSibling(path.getFileName() + GunLodFactory.ANCHOR_SUFFIX);
        Files.write(sidecar, new byte[]{0, 0, 0});

        assertNull(GunLodFactory.readAnchor(path));
    }

    /**
     * 贴图像素的所有权。
     *
     * <p>{@code NativeImage} 没有 Cleaner 也没有 finalizer，只有 {@code close()} 会释放像素内存；
     * 而 {@code BakedModelCache} 的供应商契约是"返回借用的像素，创建者持有所有权"，它自己从不关闭。
     * 所以"谁 decode 谁 close"。这条曾经漏掉：1000 把枪各漏一张 1024²~2048² 的贴图就是几个 GB。
     */
    @Test
    void lazyTextureDecodesOnceAndReleasesOnClose() throws Exception {
        try (var lazy = new GunLodFactory.LazyTexture(texturePngBytes())) {
            var first = lazy.get();
            assertNotNull(first);
            assertSame(first, lazy.get(), "命中路径会多次求值，但只应解码一次");

            lazy.close();

            // NativeImage.close() 会把像素指针清零，此后任何取像素的操作都会拒绝。
            // 这直接证明 close() 真的作用到了那张图上，而不是只清了本地引用。
            assertThrows(IllegalStateException.class, first::makePixelArray,
                    "close() 之后那张图必须已经被释放");
        }
    }

    @Test
    void lazyTextureCloseWithoutDecodeIsHarmless() {
        // 命中磁盘缓存时供应商根本不会被求值，此时关闭不能抛错。
        assertDoesNotThrow(() -> {
            try (var lazy = new GunLodFactory.LazyTexture(new byte[]{1, 2, 3})) {
                // 故意不解码
            }
        });
    }

    /** 造一张真实可解码的 8×8 PNG。 */
    private static byte[] texturePngBytes() throws Exception {
        var image = new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (var y = 0; y < 8; y++) {
            for (var x = 0; x < 8; x++) {
                image.setRGB(x, y, 0xFFFFFFFF);
            }
        }
        try (var out = new java.io.ByteArrayOutputStream()) {
            javax.imageio.ImageIO.write(image, "png", out);
            return out.toByteArray();
        }
    }

    private static BakedModelCache cache(Path root) {
        return new BakedModelCache(root, new AtomicSharedCache(root));
    }

    private static Hash256 container(int seed) {
        var bytes = new byte[Hash256.SIZE];
        Arrays.fill(bytes, (byte) seed);
        return new Hash256(bytes);
    }

    private static byte[] textureHash(int seed) {
        var bytes = new byte[Hash256.SIZE];
        Arrays.fill(bytes, (byte) seed);
        return bytes;
    }

    private static void assertNotEqualsPath(Path a, Path b, String message) {
        assertTrue(!a.equals(b), message);
    }
}
