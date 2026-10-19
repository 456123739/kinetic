package com.kinetic.model.resource.client.render;

import com.kinetic.buffer.ArrayBuffer;
import com.kinetic.buffer.NativeBuffer;
import com.kinetic.client.compat.tacz.lod.GunLodCacheKey;
import com.kinetic.client.compat.tacz.lod.GunLodDecimator;
import com.kinetic.client.compat.tacz.lod.TestGunLocatorType;
import com.kinetic.format.parser.GeoBuilder;
import com.kinetic.format.schema.baked.model.BakedModelWriter;
import com.kinetic.geckolib3.model.AnimatedGeoModel;
import com.kinetic.model.domain.Hash256;
import com.kinetic.model.storage.AtomicSharedCache;
import com.kinetic.natives.render.NativeBakedModel;
import com.kinetic.natives.render.NativeModelState;
import com.kinetic.testutil.NativeLibraryExtension;
import com.kinetic.util.ProtoUtil;
import com.google.gson.Gson;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.system.MemoryUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 烘焙磁盘缓存"写进去 / 读出来"的往返验证（依赖原生库）。
 *
 * <p>枪械 LOD 的提前烘焙完全建立在这条往返上：预热写进磁盘的东西，必须能被运行时的
 * {@code loadExisting} 原样读回。这里用真实 TaCZ ak47 减面后的模型验证：
 * 键 → 路径 → 校验器 → 解码 → extract 出的顶点数，必须与当场烘焙的完全一致。
 *
 * <p><b>为什么不走 {@code BakedModelCache.bakeOnly}</b>：那条路要一个 {@code NativeImage}，
 * 而 {@code NativeBakedModel.bake(NativeImage)} 通过 Mixin 访问器取像素——单测 JVM 里没有
 * Mixin 引导，必然 {@code ClassCastException}。所以这里用 {@code NativeBuffer} 重载做等价烘焙，
 * 再用同一个 {@link BakedModelWriter} 与同一个 {@link BakedModelCache#bakeHash} 落盘。
 * 写入侧与 {@code bakeOnly} 共用同一段 {@code materialize} 调用，所以真正需要独立验证的是
 * "读出来是不是同一份几何"。
 */
@ExtendWith(NativeLibraryExtension.class)
@EnabledIfEnvironmentVariable(named = "KINETIC_NATIVE_PATH", matches = ".+")
class BakedModelDiskRoundTripTest {
    private static final int TEXTURE_SIZE = 64;
    private static final int ORIGIN_VERSION = 29;

    /** 模型内容哈希：往返测试只需要一个稳定值，其内容与几何无关。 */
    private static final byte[] MODEL_HASH = new byte[Hash256.SIZE];

    @Test
    void diskRoundTripPreservesTheExactGeometry(@TempDir Path temp) throws Exception {
        var cache = new BakedModelCache(temp, new AtomicSharedCache(temp));
        var container = GunLodCacheKey.containerId(new ResourceLocation("tacz", "ak47"), MODEL_HASH);
        var resourceName = GunLodCacheKey.resourceName(
                GunLodDecimator.DEFAULT_MIN_BONE_METRIC, false);
        var textureHash = new byte[Hash256.SIZE];
        Arrays.fill(textureHash, (byte) 7);

        var pojo = loadAk47();
        GunLodDecimator.decimate(pojo, GunLodDecimator.DEFAULT_MIN_BONE_METRIC, false);
        var proto = GeoBuilder.build(pojo);
        assertNotNull(proto, "减面后的模型必须能被 GeoBuilder 接受");
        var source = proto.model();
        var boneCount = source.bones().size();

        // 读侧的前提：磁盘上还没有东西。
        assertFalse(cache.isCached(container, resourceName, textureHash));

        // 与 BakedModelCache.bake 等价的落盘：同一个 bakeHash、同一个 writer。
        var bakeHash = BakedModelCache.bakeHash(container, resourceName, textureHash);
        var target = cache.resolveCachePath(container, resourceName, textureHash);
        Files.createDirectories(target.getParent());
        try (var modelData = ArrayBuffer.allocate(source.getSerializedSize());
             var texture = NativeBuffer.allocate(TEXTURE_SIZE * TEXTURE_SIZE * 4);
             var channel = FileChannel.open(target, StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            source.writeTo(ProtoUtil.sink(modelData));
            MemoryUtil.memSet(texture.ptr(), 0xff, texture.size());
            var baked = NativeBakedModel.bake(modelData, boneCount, texture,
                    TEXTURE_SIZE, TEXTURE_SIZE, ORIGIN_VERSION, false);
            try (var bakedData = baked.bakedData();
                 var writer = new BakedModelWriter()) {
                writer.setData(bakeHash.bytes(), source, bakedData);
                writer.write(channel);
            }
        }

        // 读侧：校验器必须接受这个文件。
        assertTrue(cache.isCached(container, resourceName, textureHash),
                "按同一个键写出来的文件必须通过校验");

        // 读回来的模型必须能 extract，且顶点数与当场烘焙的完全一致。
        var fromDisk = cache.loadExisting(container, resourceName, textureHash,
                TestGunLocatorType.get());
        assertNotNull(fromDisk, "命中必须读回模型");
        assertEquals(boneCount, new AnimatedGeoModel(fromDisk).getSortedBones().size(),
                "骨骼数必须原样保留");

        var diskVertices = vertices(fromDisk);
        assertTrue(diskVertices > 0, "读回的模型必须有顶点");

        final int freshVertices;
        try (var modelData = ArrayBuffer.allocate(source.getSerializedSize());
             var texture = NativeBuffer.allocate(TEXTURE_SIZE * TEXTURE_SIZE * 4)) {
            source.writeTo(ProtoUtil.sink(modelData));
            MemoryUtil.memSet(texture.ptr(), 0xff, texture.size());
            var baked = NativeBakedModel.bake(modelData, boneCount, texture,
                    TEXTURE_SIZE, TEXTURE_SIZE, ORIGIN_VERSION, false);
            try (var bakedData = baked.bakedData()) {
                var read = NativeBakedModel.read(bakedData, baked.sortedBoneIndices().length);
                freshVertices = vertices(new com.kinetic.geckolib3.geo.render.built.GeoModel(
                        "test:fresh", source, TestGunLocatorType.get(), read));
            }
        }
        assertEquals(freshVertices, diskVertices,
                "磁盘缓存读回的顶点数必须与当场烘焙的完全一致");
    }

    @Test
    void changingTheKeyMissesTheExistingFile(@TempDir Path temp) throws Exception {
        var cache = new BakedModelCache(temp, new AtomicSharedCache(temp));
        var container = GunLodCacheKey.containerId(new ResourceLocation("tacz", "ak47"), MODEL_HASH);
        var textureHash = new byte[Hash256.SIZE];
        Arrays.fill(textureHash, (byte) 7);

        var pojo = loadAk47();
        GunLodDecimator.decimate(pojo, GunLodDecimator.DEFAULT_MIN_BONE_METRIC, false);
        var source = GeoBuilder.build(pojo).model();
        var boneCount = source.bones().size();
        var name = GunLodCacheKey.resourceName(GunLodDecimator.DEFAULT_MIN_BONE_METRIC, false);
        var target = cache.resolveCachePath(container, name, textureHash);
        Files.createDirectories(target.getParent());
        try (var modelData = ArrayBuffer.allocate(source.getSerializedSize());
             var texture = NativeBuffer.allocate(TEXTURE_SIZE * TEXTURE_SIZE * 4);
             var channel = FileChannel.open(target, StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            source.writeTo(ProtoUtil.sink(modelData));
            MemoryUtil.memSet(texture.ptr(), 0xff, texture.size());
            var baked = NativeBakedModel.bake(modelData, boneCount, texture,
                    TEXTURE_SIZE, TEXTURE_SIZE, ORIGIN_VERSION, false);
            try (var bakedData = baked.bakedData();
                 var writer = new BakedModelWriter()) {
                writer.setData(BakedModelCache.bakeHash(container, name, textureHash).bytes(),
                        source, bakedData);
                writer.write(channel);
            }
        }
        assertTrue(cache.isCached(container, name, textureHash));

        // 换了减面参数（= 换了资源名）就必须落到另一个文件、判定为冷。
        assertFalse(cache.isCached(container,
                GunLodCacheKey.resourceName(GunLodDecimator.DEFAULT_MIN_BONE_METRIC + 1.0f, false),
                textureHash), "改减面参数后必须重新烘焙");
        // 换了贴图内容同样必须失效。
        var otherTexture = new byte[Hash256.SIZE];
        Arrays.fill(otherTexture, (byte) 9);
        assertFalse(cache.isCached(container, name, otherTexture), "换贴图后必须重新烘焙");
    }

    private static int vertices(com.kinetic.geckolib3.geo.render.built.GeoModel model) {
        try (var state = NativeModelState.create()) {
            var animated = new AnimatedGeoModel(model);
            assertTrue(state.extract(animated.getModel().bakedModel(),
                    animated.getBoneAttributes()), "extract 必须成功");
            return state.getTotalVertexCount();
        }
    }

    private static com.kinetic.format.parser.pojo.model.GeoModel loadAk47() throws Exception {
        try (var in = BakedModelDiskRoundTripTest.class.getResourceAsStream("/tacz/ak47_geo.json")) {
            assertNotNull(in, "测试资源缺失: /tacz/ak47_geo.json");
            return new Gson().fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    com.kinetic.format.parser.pojo.model.GeoModel.class);
        }
    }
}
