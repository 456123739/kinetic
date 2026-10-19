package com.kinetic.client.compat.tacz.lod;

import com.kinetic.buffer.ArrayBuffer;
import com.kinetic.buffer.NativeBuffer;
import com.kinetic.format.parser.GeoBuilder;
import com.kinetic.format.parser.pojo.model.GeoModel;
import com.kinetic.geckolib3.model.AnimatedGeoModel;
import com.kinetic.natives.render.NativeBakedModel;
import com.kinetic.natives.render.NativeModelState;
import com.kinetic.testutil.NativeLibraryExtension;
import com.kinetic.util.ProtoUtil;
import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lwjgl.system.MemoryUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GunLodHandle} 的端到端验证（依赖原生库）。
 *
 * <p>覆盖"渲染就绪"这一段：减面后的真实 TaCZ 模型 → {@link GeoBuilder} → 原生烘焙 →
 * {@link AnimatedGeoModel} → {@link NativeModelState#extract} → 顶点数。
 * 这是本特性里最容易出错的一环（骨骼属性数组、烘焙选项、extract 的静态姿态），
 * 也是唯一无法靠纯 Java 单测发现的问题。
 *
 * <p>唯一没被覆盖的是 TaCZ 枪 id → display 查询与 PNG 贴图读取（需要 TaCZ 运行时与资源管理器）。
 * 那两步的失败语义是"返回 null"，由 {@link GunLodFactoryTest} 与隔离测试守住。
 */
@ExtendWith(NativeLibraryExtension.class)
@EnabledIfEnvironmentVariable(named = "KINETIC_NATIVE_PATH", matches = ".+")
class GunLodHandleTest {
    private static final int TEXTURE_SIZE = 64;
    private static final int ORIGIN_VERSION = 29;
    private static final ResourceLocation TEXTURE =
            new ResourceLocation("tacz", "textures/gun/uv/ak47.png");

    @Test
    void decimatedGunHandlePreparesAndCutsVertices() throws Exception {
        var full = prepareVertices(false);
        var lod = prepareVertices(true);
        System.out.println("[LOD] ak47 vertices: full=" + full + " lod=" + lod);
        assertTrue(full > 0, "完整模型必须能 extract 出顶点");
        assertTrue(lod > 0, "简化模型必须能 extract 出顶点");
        assertTrue(lod < full, "简化模型的顶点数必须低于完整模型");
        assertTrue(lod * 4 < full * 3, "简化模型至少要砍掉 25% 的顶点，否则这个 LOD 没有意义");
    }

    @Test
    void handleIsStableAcrossRepeatedPrepare() throws Exception {
        var handle = buildHandle(true);
        assertNotNull(handle, "句柄必须构建成功");
        assertTrue(handle.prepare(), "首次 prepare 必须成功");
        var vertices = handle.vertexCount();
        assertEquals(94, handle.boneCount(), "骨骼一律保留");
        assertEquals(TEXTURE, handle.texture(), "贴图位置必须原样保留");
        assertEquals(0.6f, handle.scale().x, 1e-6f, "逐轴缩放必须原样保留");
        assertNotNull(handle.anchor(), "必须带上手节点锚点");

        // 第二次 prepare 必须复用同一份原生状态，不重新 extract。
        assertTrue(handle.prepare());
        assertEquals(vertices, handle.vertexCount(), "重复 prepare 不得改变顶点数");

        // 释放后可以重新 prepare（资源重载路径）。
        handle.release();
        assertTrue(handle.prepare(), "release 之后必须能重新 prepare");
        assertEquals(vertices, handle.vertexCount(), "重新 prepare 的顶点数必须一致");
        handle.release();
    }

    private static int prepareVertices(boolean decimate) throws Exception {
        var handle = buildHandle(decimate);
        assertNotNull(handle, "句柄必须构建成功");
        assertTrue(handle.prepare(), "prepare 必须成功");
        var count = handle.vertexCount();
        handle.release();
        return count;
    }

    private static GunLodHandle buildHandle(boolean decimate) throws Exception {
        var pojo = loadAk47();
        if (decimate) {
            GunLodDecimator.decimate(pojo, GunLodDecimator.DEFAULT_MIN_BONE_METRIC, false);
        }
        GeoBuilder.Result proto = GeoBuilder.build(pojo);
        var source = proto.model();
        var boneCount = source.bones().size();
        try (var modelData = ArrayBuffer.allocate(source.getSerializedSize());
             var texture = NativeBuffer.allocate(TEXTURE_SIZE * TEXTURE_SIZE * 4)) {
            source.writeTo(ProtoUtil.sink(modelData));
            MemoryUtil.memSet(texture.ptr(), 0xff, texture.size());
            var baked = NativeBakedModel.bake(modelData, boneCount, texture,
                    TEXTURE_SIZE, TEXTURE_SIZE, ORIGIN_VERSION, false);
            try (var bakedData = baked.bakedData()) {
                var read = NativeBakedModel.read(bakedData, baked.sortedBoneIndices().length);
                var runtime = new com.kinetic.geckolib3.geo.render.built.GeoModel(
                        "test:gun-lod", source, TestGunLocatorType.get(), read);
                return new GunLodHandle(new AnimatedGeoModel(runtime), TEXTURE,
                        new org.joml.Vector3f(0.6f, 0.6f, 0.6f),
                        GunLodDecimator.handAnchor(pojo));
            }
        }
    }

    private static GeoModel loadAk47() {
        try (var in = GunLodHandleTest.class.getResourceAsStream("/tacz/ak47_geo.json")) {
            assertNotNull(in, "测试资源缺失: /tacz/ak47_geo.json");
            var text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            var model = new Gson().fromJson(stripComments(text), GeoModel.class);
            assertNotNull(model, "解析失败");
            return model;
        } catch (Exception e) {
            throw new AssertionError("读取 /tacz/ak47_geo.json 失败", e);
        }
    }

    /** TaCZ 的 geo JSON 带 {@code //} 注释，Gson 默认不接受，测试里先去掉行尾注释。 */
    private static String stripComments(String text) {
        var sb = new StringBuilder(text.length());
        for (var line : text.split("\n", -1)) {
            var idx = line.indexOf("//");
            sb.append(idx >= 0 ? line.substring(0, idx) : line).append('\n');
        }
        return sb.toString();
    }
}
