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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 面级遮挡剔除的定向回归。
 *
 * <p>这一层出过三个只有几何构造才能发现的缺陷，全部在这里钉住：
 * <ol>
 *   <li><b>轴向错位</b>：方向编号 {@code 2/3} 是 ±Z、{@code 4/5} 是 ±Y，早期用 {@code dir >> 1}
 *       把它们分别当成 Y 轴与 Z 轴，导致六个面里有四个用错轴判定。</li>
 *   <li><b>贴面相邻剔不掉</b>：早期要求覆盖者"跨越"面所在平面，而面贴面相邻的两个方块谁都不跨越，
 *       于是最常见的情况一个面都剔不掉。</li>
 *   <li><b>旋转方块误删可见面</b>：旋转过的方块，{@code origin/size} 包围盒不再等于实际绘制的盒子。
 *       离线渲染实测过：拿它判定覆盖会在 ak47 上误删 50 px、hk416d 上误删 82 px 的<b>可见</b>表面。</li>
 * </ol>
 */
class GunLodFaceCullingTest {

    /** 贴面相邻：共享的那个面必须被剔掉，其余十个面必须保留。 */
    @Test
    void cullsFacesOfFlushNeighboursOnEveryAxis() {
        // 三对贴面相邻的方块，分别沿 X / Y / Z 相接，覆盖全部三个轴、六个方向。
        // X 轴那一对是反的：GeoBuilder 解析时取反 X，并把 west/east 的贴图对调以保持镜像后的
        // 贴图正确，所以沿 +X 相接时被删掉的是 A 的 west 条目与 B 的 east 条目。
        assertFlushPairCulled(new float[]{2, 0, 0}, "west", "east");
        assertFlushPairCulled(new float[]{0, 2, 0}, "up", "down");
        assertFlushPairCulled(new float[]{0, 0, 2}, "south", "north");
    }

    private static void assertFlushPairCulled(float[] offset, String positiveFace, String negativeFace) {
        var bone = new Bone();
        bone.name = "pair";
        bone.cubes = new ArrayList<>();
        var a = cube(new float[]{0, 0, 0}, new float[]{2, 2, 2});
        var b = cube(offset, new float[]{2, 2, 2});
        bone.cubes.add(a);
        bone.cubes.add(b);

        GunLodDecimator.cullAllFaces(modelOf(bone));

        assertEquals(2, bone.cubes.size(), "两个方块都不应被整块删除");
        assertNull(faceOf(a, positiveFace), "A 朝 B 的那一面必须被剔掉: " + positiveFace);
        assertNull(faceOf(b, negativeFace), "B 朝 A 的那一面必须被剔掉: " + negativeFace);
        // 其余面必须原样保留。
        for (var name : new String[]{"west", "east", "north", "south", "up", "down"}) {
            if (name.equals(positiveFace) || name.equals(negativeFace)) {
                continue;
            }
            assertNotNull(faceOf(a, name), "A 的 " + name + " 面是暴露的，不得剔除");
            assertNotNull(faceOf(b, name), "B 的 " + name + " 面是暴露的，不得剔除");
        }
    }

    /** 旋转过的方块既不能当被剔除方，也不能当覆盖方。 */
    @Test
    void rotatedCubesNeverParticipateInFaceCulling() {
        var bone = new Bone();
        bone.name = "rotated";
        bone.cubes = new ArrayList<>();
        var target = cube(new float[]{0, 0, 0}, new float[]{2, 2, 2});
        // 这个方块旋转 45°，它的 origin/size 包围盒恰好"盖住"target 的 +X 面，
        // 但它实际画出来的是一个斜的盒子，并没有盖住那个面。
        var rotated = cube(new float[]{2, 0, 0}, new float[]{2, 2, 2});
        rotated.rotation = new float[]{0, 0, 45};
        bone.cubes.add(target);
        bone.cubes.add(rotated);

        GunLodDecimator.cullAllFaces(modelOf(bone));

        assertEquals(2, bone.cubes.size(), "两个方块都必须保留");
        for (var name : new String[]{"west", "east", "north", "south", "up", "down"}) {
            assertNotNull(faceOf(target, name), "旋转方块不得成为覆盖者，target 的 " + name + " 面必须保留");
            assertNotNull(faceOf(rotated, name), "旋转方块自身不做面级剔除");
        }
    }

    /** 完全被包住的方块整块删除。 */
    @Test
    void dropsFullyEnclosedCube() {
        var bone = new Bone();
        bone.name = "enclosed";
        bone.cubes = new ArrayList<>();
        var outer = cube(new float[]{0, 0, 0}, new float[]{4, 4, 4});
        var inner = cube(new float[]{1, 1, 1}, new float[]{2, 2, 2});
        bone.cubes.add(outer);
        bone.cubes.add(inner);

        GunLodDecimator.cullAllFaces(modelOf(bone));

        assertEquals(1, bone.cubes.size(), "被完全包住的方块应整块删除");
        assertTrue(bone.cubes.contains(outer), "外层方块必须保留");
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
        var uv = new CubeUv();
        uv.perFaceUv = faces;
        cube.uv = uv;
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
