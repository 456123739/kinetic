package com.kinetic.client.compat.tacz.lod;

import com.kinetic.format.parser.pojo.model.GeoModel;
import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用 TaCZ 官方 ak47 的<b>真实</b>模型验证减面。
 *
 * <p>期望值是用独立的 Python 脚本在同一个文件上量出来的，不是从本实现反推的。如果这里失败，
 * 说明实现有问题，不允许改期望值来迁就实现。
 */
class GunLodDecimatorTest {
    /** 与 {@link GunLodDecimator#DEFAULT_MIN_BONE_METRIC} 一致。 */
    private static final float THRESHOLD = GunLodDecimator.DEFAULT_MIN_BONE_METRIC;

    @Test
    void decimatesRealTaczGunModelByLongestEdge() throws Exception {
        GeoModel model = loadAk47();

        assertEquals(617, GunLodDecimator.cubeCount(model), "完整模型方块数");
        assertEquals(2455, GunLodDecimator.faceCount(model), "完整模型面数");

        GunLodDecimator.decimate(model, THRESHOLD, false);

        assertEquals(278, GunLodDecimator.cubeCount(model), "减面后方块数（折叠 + 整体取舍 + 面级剔除，不含标记骨骼）");
        assertEquals(1210, GunLodDecimator.faceCount(model), "减面后面数");

        // 轮廓件与可辨识特征必须整体存活（用独立 Python 脚本实测过）。
        // 注意 body_rail 是 m4a1 的骨骼，ak47 上没有，不要写进来。
        for (String bone : new String[]{"barrel4", "gastube", "ar_stock_adapter", "mount", "bolt",
                                        "grip2", "extd_mag", "extd_mag2", "extd_mag3",
                                        "cover", "group6", "group22", "wood", "oem_stock_heavy"}) {
            assertTrue(GunLodDecimator.boneHasCubes(model, bone), "可辨识特征骨骼必须存活: " + bone);
        }
        // 细节件整体消失（不是被削成残片）。
        for (String bone : new String[]{"muzzle_default", "trigger2", "iron_sight4"}) {
            assertFalse(GunLodDecimator.boneHasCubes(model, bone), "细节件应整体消失: " + bone);
        }
        assertTrue(GunLodDecimator.faceCount(model) * 3 <= 2443 * 2, "面数应至少降 30%");
    }

    /**
     * 正确性回归：TaCZ 用手部渲染器顶替绘制的定位标记骨骼绝不能进 LOD。
     *
     * <p>{@code lefthand_pos} / {@code righthand_pos} 是 3×12×4 的标记方块，最长边 12 远大于阈值，
     * 只按阈值过滤必然保留——离线渲染里它们表现为枪身上两块 0.75 格高的板子。
     * 真实枪械部件（{@code mount} 导轨等）必须照旧保留。
     */
    @Test
    void dropsBonesTaczNeverDrawsAsCubes() throws Exception {
        assertTrue(GunLodDecimator.isReplacedByFunctionalRenderer("lefthand_pos"));
        assertTrue(GunLodDecimator.isReplacedByFunctionalRenderer("righthand_pos"));
        assertTrue(GunLodDecimator.isReplacedByFunctionalRenderer("muzzle_flash"));
        assertTrue(GunLodDecimator.isReplacedByFunctionalRenderer("shell"));
        assertTrue(GunLodDecimator.isReplacedByFunctionalRenderer("scope_pos"), "_pos 后缀兜底");
        assertFalse(GunLodDecimator.isReplacedByFunctionalRenderer("mount"), "导轨是真实几何");
        assertFalse(GunLodDecimator.isReplacedByFunctionalRenderer("barrel4"));
        assertFalse(GunLodDecimator.isReplacedByFunctionalRenderer(null));

        GeoModel model = loadAk47();
        assertTrue(GunLodDecimator.boneHasCubes(model, "righthand_pos"), "减面前标记骨骼有方块");

        GunLodDecimator.decimate(model, THRESHOLD, false);

        assertFalse(GunLodDecimator.boneHasCubes(model, "righthand_pos"), "手部标记方块必须被丢弃");
        assertFalse(GunLodDecimator.boneHasCubes(model, "lefthand_pos"), "手部标记方块必须被丢弃");
        assertTrue(GunLodDecimator.boneHasCubes(model, "mount"), "导轨必须保留");
    }

    /**
     * 结构性回归：减面只能<b>整体</b>保留或丢弃一个骨骼的方块，绝不能留下半个零件。
     *
     * <p>这正是悬空碎片的成因：某个零件只剩 1~2 个方块时，它与整枪不再相连，轮廓会裂成多块。
     * 离线渲染实测过旧规则（逐方块过滤）会把轮廓裂成 3 块，其中一块是挂在枪身下方的孤立板子。
     */
    @Test
    void neverKeepsPartOfABone() throws Exception {
        for (float threshold : new float[]{1.0f, 2.0f, 3.0f, 4.0f, 6.0f}) {
            GeoModel model = loadAk47();
            var before = new java.util.HashMap<String, Integer>();
            for (var bone : model.minecraftGeometry.get(0).bones) {
                if (bone.cubes != null && !bone.cubes.isEmpty()) {
                    before.put(bone.name, bone.cubes.size());
                }
            }
            GunLodDecimator.decimateBonesOnly(model, threshold, false);
            for (var bone : model.minecraftGeometry.get(0).bones) {
                var original = before.get(bone.name);
                if (original == null) {
                    continue;
                }
                int now = bone.cubes == null ? 0 : bone.cubes.size();
                assertTrue(now == 0 || now == original,
                        "骨骼 " + bone.name + " 在阈值 " + threshold + " 下被削成了残片: "
                                + original + " -> " + now);
            }
        }
    }

    /** 阈值越高保留越少，且必须是单调的。 */
    @Test
    void thresholdIsMonotonic() throws Exception {
        var previous = Integer.MAX_VALUE;
        for (float threshold : new float[]{0.0f, 2.0f, 4.0f, 6.0f}) {
            GeoModel model = loadAk47();
            GunLodDecimator.decimateBonesOnly(model, threshold, false);
            var faces = GunLodDecimator.faceCount(model);
            assertTrue(faces <= previous, "阈值 " + threshold + " 的面数不应多于更低阈值");
            previous = faces;
        }
        GeoModel all = loadAk47();
        GunLodDecimator.decimateBonesOnly(all, 0.0f, false);
        // 阈值 0 保留全部真实几何；手部标记骨骼仍然被丢弃（12 面 = 两个 6 面的标记方块）。
        assertEquals(2443, GunLodDecimator.faceCount(all), "阈值 0 应保留全部非标记几何");
        assertEquals(615, GunLodDecimator.cubeCount(all), "阈值 0 应保留全部非标记方块");
    }

    private static GeoModel loadAk47() throws Exception {
        try (var in = GunLodDecimatorTest.class.getResourceAsStream("/tacz/ak47_geo.json")) {
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
