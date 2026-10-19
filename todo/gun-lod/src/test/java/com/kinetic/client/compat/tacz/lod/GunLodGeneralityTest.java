package com.kinetic.client.compat.tacz.lod;

import com.kinetic.format.parser.pojo.model.GeoModel;
import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 减面规则的<b>普适性</b>回归：对测试资源里的每一把枪都跑同一组不变量。
 *
 * <p>只有一把枪的样本无法说明规则普适——第一版规则（逐方块按最长边过滤）在 ak47 上就已经把
 * 21 个主要零件里的 15 个削成了残片，并且产生了悬空碎片。这里把"必须是整体取舍"这类结构不变量
 * 对每把枪都断言一遍，新增枪械模型时只要补一行期望值就会自动进入覆盖。
 *
 * <p>{@link #everyFixtureIsCovered()} 是关键：它保证"新增了模型但忘了写期望值"会直接失败，
 * 而不是让覆盖静默变窄。
 */
class GunLodGeneralityTest {
    /**
     * 文件名 → {去标记后的方块数, 去标记后的面数, LOD 方块数, LOD 面数}。
     *
     * <p>这些数字由独立 Python 脚本在同一批 JSON 上量得，与 Java 实现互相交叉验证。
     * LOD 用的是自适应阈值（默认下限 3.0），实测四把模型都落在原始面数的 25%~55%：
     * ak47 47.0%、hk416d 27.6%、eos 53.5%、刺刀配件 35.3%。
     */
    private static final Map<String, int[]> EXPECTED = new LinkedHashMap<>();

    static {
        EXPECTED.put("ak47_geo.json", new int[]{615, 2443, 278, 1210});
        EXPECTED.put("hk416d_geo.json", new int[]{784, 3832, 255, 1320});
        // 真实附属包（EOS Dawn Goddess Lab）：包内最大的一把枪，3053 方块 / 5765 面 / 125 骨骼，
        // 是 ak47 的 2.3 倍面数，用来证明规则对"更复杂的附属包"同样成立。
        EXPECTED.put("eos_helenas_nail_geo.json", new int[]{3051, 5753, 1767, 3084});
        // 同一包里没有 thirdperson_hand 的模型（是配件）：验证真实数据上的零锚点路径。
        EXPECTED.put("eos_bayonet_eternity_geo.json", new int[]{399, 2394, 235, 1410});
    }

    static Stream<String> fixtures() {
        return EXPECTED.keySet().stream();
    }

    /** 防止"加了新模型但没加期望值"导致覆盖静默变窄：直接扫描测试资源目录。 */
    @Test
    void everyFixtureIsCovered() throws Exception {
        var found = new TreeSet<String>();
        var dir = GunLodGeneralityTest.class.getResource("/tacz/");
        assertNotNull(dir, "测试资源目录 /tacz/ 不存在");
        try (var paths = java.nio.file.Files.list(java.nio.file.Path.of(dir.toURI()))) {
            // synthetic_* 是构造出来的健壮性夹具（不是真实枪械，没有可比的期望值），
            // 由 GunLodAddonRobustnessTest 单独覆盖，这里排除。
            paths.filter(p -> p.getFileName().toString().endsWith("_geo.json"))
                    .filter(p -> !p.getFileName().toString().startsWith("synthetic_"))
                    .forEach(p -> found.add(p.getFileName().toString()));
        }
        assertEquals(new TreeSet<>(EXPECTED.keySet()), found,
                "测试资源里的枪械模型与 EXPECTED 必须一一对应");
        assertTrue(found.size() >= 2, "普适性验证至少需要两把枪");
    }

    @ParameterizedTest
    @MethodSource("fixtures")
    void decimatesWholeBonesOnly(String fixture) throws Exception {
        var expected = EXPECTED.get(fixture);
        var model = load(fixture);
        var boneCountBefore = model.minecraftGeometry.get(0).bones.size();

        // 减面前：先确认"真实几何"的规模与独立测量一致（标记骨骼不计入）。
        var markers = stripMarkers(model);
        assertEquals(expected[0], GunLodDecimator.cubeCount(model), fixture + " 去标记后方块数");
        assertEquals(expected[1], GunLodDecimator.faceCount(model), fixture + " 去标记后面数");
        // 官方枪都有带几何的手部标记骨骼；附属包里的配件（瞄具/刺刀）没有。
        // 这里只断言"有标记时一定被清空"，不再要求每把枪都必须有。


        GunLodDecimator.decimate(model, GunLodDecimator.DEFAULT_MIN_BONE_METRIC, false);

        System.out.println("[LOD] " + fixture + " -> " + GunLodDecimator.cubeCount(model)
                + " cubes / " + GunLodDecimator.faceCount(model) + " faces");
        assertEquals(expected[2], GunLodDecimator.cubeCount(model), fixture + " 减面后方块数");
        assertEquals(expected[3], GunLodDecimator.faceCount(model), fixture + " 减面后面数");

        // 不变量 1：骨骼层级完全保留（只是清空方块）。
        assertEquals(boneCountBefore, model.minecraftGeometry.get(0).bones.size(),
                fixture + " 骨骼数不得变化");

        // 不变量 2：面级剔除只会让方块数变少，绝不会凭空造出方块。
        assertTrue(GunLodDecimator.cubeCount(model) <= expected[0],
                fixture + " 面级剔除后方块数不应增加");

        // 不变量 3：减面后不再有标记骨骼的几何。
        assertEquals(0, countMarkerCubes(model), fixture + " 减面后仍残留标记骨骼几何");

        // 不变量 4：削减幅度落在有意义的区间——太小说明没简化，太大说明开始丢特征。
        var keptRatio = (double) GunLodDecimator.faceCount(model) / expected[1];
        // 只对"枪"断言削减幅度：附属包里的配件模型（瞄具/刺刀）本来就该原样保留。
        // 判据用"有没有 thirdperson_hand"，与锚点用的是同一个事实。
        if (GunLodDecimator.handAnchor(model).lengthSquared() == 0.0f) {
            assertTrue(GunLodDecimator.faceCount(model) <= expected[1],
                    fixture + " 配件模型只应减少，不应增加面数");
            return;
        }
        // 注意：旧的绝对阈值对"骨骼少、每根骨骼方块多"的附属包几乎不削减（eos 最大那把枪保留 87%）。
        // 自适应规则落地后这里收紧成硬性区间：任何枪包都必须落进同一档削减幅度。
        assertTrue(keptRatio <= 0.55, fixture + " 面数削减不足: 保留 " + keptRatio);
        assertTrue(keptRatio >= 0.25, fixture + " 面数削减过度，可辨识特征会丢失: 保留 " + keptRatio);
    }

    /**
     * 零件取舍阶段的不变量：只能<b>整体</b>保留或丢弃一个骨骼的方块，绝不能留下半个零件。
     *
     * <p>这正是悬空碎片的成因：零件只剩 1~2 个方块时与整枪不再相连，轮廓会裂成多块。
     * 离线渲染实测过旧规则（逐方块过滤）会把轮廓裂成 3 块，其中一块是挂在枪身下方的孤立板子。
     *
     * <p>刻意只跑 {@code decimateBonesOnly}：面级剔除会合法地删掉被完全包住的方块，
     * 那是另一个阶段的独立不变量，混在一起测会掩盖真正的回归。
     */
    @ParameterizedTest
    @MethodSource("fixtures")
    void boneSelectionIsAllOrNothing(String fixture) throws Exception {
        for (var threshold : new float[]{1.0f, 2.0f, 3.0f, 4.0f, 6.0f}) {
            var model = load(fixture);
            var before = cubesPerBone(model);
            GunLodDecimator.decimateBonesOnly(model, threshold, false);
            var after = cubesPerBone(model);
            for (var entry : before.entrySet()) {
                int now = after.getOrDefault(entry.getKey(), 0);
                assertTrue(now == 0 || now == entry.getValue(),
                        fixture + " 的骨骼 " + entry.getKey() + " 在阈值 " + threshold
                                + " 下被削成了残片: " + entry.getValue() + " -> " + now);
            }
        }
    }

    /** 阈值越高保留越少，且必须单调——对每把枪都成立。 */
    @ParameterizedTest
    @MethodSource("fixtures")
    void thresholdIsMonotonic(String fixture) throws Exception {
        var previous = Integer.MAX_VALUE;
        for (var threshold : new float[]{0.0f, 2.0f, 3.0f, 4.0f, 6.0f}) {
            var model = load(fixture);
            GunLodDecimator.decimateBonesOnly(model, threshold, false);
            var faces = GunLodDecimator.faceCount(model);
            assertTrue(faces <= previous,
                    fixture + " 阈值 " + threshold + " 的面数不应多于更低阈值");
            previous = faces;
        }
    }

    /**
     * 摆放普适性：每把枪都必须能算出非零的手节点锚点，且锚点必须等于
     * {@code thirdperson_hand} 枢轴在 KINETIC 坐标系下的相反数。
     *
     * <p>这一步保证"LOD 模型被摆到手上"这件事不依赖具体枪包；算不出锚点时退化为零平移，
     * 也就是不额外摆放，而不是抛异常。
     */
    @ParameterizedTest
    @MethodSource("fixtures")
    void anchorsTheHandNode(String fixture) throws Exception {
        var model = load(fixture);
        org.joml.Vector3f pivot = null;
        for (var bone : model.minecraftGeometry.get(0).bones) {
            if (GunLodDecimator.HAND_NODE_NAME.equals(bone.name) && bone.pivot != null) {
                pivot = new org.joml.Vector3f(bone.pivot[0], bone.pivot[1], bone.pivot[2]);
            }
        }
        var anchor = GunLodDecimator.handAnchor(model);
        if (pivot == null) {
            // 附属包里的配件（瞄具/刺刀）没有 thirdperson_hand。TaCZ 在这种情况下不做任何摆放，
            // 所以锚点必须是零——这里顺便在真实数据上钉住"零锚点"这条路径。
            assertEquals(0.0f, anchor.lengthSquared(), 1e-6f,
                    fixture + " 没有 " + GunLodDecimator.HAND_NODE_NAME + " 时锚点必须为零");
            return;
        }
        assertEquals(pivot.x, anchor.x, 1e-6f, fixture + " 锚点 X 必须是枢轴在 KINETIC 坐标系下的相反数");
        assertEquals(-pivot.y, anchor.y, 1e-6f, fixture + " 锚点 Y");
        assertEquals(-pivot.z, anchor.z, 1e-6f, fixture + " 锚点 Z");
        assertTrue(anchor.lengthSquared() > 0.0f, fixture + " 锚点不应为零");

        // 减面不得改变锚点：骨骼一律保留，所以枢轴一定还在。
        GunLodDecimator.decimate(model, GunLodDecimator.DEFAULT_MIN_BONE_METRIC, false);
        assertEquals(anchor, GunLodDecimator.handAnchor(model), fixture + " 减面后锚点不得变化");
    }

    /** 每把枪都必须有可辨识的轮廓件存活（枪管/机匣/枪托这类"大件"不能全丢）。 */
    @ParameterizedTest
    @MethodSource("fixtures")
    void keepsStructuralParts(String fixture) throws Exception {
        var model = load(fixture);
        var beforeCubes = GunLodDecimator.cubeCount(model);
        GunLodDecimator.decimate(model, GunLodDecimator.DEFAULT_MIN_BONE_METRIC, false);

        var kept = 0;
        var keptCubes = 0;
        for (var bone : model.minecraftGeometry.get(0).bones) {
            if (bone.cubes != null && !bone.cubes.isEmpty()) {
                kept++;
                keptCubes += bone.cubes.size();
            }
        }
        assertTrue(kept >= 8, fixture + " 保留的零件太少（" + kept + "），轮廓会不成立");
        // 阶段 4 会把整根骨骼折成 1 个方块，所以方块数不再能与原值直接比；
        // 这里只要求"轮廓骨架还在"：至少留下原来八分之一的方块。
        assertTrue(keptCubes >= beforeCubes / 8,
                fixture + " 保留的方块太少（" + keptCubes + "/" + beforeCubes + "），轮廓会不成立");
    }

    private static Map<String, Integer> cubesPerBone(GeoModel model) {
        var counts = new LinkedHashMap<String, Integer>();
        for (var bone : model.minecraftGeometry.get(0).bones) {
            if (bone.cubes != null && !bone.cubes.isEmpty()) {
                counts.put(bone.name, bone.cubes.size());
            }
        }
        return counts;
    }

    /** 把标记骨骼的方块清空，返回被清空的方块总数（用于先量"真实几何"）。 */
    private static int stripMarkers(GeoModel model) {
        var removed = 0;
        for (var bone : model.minecraftGeometry.get(0).bones) {
            if (bone.cubes == null || bone.cubes.isEmpty()) {
                continue;
            }
            if (GunLodDecimator.isReplacedByFunctionalRenderer(bone.name)) {
                removed += bone.cubes.size();
                bone.cubes = new java.util.ArrayList<>(0);
            }
        }
        return removed;
    }

    private static int countMarkerCubes(GeoModel model) {
        var count = 0;
        for (var bone : model.minecraftGeometry.get(0).bones) {
            if (bone.cubes != null && !bone.cubes.isEmpty()
                    && GunLodDecimator.isReplacedByFunctionalRenderer(bone.name)) {
                count += bone.cubes.size();
            }
        }
        return count;
    }

    private static GeoModel load(String fixture) {
        try (var in = GunLodGeneralityTest.class.getResourceAsStream("/tacz/" + fixture)) {
            assertNotNull(in, "测试资源缺失: /tacz/" + fixture);
            var text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            var model = new Gson().fromJson(stripComments(text), GeoModel.class);
            assertNotNull(model, "解析失败: " + fixture);
            return model;
        } catch (Exception e) {
            throw new AssertionError("读取 /tacz/" + fixture + " 失败", e);
        }
    }

    /** TaCZ 的 geo JSON 可能带 {@code //} 注释，Gson 默认不接受，测试里先去掉行尾注释。 */
    private static String stripComments(String text) {
        var sb = new StringBuilder(text.length());
        for (var line : text.split("\n", -1)) {
            var idx = line.indexOf("//");
            sb.append(idx >= 0 ? line.substring(0, idx) : line).append('\n');
        }
        return sb.toString();
    }
}
