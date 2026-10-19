package com.kinetic.client.compat.tacz.lod;

import com.kinetic.format.parser.pojo.model.Bone;
import com.kinetic.format.parser.pojo.model.Cube;
import com.kinetic.format.parser.pojo.model.CubeUv;
import com.kinetic.format.parser.pojo.model.FaceUv;
import com.kinetic.format.parser.pojo.model.GeoModel;
import com.kinetic.format.parser.pojo.model.Geometry;
import com.kinetic.format.parser.pojo.model.UvFaces;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段 4（粗体素网格折叠）的定向回归。
 *
 * <p>核心不变量：
 * <ol>
 *   <li>发出的盒子一定在原骨骼包围盒内，并且装得下原骨骼的全部方块——不位移、不缩放、
 *       轮廓只会变大、绝不会丢可见像素。</li>
 *   <li>面数必须真的省下来（省不到 {@link GunLodDecimator#COLLAPSE_FACE_TARGET} 就放弃折叠）。</li>
 *   <li>截面均匀的直零件会被合并回 1 个盒子；收口/弯曲的零件留下多级台阶。</li>
 *   <li>真正非凸的零件（L 形、绕远枢轴甩出去的旋转方块）不折叠。</li>
 *   <li>供体缺面时补齐到 6 面（缺面会让模型从那一侧看是空的）。</li>
 * </ol>
 */
class GunLodCollapseTest {

    /** 未旋转方块的 trueAabb 必须退化成普通的 origin/size 盒子（X 按 KINETIC 约定取反）。 */
    @Test
    void trueAabbOfUnrotatedCubeIsThePlainBox() {
        var cube = cube(new float[]{-4, 2, -2}, new float[]{8, 4, 4});
        var box = GunLodDecimator.trueAabb(cube, 0.0f);
        // KINETIC 空间：x = -(origin.x + size.x) .. -origin.x
        assertEquals(-4.0f, box[0], 1e-5f, "lo.x");
        assertEquals(2.0f, box[1], 1e-5f, "lo.y");
        assertEquals(-2.0f, box[2], 1e-5f, "lo.z");
        assertEquals(4.0f, box[3], 1e-5f, "hi.x");
        assertEquals(6.0f, box[4], 1e-5f, "hi.y");
        assertEquals(2.0f, box[5], 1e-5f, "hi.z");
    }

    /** inflate 是均匀外扩，必须并进包围盒；骨骼级 inflate 只在方块没写时生效。 */
    @Test
    void trueAabbFoldsInflateIn() {
        var cube = cube(new float[]{0, 0, 0}, new float[]{2, 2, 2});
        cube.inflate = 1.0f;
        var box = GunLodDecimator.trueAabb(cube, 5.0f);
        // ox = -(0 + 2) = -2，x0 = -2 - 1 = -3，x1 = -2 + 2 + 1 = 1。
        assertEquals(-3.0f, box[0], 1e-5f, "方块自带 inflate 优先于骨骼级");
        assertEquals(1.0f, box[3], 1e-5f);

        var plain = cube(new float[]{0, 0, 0}, new float[]{2, 2, 2});
        var boneInflated = GunLodDecimator.trueAabb(plain, 5.0f);
        assertEquals(-7.0f, boneInflated[0], 1e-5f, "没写 inflate 时用骨骼级");
        assertEquals(5.0f, boneInflated[3], 1e-5f);
    }

    /** 旋转方块的紧包围盒必须精确等于它八个角的最小/最大值。 */
    @Test
    void trueAabbOfRotatedCubeIsTight() {
        var cube = cube(new float[]{0, 0, 0}, new float[]{2, 2, 2});
        cube.rotation = new float[]{0, 0, 90};
        cube.pivot = new float[]{0, 0, 0};
        var box = GunLodDecimator.trueAabb(cube, 0.0f);
        // KINETIC 角点 x ∈ [-2,0]、y ∈ [0,2]，绕原点 +90° 后 x' = -y ∈ [-2,0]、y' = x ∈ [-2,0]。
        assertEquals(-2.0f, box[0], 1e-4f);
        assertEquals(-2.0f, box[1], 1e-4f);
        assertEquals(0.0f, box[3], 1e-4f);
        assertEquals(0.0f, box[4], 1e-4f);
    }

    /** 截面均匀的直条：网格切出来的格子截面全同，合并后必须回到 1 个盒子。 */
    @Test
    void collapsesUniformBarToOneBox() {
        var bone = new Bone();
        bone.name = "bar";
        bone.cubes = new ArrayList<>();
        for (var i = 0; i < 5; i++) {
            bone.cubes.add(cube(new float[]{0, i * 2, 0}, new float[]{2, 2, 2}));
        }
        var boundsBefore = unionOf(bone);
        assertTrue(GunLodDecimator.collapseBone(bone), "均匀直条必须折叠");
        assertEquals(1, bone.cubes.size(), "均匀直条合并后应当只剩一个盒子");
        assertEquals(6, GunLodDecimator.faceCount(bone.cubes.get(0)), "盒子有六个面");
        assertBoundsUnchanged(boundsBefore, bone);
    }

    /** 收口/弯曲的零件必须留下多级台阶，而不是被填成一块砖。 */
    @Test
    void keepsStepsForTaperedProfile() {
        var bone = new Bone();
        bone.name = "taper";
        bone.cubes = new ArrayList<>();
        // 四级收口，每级 3 个方块（面数够多，才值得折叠）。
        float[][] sizes = {{4, 2, 4}, {3, 2, 3}, {2, 2, 2}, {1, 2, 1}};
        for (var level = 0; level < sizes.length; level++) {
            for (var k = 0; k < 3; k++) {
                bone.cubes.add(cube(new float[]{k * 0.1f, level * 2, 0}, sizes[level]));
            }
        }
        var boundsBefore = unionOf(bone);
        assertTrue(GunLodDecimator.collapseBone(bone), "收口零件必须折叠");
        assertTrue(bone.cubes.size() > 1,
                "收口零件必须留下台阶，实际只剩 " + bone.cubes.size() + " 个盒子");
        assertTrue(bone.cubes.size() < 12, "台阶数应远少于原方块数");
        assertBoundsUnchanged(boundsBefore, bone);
    }

    /** 每个发出的盒子都必须在原包围盒内，并且原方块的八个角都要被某个盒子盖住。 */
    @Test
    void emittedBoxesStayInsideBoundsAndCoverTheGeometry() {
        var bone = new Bone();
        bone.name = "cover";
        bone.cubes = new ArrayList<>();
        bone.cubes.add(cube(new float[]{0, 0, 0}, new float[]{6, 2, 2}));
        for (var i = 0; i < 6; i++) {
            bone.cubes.add(cube(new float[]{i, 2, 0.5f}, new float[]{0.5f, 1, 0.5f}));
        }
        var before = unionOf(bone);
        var cubeBoxes = new ArrayList<float[]>();
        for (var cube : bone.cubes) {
            cubeBoxes.add(GunLodDecimator.trueAabb(cube, 0.0f));
        }
        assertTrue(GunLodDecimator.collapseBone(bone));

        for (var box : bone.cubes) {
            var b = GunLodDecimator.trueAabb(box, 0.0f);
            for (var axis = 0; axis < 3; axis++) {
                assertTrue(b[axis] >= before[axis] - 1e-4f
                                && b[axis + 3] <= before[axis + 3] + 1e-4f,
                        "盒子超出了原包围盒");
            }
        }
        for (var cb : cubeBoxes) {
            for (var xi = 0; xi < 2; xi++) {
                for (var yi = 0; yi < 2; yi++) {
                    for (var zi = 0; zi < 2; zi++) {
                        var x = xi == 0 ? cb[0] : cb[3];
                        var y = yi == 0 ? cb[1] : cb[4];
                        var z = zi == 0 ? cb[2] : cb[5];
                        assertTrue(isCovered(bone, x, y, z),
                                "原方块的角 (" + x + "," + y + "," + z + ") 没有被任何盒子盖住");
                    }
                }
            }
        }
    }

    /** 真正非凸的 L 形零件不能折叠（包围盒体积远大于实际体积）。 */
    @Test
    void doesNotCollapseGenuinelyNonConvexCluster() {
        var bone = new Bone();
        bone.name = "L";
        bone.cubes = new ArrayList<>();
        bone.cubes.add(cube(new float[]{0, 0, 0}, new float[]{6, 0.5f, 0.5f}));
        bone.cubes.add(cube(new float[]{0, 0, 0}, new float[]{0.5f, 6, 0.5f}));
        var fill = GunLodDecimator.boneFill(bone, 0.0f);
        assertTrue(fill < GunLodDecimator.FILL_THRESHOLD,
                "L 形零件的实心度应低于阈值: " + fill);
        assertFalse(GunLodDecimator.collapseBone(bone), "非凸零件不得折叠");
        assertEquals(2, bone.cubes.size(), "原方块必须原样保留");
    }

    /**
     * 绕"很远的枢轴"旋转的方块会让真实包围盒暴涨，实心度随之掉下来——这种骨骼必须原样保留，
     * 否则盒子会把整片空白也填实。
     */
    @Test
    void doesNotCollapseBoneWithFarPivotRotatedCube() {
        var bone = new Bone();
        bone.name = "swing";
        bone.cubes = new ArrayList<>();
        bone.cubes.add(cube(new float[]{0, 0, 0}, new float[]{4, 4, 4}));
        var swung = cube(new float[]{10, 0, 0}, new float[]{4, 4, 4});
        swung.rotation = new float[]{0, 0, 90};
        swung.pivot = new float[]{0, 0, 0};
        bone.cubes.add(swung);

        var fill = GunLodDecimator.boneFill(bone, 0.0f);
        assertTrue(fill < GunLodDecimator.FILL_THRESHOLD, "甩出去的方块应拉低实心度: " + fill);
        assertFalse(GunLodDecimator.collapseBone(bone), "非凸骨骼不得折叠");
        assertEquals(2, bone.cubes.size());
    }

    /** 省不到面数目标时放弃折叠：只有两个单面方块时，网格会变多面，必须拒绝。 */
    @Test
    void refusesCollapseWhenFacesDoNotShrink() {
        var bone = new Bone();
        bone.name = "thin";
        bone.cubes = new ArrayList<>();
        bone.cubes.add(singleFaceCube(new float[]{0, 0, 0}, new float[]{2, 2, 2}));
        bone.cubes.add(singleFaceCube(new float[]{0, 2, 0}, new float[]{2, 2, 2}));
        var before = GunLodDecimator.faceCount(modelOf(bone));
        assertFalse(GunLodDecimator.collapseBone(bone), "省不到面数就不得折叠");
        assertEquals(before, GunLodDecimator.faceCount(modelOf(bone)), "面数不得增加");
    }

    /** 供体缺面时必须补齐：每个盒子六个面一个都不能少。 */
    @Test
    void collapseFillsMissingFacesFromTheDonor() {
        var bone = new Bone();
        bone.name = "partial";
        bone.cubes = new ArrayList<>();
        for (var i = 0; i < 4; i++) {
            bone.cubes.add(threeFaceCube(new float[]{0, i * 2, 0}, new float[]{4, 2, 4}));
        }
        assertTrue(GunLodDecimator.collapseBone(bone), "实心度高，必须折叠");
        assertTrue(bone.cubes.size() >= 1);
        for (var cube : bone.cubes) {
            assertEquals(6, GunLodDecimator.faceCount(cube), "缺的面必须用供体已有的面补齐");
        }
    }

    /**
     * 缺面补齐优先用<b>对面</b>的贴图：UV 的轴对应是 west/east: u=z,v=y、
     * north/south: u=x,v=y、up/down: u=x,v=z。用 west 去补 up 会把 patch 的两条轴整个配错，
     * 长边被压到短边上（离线实测 ak47 的最坏面拉伸因此从 15.6 降到 4.7 模型单位/texel）。
     * 对面也没有时退回"第一个非空面"的旧规则。
     */
    @Test
    void fillsMissingFaceFromTheOppositeFaceWhenAvailable() {
        var bone = new Bone();
        bone.name = "opposite";
        bone.cubes = new ArrayList<>();
        var west = face();
        var east = face();
        var down = face();
        for (var i = 0; i < 4; i++) {
            var c = new Cube();
            c.origin = new float[]{0, i * 2, 0};
            c.size = new float[]{4, 2, 4};
            var faces = new UvFaces();
            faces.west = west;
            faces.east = east;
            faces.down = down;                 // 只有 west/east/down 三个面
            c.uv = CubeUv.perFace(faces);
            bone.cubes.add(c);
        }
        assertTrue(GunLodDecimator.collapseBone(bone), "实心直条必须折叠");
        assertTrue(bone.cubes.size() >= 1);
        for (var cube : bone.cubes) {
            var faces = cube.uv.perFaceUv;
            assertEquals(6, GunLodDecimator.faceCount(cube), "六个面都要补齐");
            assertSame(down, faces.up, "up 缺面时用对面 down 的贴图");
            assertSame(west, faces.north, "north/south 都没有时退回第一个非空面 west");
            assertSame(west, faces.south);
        }
    }

    /** 盒式 UV 的供体折叠后仍然是 6 面。 */
    @Test
    void collapseKeepsBoxUvAtSixFaces() {        var bone = new Bone();
        bone.name = "boxuv";
        bone.cubes = new ArrayList<>();
        for (var i = 0; i < 3; i++) {
            var c = cube(new float[]{0, i * 2, 0}, new float[]{2, 2, 2});
            c.uv = CubeUv.box(new float[]{0, 0});
            bone.cubes.add(c);
        }
        assertTrue(GunLodDecimator.collapseBone(bone));
        assertTrue(bone.cubes.size() >= 1);
        for (var cube : bone.cubes) {
            assertEquals(6, GunLodDecimator.faceCount(cube));
            assertNotNull(cube.uv.boxUv, "盒式 UV 原样保留");
        }
    }

    /**
     * 只有无面方块的格子绝不能发出隐形盒子。
     *
     * <p>无面方块（逐面 UV 对象里六个面全缺）在 {@code GeoBuilder} 里生成 0 个四边形；
     * 若把它选成 UV 供体，{@code collapseUv} 会原样返回"0 面"的 UV，盒子一个四边形都不画——
     * 轮廓上出现一个洞。正确的做法是这种格子干脆不发盒子（那块几何在原模型里本来就不画）。
     *
     * <p>夹具：左侧 4×4×4 的实心块（8 个 2×2×2，各 6 面，共 48 面）负责通过实心度与面数目标；
     * 右侧贴一个 2×2×2 的<b>无面</b>方块，它单独占住网格最左一列的 4 个格子（KINETIC x ∈ [-6,-4.8]）。
     */
    @Test
    void neverEmitsInvisibleBoxForCellWithOnlyFacelessCubes() {
        var bone = new Bone();
        bone.name = "faceless";
        bone.cubes = new ArrayList<>();
        for (var x = 0; x < 2; x++) {
            for (var y = 0; y < 2; y++) {
                for (var z = 0; z < 2; z++) {
                    bone.cubes.add(cube(new float[]{x * 2, y * 2, z * 2}, new float[]{2, 2, 2}));
                }
            }
        }
        var faceless = cube(new float[]{4, 0, 0}, new float[]{2, 2, 2});
        faceless.uv = CubeUv.perFace(new UvFaces());      // 逐面对象，但六个面全缺
        bone.cubes.add(faceless);

        assertEquals(0, GunLodDecimator.faceCount(faceless), "前提：这个方块一个面都不画");
        var fill = GunLodDecimator.boneFill(bone, 0.0f);
        assertTrue(fill >= GunLodDecimator.FILL_THRESHOLD, "前提：实心度要够折叠: " + fill);
        assertTrue(GunLodDecimator.collapseBone(bone), "这块骨骼应当折叠");

        assertTrue(bone.cubes.size() >= 1, "折叠后应当还有盒子");
        for (var emitted : bone.cubes) {
            assertTrue(GunLodDecimator.faceCount(emitted) > 0,
                    "发出的盒子必须有面：无面供体 = 隐形盒子 = 轮廓上的洞");
        }
        // 无面方块单独占住的格子（KINETIC x ∈ [-6,-4.8]）在原模型里什么都不画，
        // 因此也不该被任何盒子覆盖：所有盒子的 lo.x 都不能越过 -4.8。
        for (var emitted : bone.cubes) {
            var box = GunLodDecimator.trueAabb(emitted, 0.0f);
            assertTrue(box[0] >= -4.8f - 1e-4f,
                    "只有无面方块的格子不得发出盒子，实际 lo.x=" + box[0]);
        }
    }

    /** 同一个格子里只要有有面方块，就仍然优先用它当供体（无面方块体积更大也不行）。 */
    @Test
    void prefersFaceBearingDonorEvenWhenFacelessCubeIsLarger() {
        var bone = new Bone();
        bone.name = "mixed";
        bone.cubes = new ArrayList<>();
        // 有面的实心块：8 个 2×2×2，共 48 面。
        for (var x = 0; x < 2; x++) {
            for (var y = 0; y < 2; y++) {
                for (var z = 0; z < 2; z++) {
                    bone.cubes.add(cube(new float[]{x * 2, y * 2, z * 2}, new float[]{2, 2, 2}));
                }
            }
        }
        // 无面的大方块：完全罩住实心块，体积是任意单块的 8 倍。
        var faceless = cube(new float[]{0, 0, 0}, new float[]{4, 4, 4});
        faceless.uv = CubeUv.perFace(new UvFaces());
        bone.cubes.add(faceless);

        assertTrue(GunLodDecimator.collapseBone(bone));
        assertTrue(bone.cubes.size() >= 1);
        for (var emitted : bone.cubes) {
            assertTrue(GunLodDecimator.faceCount(emitted) > 0,
                    "供体必须是有面方块，哪怕无面方块更大");
        }
    }

    /** 单个方块的骨骼不做折叠（等价变换，没有必要，还会丢掉 pivot/mirror）。 */
    @Test
    void singleCubeBoneIsLeftAlone() {
        var bone = new Bone();
        bone.name = "single";
        bone.cubes = new ArrayList<>();
        bone.cubes.add(cube(new float[]{0, 0, 0}, new float[]{4, 4, 4}));
        assertFalse(GunLodDecimator.collapseBone(bone));
        assertEquals(1, bone.cubes.size());
    }

    /** 整个模型入口：返回被折叠的骨骼数，骨骼层级不变。 */
    @Test
    void collapseBoxLikeBonesCountsAndKeepsHierarchy() {
        var solid = new Bone();
        solid.name = "solid";
        solid.cubes = new ArrayList<>(List.of(
                cube(new float[]{0, 0, 0}, new float[]{4, 4, 4}),
                cube(new float[]{0, 4, 0}, new float[]{4, 4, 4})));
        var hollow = new Bone();
        hollow.name = "hollow";
        hollow.cubes = new ArrayList<>(List.of(
                cube(new float[]{0, 0, 0}, new float[]{6, 0.5f, 0.5f}),
                cube(new float[]{0, 0, 0}, new float[]{0.5f, 6, 0.5f})));
        var model = modelOf(solid, hollow);

        assertEquals(1, GunLodDecimator.collapseBoxLikeBones(model), "只有实心那根会折叠");
        assertEquals(2, model.minecraftGeometry.get(0).bones.size(), "骨骼层级不变");
        assertTrue(solid.cubes.size() >= 1);
        assertEquals(2, hollow.cubes.size());
    }

    /** 阶梯的第一档就是文档里写的 5×3。 */
    @Test
    void gridLadderStartsAtFiveByThree() {
        assertEquals(5, GunLodDecimator.GRID_LADDER[0][0]);
        assertEquals(3, GunLodDecimator.GRID_LADDER[0][1]);
        assertEquals(GunLodDecimator.GRID_LONG, GunLodDecimator.GRID_LADDER[0][0]);
        assertEquals(GunLodDecimator.GRID_SHORT, GunLodDecimator.GRID_LADDER[0][1]);
        assertTrue(GunLodDecimator.COLLAPSE_FACE_TARGET > 0.0f
                && GunLodDecimator.COLLAPSE_FACE_TARGET <= 1.0f);
    }

    // ---------------------------------------------------------------- 夹具

    private static boolean isCovered(Bone bone, float x, float y, float z) {
        for (var cube : bone.cubes) {
            var b = GunLodDecimator.trueAabb(cube, 0.0f);
            if (x >= b[0] - 1e-4f && x <= b[3] + 1e-4f
                    && y >= b[1] - 1e-4f && y <= b[4] + 1e-4f
                    && z >= b[2] - 1e-4f && z <= b[5] + 1e-4f) {
                return true;
            }
        }
        return false;
    }

    private static float[] unionOf(Bone bone) {
        var lo = new float[]{Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        var hi = new float[]{-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (var cube : bone.cubes) {
            var box = GunLodDecimator.trueAabb(cube, bone.inflate);
            for (var axis = 0; axis < 3; axis++) {
                lo[axis] = Math.min(lo[axis], box[axis]);
                hi[axis] = Math.max(hi[axis], box[axis + 3]);
            }
        }
        return new float[]{lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]};
    }

    private static void assertBoundsUnchanged(float[] before, Bone bone) {
        assertTrue(bone.cubes.size() >= 1, "折叠后应当还有方块");
        var after = unionOf(bone);
        for (var axis = 0; axis < 6; axis++) {
            assertEquals(before[axis], after[axis], 1e-4f,
                    "折叠不得改变包围盒（分量 " + axis + "）");
        }
    }


    /**
     * 一个<b>真的会画出来</b>的逐面条目：真实基岩数据里 {@code uv} 与 {@code uv_size} 都在，
     * 而 {@code GeoBuilder.addFace} 缺任何一个就直接跳过。用空条目当测试数据会让"这条面到底
     * 会不会被画"与真实模型不一致。
     */
    private static FaceUv face() {
        var face = new FaceUv();
        face.uv = new float[]{0, 0};
        face.uvSize = new float[]{1, 1};
        return face;
    }

    private static Cube cube(float[] origin, float[] size) {
        var cube = new Cube();
        cube.origin = origin;
        cube.size = size;
        var faces = new UvFaces();
        faces.west = face();
        faces.east = face();
        faces.north = face();
        faces.south = face();
        faces.up = face();
        faces.down = face();
        cube.uv = CubeUv.perFace(faces);
        return cube;
    }

    private static Cube singleFaceCube(float[] origin, float[] size) {
        var cube = new Cube();
        cube.origin = origin;
        cube.size = size;
        var faces = new UvFaces();
        faces.west = face();
        cube.uv = CubeUv.perFace(faces);
        return cube;
    }

    private static Cube threeFaceCube(float[] origin, float[] size) {
        var cube = new Cube();
        cube.origin = origin;
        cube.size = size;
        var faces = new UvFaces();
        faces.west = face();
        faces.east = face();
        faces.up = face();
        cube.uv = CubeUv.perFace(faces);
        return cube;
    }

    private static GeoModel modelOf(Bone... bones) {
        var geometry = new Geometry();
        geometry.bones = new ArrayList<>(List.of(bones));
        var model = new GeoModel();
        model.minecraftGeometry = new ArrayList<>(List.of(geometry));
        return model;
    }
}
