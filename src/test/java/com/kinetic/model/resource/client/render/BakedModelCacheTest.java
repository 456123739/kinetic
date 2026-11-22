package com.kinetic.model.resource.client.render;

import com.kinetic.buffer.ArrayBuffer;
import com.kinetic.buffer.NativeBuffer;
import com.kinetic.format.AssetLoadException;
import com.kinetic.format.schema.baked.model.BakedModelWriter;
import com.kinetic.model.domain.Hash256;
import com.kinetic.model.storage.AtomicSharedCache;
import com.kinetic.natives.render.NativeBakedModel;
import com.kinetic.proto.mixel.asset.model.ModelData;
import com.kinetic.proto.mixel.asset.model.data.Bone;
import com.kinetic.proto.mixel.asset.model.data.Cubes;
import com.kinetic.proto.mixel.asset.model.data.GeoModel;
import com.kinetic.proto.mixel.asset.model.data.GeoProperties;
import com.kinetic.testutil.NativeLibraryExtension;
import com.kinetic.util.ProtoUtil;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "KINETIC_NATIVE_PATH", matches = ".+")
@ExtendWith(NativeLibraryExtension.class)
class BakedModelCacheTest {
    @TempDir Path temp;

    /**
     * 缓存读回要按 locator 建骨骼表。单测里没有客户端启动流程（{@code Kinetic.EVENT_BUS} 为 null），
     * 所以用一个只注册自己 id 的空 locator 类型，不走 {@code PlayerLocator.init()}。
     */
    private static final class TestLocator extends com.kinetic.geckolib3.geo.render.built.GeoLocatorType {
        private TestLocator() {
            super(new net.minecraft.resources.ResourceLocation("kinetic", "baked_cache_test"));
        }
    }

    private static final com.kinetic.geckolib3.geo.render.built.GeoLocatorType LOCATOR = new TestLocator();

    @Test
    void decodesSourceOnlyForColdOrCorruptCache() throws Exception {
        var cache = new BakedModelCache(temp, new AtomicSharedCache(temp));
        var container = new Hash256(new byte[Hash256.SIZE]);
        var textureHash = new byte[Hash256.SIZE];
        var geometry = geometry();
        var definition = ModelData.newBuilder().putGeoModels("main",
                ByteBuffer.wrap(ProtoUtil.serializeToArray(geometry))).build();
        var parses = new AtomicInteger();
        var textureReads = new AtomicInteger();
        BakedModelCache.GeometrySupplier source = () -> {
            parses.incrementAndGet();
            return ModelRenderTargetLoader.geoModel(definition, "main");
        };
        var stopBeforeHostTexture = AssetLoadException.access("Test has no Minecraft mixin host");
        BakedModelCache.TexturePixelsSupplier texture = () -> {
            textureReads.incrementAndGet();
            throw stopBeforeHostTexture;
        };
        assertSame(stopBeforeHostTexture, assertThrows(AssetLoadException.class,
                () -> cache.loadOrBake(container, "main", textureHash, source, texture,
                        29, false, false, false, LOCATOR)));
        assertEquals(1, parses.get());
        assertEquals(1, textureReads.get(), "Cold miss must reach the bake path");

        var hash = BakedModelCache.bakeHash(container, "main", textureHash);
        var path = cache.path(container, hash);
        writeNativeFixture(path, hash, geometry);
        try (var warm = cache.loadOrBake(container, "main", textureHash, source, texture,
                29, false, false, false, LOCATOR)) {
            assertEquals("root", warm.sortedBones().get(0).name());
            assertEquals(1, parses.get(), "Warm hit must not parse source geometry");
            assertEquals(1, textureReads.get(), "Warm hit must not decode bake texture");
        }
        try (var cached = cache.loadExisting(container, "main", textureHash, LOCATOR)) {
            assertEquals("root", cached.sortedBones().get(0).name());
        }
        Files.write(path, new byte[]{1, 2, 3});
        assertSame(stopBeforeHostTexture, assertThrows(AssetLoadException.class,
                () -> cache.loadOrBake(container, "main", textureHash, source, texture,
                        29, false, false, false, LOCATOR)));
        assertEquals(2, parses.get(), "Corrupt entry must parse once for rebuilding");
        assertEquals(2, textureReads.get(), "Corrupt entry must reach the bake path");
        writeNativeFixture(path, hash, geometry);
        try (var repaired = cache.loadOrBake(container, "main", textureHash, source, texture,
                29, false, false, false, LOCATOR)) {
            assertEquals("root", repaired.sortedBones().get(0).name());
            assertEquals(2, parses.get());
        }
    }

    @Test
    void warmHitDecodesTheCacheFileOnce() throws Exception {
        var cache = new BakedModelCache(temp, new AtomicSharedCache(temp));
        var container = new Hash256(new byte[Hash256.SIZE]);
        var textureHash = new byte[Hash256.SIZE];
        var geometry = geometry();
        var hash = BakedModelCache.bakeHash(container, "main", textureHash);
        writeNativeFixture(cache.path(container, hash), hash, geometry);
        BakedModelCache.GeometrySupplier never = () -> {
            throw new AssertionError("命中的缓存不应解析源几何");
        };
        BakedModelCache.TexturePixelsSupplier neverTexture = () -> {
            throw new AssertionError("命中的缓存不应解码贴图");
        };
        for (int i = 0; i < 120; i++) {
            cache.loadOrBake(container, "main", textureHash, never, neverTexture, 29,
                    false, false, false, LOCATOR).close();
        }
        var samples = new java.util.ArrayList<Long>();
        for (int round = 0; round < 9; round++) {
            var before = System.nanoTime();
            for (int i = 0; i < 200; i++) {
                cache.loadOrBake(container, "main", textureHash, never, neverTexture, 29,
                        false, false, false, LOCATOR).close();
            }
            samples.add(System.nanoTime() - before);
        }
        java.util.Collections.sort(samples);
        // 单遍读：一次开文件、一次清单解析、一次原生负载解码。改前的命中路径会再整套走一遍。
        System.out.printf("[baked-cache] warm load %.1f us/次（单遍：开 1 次、清单 1 次、负载 1 次）%n",
                samples.get(4) / 1000.0 / 200);
        assertTrue(samples.get(4) > 0);
    }

    @Test
    void sourceContentFailureKeepsItsProvenance() {
        var cache = new BakedModelCache(temp, new AtomicSharedCache(temp));
        var failure = AssetLoadException.content("Invalid geometry");
        var thrown = assertThrows(AssetLoadException.class, () -> cache.loadOrBake(
                new Hash256(new byte[Hash256.SIZE]), "broken", new byte[Hash256.SIZE],
                () -> { throw failure; },
                () -> { throw new AssertionError("Failed source must not decode texture"); },
                29, false, false, false, LOCATOR));
        assertSame(failure, thrown);
    }

    private static GeoModel geometry() throws Exception {
        return GeoModel.newBuilder()
                .setProperties(GeoProperties.newBuilder()
                        .setTextureWidth(1).setTextureHeight(1).build())
                .addBones(Bone.newBuilder().setName("root")
                        .addPivot(0).addPivot(0).addPivot(0)
                        .addRotate(0).addRotate(0).addRotate(0).setCubeCount(0).build())
                .setCubes(ByteBuffer.wrap(ProtoUtil.serializeToArray(Cubes.newBuilder().build())))
                .build();
    }

    /** Use the native byte-buffer entry point because unit tests do not apply image mixins. */
    private static void writeNativeFixture(Path path, Hash256 hash, GeoModel geometry) throws Exception {
        Files.createDirectories(path.getParent());
        try (var model = ArrayBuffer.move(ProtoUtil.serializeToArray(geometry));
             var pixels = NativeBuffer.allocate(4)) {
            pixels.nio().putInt(0, -1);
            var baked = NativeBakedModel.bake(model, 1, pixels, 1, 1, 29, false);
            try (var data = baked.bakedData();
                 var writer = new BakedModelWriter();
                 var channel = FileChannel.open(path, StandardOpenOption.CREATE,
                         StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                writer.setData(hash.bytes(), geometry, data);
                writer.write(channel);
            }
        }
    }
}
