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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一轮全面审计里发现的四个几何缺陷的定向回归。它们的共同点是"只有特定几何构造才暴露"，
 * 真实枪包夹具恰好都不触发，所以必须在这里用合成几何钉住。
 */
class GunLodAuditRegressionTest {

    /**
     * <b>镜像布局的覆盖方向</b>：{@code GeoBuilder} 在镜像布局里把 west/east 与 up/down 再对调一次，
     * 覆盖判定必须跟着换一张表。用同一张表会把镜像方块的上下两个外面删掉、把内面留下。
     */
    @Test
    void mirroredCubesUseTheMirroredFaceMapping() {
        var bone = new Bone();
        bone.name = "mirrored";
        bone.cubes = new ArrayList<>();
        var a = cube(new float[]{0, 0, 0}, new float[]{2, 2, 2});
        var b = cube(new float[]{0, 2, 0}, new float[]{2, 2, 2});
        a.mirror = true;
        b.mirror = true;
        bone.cubes.add(a);
        bone.cubes.add(b);

        GunLodDecimator.cullAllFaces(modelOf(bone));

        assertEquals(2, bone.cubes.size(), "两个方块都必须保留");
        // 镜像布局下 up 画在 JSON 最小 y、down 画在最大 y：共享面在 y=2，所以被删的是 A.down 与 B.up。
        assertNull(faceOf(a, "down"), "A 朝 B 的那一面（镜像下是 down 条目）必须被剔掉");
        assertNull(faceOf(b, "up"), "B 朝 A 的那一面（镜像下是 up 条目）必须被剔掉");
        assertNotNull(faceOf(a, "up"), "A 的外侧下面必须保留");
        assertNotNull(faceOf(b, "down"), "B 的外侧上面必须保留");
    }

    /**
     * <b>inflate 是有符号的</b>：负值内缩。取绝对值会把一个内缩的邻居当成外扩的，
     * 于是隔着一条缝也会把别人的面删掉。
     */
    @Test
    void negativeInflateShrinksTheCubeInsteadOfGrowingIt() {
        var bone = new Bone();
        bone.name = "inset";
        bone.cubes = new ArrayList<>();
        var a = cube(new float[]{0, 0, 0}, new float[]{2, 2, 2});
        // B 的声明盒子是 [2,6]，内缩 1 之后真实占 [3,5]：与 A 之间有一条 1 单位的缝。
        var b = cube(new float[]{2, 0, 0}, new float[]{4, 4, 4});
        b.inflate = -1.0f;
        bone.cubes.add(a);
        bone.cubes.add(b);

        GunLodDecimator.cullAllFaces(modelOf(bone));

        assertEquals(2, bone.cubes.size());
        assertNotNull(faceOf(a, "west"), "隔着一条缝，A 的外侧 X 面必须保留");
        assertNotNull(faceOf(a, "east"), "A 的内侧 X 面同样没有被贴住");
    }

    /**
     * <b>空的面条目不等于有面</b>：{@code GeoBuilder.addFace} 在 {@code uv} 或 {@code uv_size}
     * 缺失时直接跳过。把这种方块当成"有面的供体"，整根骨骼会折叠成一个隐形盒子（面数掉到 0），
     * 而不是"没贴图"。
     */
    @Test
    void anEmptyFaceEntryIsNotAFace() {
        var empty = new Cube();
        empty.origin = new float[]{0, 0, 0};
        empty.size = new float[]{4, 4, 4};
        var faces = new UvFaces();
        faces.west = new FaceUv();          // 有条目，但没有 uv / uv_size
        empty.uv = CubeUv.perFace(faces);

        assertEquals(0, GunLodDecimator.faceCount(empty), "空条目在 GeoBuilder 里生成 0 个四边形");
    }

    @Test
    void aBoneWhoseOnlyFacesAreEmptyNeverCollapsesIntoAnInvisibleBox() {
        var bone = new Bone();
        bone.name = "emptyFaces";
        bone.cubes = new ArrayList<>();
        // 一个空条目的巨块 + 若干真的有面的小方块。
        var empty = new Cube();
        empty.origin = new float[]{0, 0, 0};
        empty.size = new float[]{8, 8, 8};
        var emptyFaces = new UvFaces();
        emptyFaces.west = new FaceUv();
        empty.uv = CubeUv.perFace(emptyFaces);
        bone.cubes.add(empty);
        for (var i = 0; i < 8; i++) {
            bone.cubes.add(cube(new float[]{i, 0, 0}, new float[]{1, 1, 1}));
        }

        GunLodDecimator.collapseBone(bone);

        assertFalse(bone.cubes.isEmpty(), "折叠后不能什么都不剩");
        for (var cube : bone.cubes) {
            assertEquals(6, GunLodDecimator.faceCount(cube),
                    "每个折叠盒子都必须有 6 个真的会画出来的面；否则就是隐形盒子 = 轮廓上的洞");
        }
    }

    /**
     * <b>只减面会被渲染的那份几何</b>：{@code GeoBuilder} 只取第一个 geometry，其余根本不会被画。
     * 把幽灵几何也算进体量分位，会把自适应阈值抬高到把真正要画的骨骼整根删掉，LOD 变成空模型。
     */
    @Test
    void ghostGeometriesDoNotRaiseTheAdaptiveThreshold() {
        var real = new Bone();
        real.name = "real";
        real.cubes = new ArrayList<>();
        real.cubes.add(cube(new float[]{0, 0, 0}, new float[]{4, 4, 4}));

        var ghost = new Bone();
        ghost.name = "ghost";
        ghost.cubes = new ArrayList<>();
        ghost.cubes.add(cube(new float[]{0, 0, 0}, new float[]{40, 40, 40}));

        var first = new Geometry();
        first.bones = new ArrayList<>(List.of(real));
        var second = new Geometry();
        second.bones = new ArrayList<>(List.of(ghost));
        var model = new GeoModel();
        model.minecraftGeometry = new ArrayList<>(List.of(first, second));

        GunLodDecimator.decimate(model, 3.0f, false);

        assertTrue(GunLodDecimator.boneHasCubes(model, "real"),
                "真正会被渲染的那根骨骼不能被幽灵几何的体量挤掉");
    }

    // ---------------------------------------------------------------- helpers

    /** 一个真的会画出来的逐面条目（真实基岩数据里 uv 与 uv_size 都在）。 */
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

    private static FaceUv faceOf(Cube cube, String name) {
        var faces = cube.uv.perFaceUv;
        return switch (name) {
            case "west" -> faces.west;
            case "east" -> faces.east;
            case "north" -> faces.north;
            case "south" -> faces.south;
            case "up" -> faces.up;
            default -> faces.down;
        };
    }

    private static GeoModel modelOf(Bone bone) {
        var geometry = new Geometry();
        geometry.bones = new ArrayList<>(List.of(bone));
        var model = new GeoModel();
        model.minecraftGeometry = new ArrayList<>(List.of(geometry));
        return model;
    }
}
