package com.kinetic.format.parser;

import com.kinetic.buffer.ArrayBuffer;
import com.kinetic.buffer.NativeBuffer;
import com.kinetic.client.compat.tacz.lod.GunLodDecimator;
import com.kinetic.format.parser.pojo.model.GeoModel;
import com.kinetic.natives.render.NativeBakedModel;
import com.kinetic.testutil.NativeLibraryExtension;
import com.kinetic.util.ProtoUtil;
import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lwjgl.system.MemoryUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 枪械 LOD 的端到端管线验证：真实 TaCZ ak47 模型 → 减面 → KINETIC 的 Bedrock 解析 → 原生烘焙。
 *
 * <p>这条链路就是"KINETIC 自己造 LOD"的核心。唯一没有覆盖的是 TaCZ 的枪 id → display 查询
 * （那需要 TaCZ 运行时），其余每一环都在这里被验证。
 *
 * <p>解析与减面部分的期望值由独立的 Python 脚本在同一文件上量得，用于交叉验证
 * {@link GeoBuilder} 自己的统计。
 */
class TaczGunLodPipelineTest {
    private static final float THRESHOLD = GunLodDecimator.DEFAULT_MIN_BONE_METRIC;
    private static final int TEXTURE_SIZE = 64;
    private static final int ORIGIN_VERSION = 29;

    /** 不依赖原生库：证明真实 TaCZ 模型能被减面并被 KINETIC 的 Bedrock 解析器接受。 */
    @Test
    void decimatedRealGunModelParsesAndMatchesExpectedCounts() throws Exception {
        GeoModel pojo = loadAk47();
        assertEquals(617, GunLodDecimator.cubeCount(pojo), "减面前方块数");
        assertEquals(2455, GunLodDecimator.faceCount(pojo), "减前面数");

        GunLodDecimator.decimate(pojo, THRESHOLD, false);

        GeoBuilder.Result result = GeoBuilder.build(pojo);
        assertNotNull(result, "GeoBuilder 必须接受减面后的 TaCZ 模型");

        // 交叉验证：GeoBuilder 自己统计的方块/面数必须与减面器一致。
        assertEquals(278, result.stats.cubes, "GeoBuilder 统计的方块数");
        assertEquals(1210, result.stats.faces, "GeoBuilder 统计的面数");
        assertEquals(94, result.stats.bones, "骨骼数（骨骼一律保留）");
        assertTrue(result.stats.faces * 3 <= 2443 * 2, "面数应至少降 30%");
    }

    /** 依赖原生库：证明减面后的模型能被 KINETIC 的原生烘焙管线接受。 */
    @Test
    @EnabledIfEnvironmentVariable(named = "KINETIC_NATIVE_PATH", matches = ".+")
    @ExtendWith(NativeLibraryExtension.class)
    void decimatedRealGunModelBakesThroughNativePipeline() throws Exception {
        GeoModel pojo = loadAk47();
        GunLodDecimator.decimate(pojo, THRESHOLD, false);
        var proto = GeoBuilder.build(pojo).model;

        try (var modelData = ArrayBuffer.allocate(proto.getSerializedSize());
             var texture = NativeBuffer.allocate(TEXTURE_SIZE * TEXTURE_SIZE * 4)) {
            proto.writeTo(ProtoUtil.sink(modelData));
            MemoryUtil.memSet(texture.ptr(), 0xff, texture.size());

            var baked = NativeBakedModel.bake(modelData, 94, texture,
                    TEXTURE_SIZE, TEXTURE_SIZE, ORIGIN_VERSION, false);
            try (var bakedData = baked.bakedData()) {
                var read = NativeBakedModel.read(bakedData, 94);
                try (var bakedModel = read.bakedModel()) {
                    assertEquals(94, bakedModel.getInfo().boneCount(), "烘焙后骨骼数");
                }
            }
        }
    }

    private static GeoModel loadAk47() throws Exception {
        try (var in = TaczGunLodPipelineTest.class.getResourceAsStream("/tacz/ak47_geo.json")) {
            assertNotNull(in, "测试资源缺失: /tacz/ak47_geo.json");
            var text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            var model = new Gson().fromJson(stripComments(text), GeoModel.class);
            assertNotNull(model, "解析失败");
            return model;
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
