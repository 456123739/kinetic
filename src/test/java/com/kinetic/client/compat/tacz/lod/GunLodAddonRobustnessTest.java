package com.kinetic.client.compat.tacz.lod;

import com.kinetic.format.parser.pojo.model.Bone;
import com.kinetic.format.parser.pojo.model.Cube;
import com.kinetic.format.parser.pojo.model.GeoModel;
import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 附属包风格模型的健壮性回归。
 *
 * <p>附属包只会比官方枪更复杂，而且<b>不保证沿用官方枪的骨骼命名</b>。这里用一个刻意构造的
 * "最坏情况"夹具覆盖官方模型覆盖不到的分支：
 * <ul>
 *   <li>没有 {@code thirdperson_hand}——锚点必须回退，而不是退化成"不摆放"；</li>
 *   <li>两个 {@code minecraft:geometry} 条目——只有<b>第一个</b>会被渲染（{@code GeoBuilder} 只取
 *       {@code get(0)}），减面也只处理它；第二个是幽灵几何，它的体量不得影响阈值
 *       （见 {@code GunLodAuditRegressionTest.ghostGeometriesDoNotRaiseTheAdaptiveThreshold}）；</li>
 *   <li>盒式 UV 与逐面 UV 混用——面级剔除不能只支持一种；</li>
 *   <li>握把由 24 个 1×1×1 小块拼成（单块最长边只有 1）——必须靠表面积判据救回来；</li>
 *   <li>只有 3 个极小块的小零件——两个判据都不该保它。</li>
 * </ul>
 */
class GunLodAddonRobustnessTest {

    @Test
    void handlesAddonStyleModelWithoutHandNode() {
        var model = load();
        assertEquals(2, model.minecraftGeometry.size(), "夹具必须有两个几何条目");
        var fullCubes = GunLodDecimator.cubeCount(model);
        assertTrue(fullCubes > 25, "夹具规模: " + fullCubes);

        // 锚点必须与 TaCZ 逐位一致：没有 thirdperson_hand 时 TaCZ 不做任何摆放，
        // 所以这里必须是零向量——不能"回退到 root 或几何中心"，那会让枪相对 TaCZ 偏位。
        var anchor = GunLodDecimator.handAnchor(model);
        assertEquals(0.0f, anchor.x, 1e-4f, "没有 thirdperson_hand 时必须与 TaCZ 一致：不摆放");
        assertEquals(0.0f, anchor.y, 1e-4f, "没有 thirdperson_hand 时必须与 TaCZ 一致：不摆放");
        assertEquals(0.0f, anchor.z, 1e-4f, "没有 thirdperson_hand 时必须与 TaCZ 一致：不摆放");

        var before = cubesPerBone(model);
        GunLodDecimator.decimateBonesOnly(model, GunLodDecimator.DEFAULT_MIN_BONE_METRIC, false);

        // 握把式零件（24 个 1×1×1）必须整体存活——这是"只看最长边不普适"的核心回归。
        assertTrue(GunLodDecimator.boneHasCubes(model, "grip_cluster"),
                "由小方块拼成的握把必须靠表面积判据存活");
        // 极小的细节零件整体消失。
        assertTrue(!GunLodDecimator.boneHasCubes(model, "tiny_detail"),
                "极小的细节零件应整体消失");

        // 结构性不变量：不留残片、不增加方块、骨骼层级不变。
        var after = cubesPerBone(model);
        for (var entry : before.entrySet()) {
            var now = after.getOrDefault(entry.getKey(), 0);
            assertTrue(now == 0 || now == entry.getValue(),
                    entry.getKey() + " 被削成了残片: " + entry.getValue() + " -> " + now);
        }
        assertTrue(GunLodDecimator.cubeCount(model) <= fullCubes, "方块数不应增加");

        // 面级剔除必须能同时吃下盒式 UV 与逐面 UV，且不抛异常。
        var beforeCull = GunLodDecimator.faceCount(model);
        GunLodDecimator.cullAllFaces(model);
        assertTrue(GunLodDecimator.faceCount(model) <= beforeCull, "面数不应增加");
    }

    /** 没有任何已知定位节点的模型：锚点必须为零，与 TaCZ 的"不摆放"一致。 */
    @Test
    void anchorIsZeroWhenNoKnownNodeExists() {
        var model = new GeoModel();
        var geometry = new com.kinetic.format.parser.pojo.model.Geometry();
        geometry.bones = new java.util.ArrayList<>();
        var bone = new Bone();
        bone.name = "totally_unknown_node";
        bone.pivot = new float[]{0, 0, 0};
        var cube = new Cube();
        cube.origin = new float[]{-4, 2, -2};
        cube.size = new float[]{8, 4, 4};
        bone.cubes = new java.util.ArrayList<>(java.util.List.of(cube));
        geometry.bones.add(bone);
        model.minecraftGeometry = new java.util.ArrayList<>(java.util.List.of(geometry));

        var anchor = GunLodDecimator.handAnchor(model);
        assertEquals(0.0f, anchor.lengthSquared(), 1e-6f,
                "没有 thirdperson_hand 时 TaCZ 不做摆放，锚点必须为零");
    }

    private static Map<String, Integer> cubesPerBone(GeoModel model) {
        var counts = new LinkedHashMap<String, Integer>();
        for (var geometry : model.minecraftGeometry) {
            for (var bone : geometry.bones) {
                if (bone.cubes != null && !bone.cubes.isEmpty()) {
                    counts.put(bone.name, bone.cubes.size());
                }
            }
        }
        return counts;
    }

    private static GeoModel load() {
        try (var in = GunLodAddonRobustnessTest.class
                .getResourceAsStream("/tacz/synthetic_addon_geo.json")) {
            assertNotNull(in, "测试资源缺失");
            var model = new Gson().fromJson(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8), GeoModel.class);
            assertNotNull(model, "解析失败");
            return model;
        } catch (Exception e) {
            throw new AssertionError("读取夹具失败", e);
        }
    }
}
