package com.kinetic.client.compat.realcamera;

import com.kinetic.buffer.ArrayBuffer;
import com.kinetic.buffer.NativeBuffer;
import com.kinetic.geckolib3.model.AnimatedGeoModel;
import com.kinetic.natives.render.NativeBakedModel;
import com.kinetic.natives.render.NativeModelState;
import com.kinetic.proto.mixel.asset.model.data.Bone;
import com.kinetic.proto.mixel.asset.model.data.CubeLegacy;
import com.kinetic.proto.mixel.asset.model.data.Cubes;
import com.kinetic.proto.mixel.asset.model.data.GeoModel;
import com.kinetic.proto.mixel.asset.model.data.GeoProperties;
import com.kinetic.testutil.NativeLibraryExtension;
import com.kinetic.util.ProtoUtil;
import java.nio.ByteBuffer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lwjgl.system.MemoryUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 相机绑定的端到端几何验证：从真实烘焙管线取出的模型上，真实相机的<b>默认</b>头部绑定 UV
 * 必须落在头骨正面与头顶上，并且解出来的位置就是眼睛高度。
 *
 * <p>这条断言是整个特性最有价值的一条：它同时钉住了「UV 归一化约定」「Bedrock X 镜像」
 * 「面内插值」「面法线朝向」四件事。任何一件偏了，相机都会贴在一个与模型不符的位置上。
 *
 * <p>模型按内置 Steve 的头部原样搭建（同一套 UV 与角点顺序），因此这里的期望值可以直接与
 * {@code analysis/} 下的离线渲染结果对照。
 */
@EnabledIfEnvironmentVariable(named = "KINETIC_NATIVE_PATH", matches = ".+")
@ExtendWith(NativeLibraryExtension.class)
class CameraBindGeometryNativeTest {
    private static final int TEXTURE_SIZE = 64;
    private static final int ORIGIN_VERSION = 29;

    /** 头骨八个角点，方块为单位、X 已按 Bedrock 约定镜像（与 {@code GeoBuilder} 一致）。 */
    private static final float[] POSITIONS = {
            -0.25f, 1.5f, -0.25f, // p1
            -0.25f, 1.5f, 0.25f,  // p2
            -0.25f, 2.0f, -0.25f, // p3
            -0.25f, 2.0f, 0.25f,  // p4
            0.25f, 1.5f, -0.25f,  // p5
            0.25f, 1.5f, 0.25f,   // p6
            0.25f, 2.0f, -0.25f,  // p7
            0.25f, 2.0f, 0.25f,   // p8
    };

    /** 六个面的角点下标（p 编号减一）、UV 四角与法线，取自内置 Steve 的 {@code Head} 立方体。 */
    private static final int[][] FACE_VERTICES = {
            {3, 2, 0, 1}, // west
            {6, 7, 5, 4}, // east
            {2, 6, 4, 0}, // north
            {7, 3, 1, 5}, // south
            {3, 7, 6, 2}, // up
            {0, 4, 5, 1}, // down
    };
    private static final float[][] FACE_UVS = {
            {0.375f, 0.125f, 0.25f, 0.125f, 0.25f, 0.25f, 0.375f, 0.25f},
            {0.125f, 0.125f, 0f, 0.125f, 0f, 0.25f, 0.125f, 0.25f},
            {0.25f, 0.125f, 0.125f, 0.125f, 0.125f, 0.25f, 0.25f, 0.25f},
            {0.5f, 0.125f, 0.375f, 0.125f, 0.375f, 0.25f, 0.5f, 0.25f},
            {0.25f, 0f, 0.125f, 0f, 0.125f, 0.125f, 0.25f, 0.125f},
            {0.375f, 0.125f, 0.25f, 0.125f, 0.25f, 0f, 0.375f, 0f},
    };
    private static final float[][] FACE_NORMALS = {
            {-1f, 0f, 0f}, {1f, 0f, 0f}, {0f, 0f, -1f},
            {0f, 0f, 1f}, {0f, 1f, 0f}, {0f, -1f, 0f},
    };

    /** 真实相机 {@code BindTarget.vanillaTarget} 的默认 UV，单位是归一化贴图坐标。 */
    private static final float DEFAULT_POS_U = 0.1875f;
    private static final float DEFAULT_POS_V = 0.2f;
    private static final float DEFAULT_UP_U = 0.1875f;
    private static final float DEFAULT_UP_V = 0.075f;

    @Test
    void defaultHeadBindingResolvesToTheEyesOnTheRealBake() throws Exception {
        var geometry = bakedGeometry();
        assertEquals(6, geometry.quadCount(), "head cube must expose six faces");

        var north = geometry.findQuad(DEFAULT_POS_U, DEFAULT_POS_V);
        var up = geometry.findQuad(DEFAULT_UP_U, DEFAULT_UP_V);
        assertTrue(north >= 0, "the default position UV must land on the head's front face");
        assertTrue(up >= 0, "the default upward UV must land on the head's top face");
        assertTrue(north != up, "the two UV points must resolve to different faces");

        var normal = new Vector3f();
        assertNotNull(geometry.normalAt(geometry.quad(north), normal));
        assertEquals(0f, normal.x, 1.0e-5f);
        assertEquals(0f, normal.y, 1.0e-5f);
        assertEquals(-1f, normal.z, 1.0e-5f, "the front face of a Bedrock head points to -Z");

        assertNotNull(geometry.normalAt(geometry.quad(up), normal));
        assertEquals(0f, normal.x, 1.0e-5f);
        assertEquals(1f, normal.y, 1.0e-5f);
        assertEquals(0f, normal.z, 1.0e-5f, "the top face points up");

        // (12, 12.8) 像素：头骨正面、眼睛高度、贴着脸的那一点。
        var position = new Vector3f();
        assertNotNull(geometry.positionAt(geometry.quad(north), DEFAULT_POS_U, DEFAULT_POS_V, position));
        assertEquals(0f, position.x, 1.0e-4f);
        assertEquals(1.7f, position.y, 1.0e-4f);
        assertEquals(-0.25f, position.z, 1.0e-4f);
    }

    @Test
    void solverComposesTheBonePoseWithTheModelPose() throws Exception {
        var geometry = bakedGeometry();
        var solver = new CameraBindSolver(geometry);
        var target = new CameraBindSolver.Target(DEFAULT_POS_U, DEFAULT_POS_V,
                DEFAULT_POS_U, DEFAULT_POS_V, DEFAULT_UP_U, DEFAULT_UP_V);
        var plan = solver.plan(target);
        assertTrue(plan.resolved());

        var model = buildModel();
        try (var modelData = ArrayBuffer.allocate(model.getSerializedSize());
             var texture = NativeBuffer.allocate(TEXTURE_SIZE * TEXTURE_SIZE * 4)) {
            model.writeTo(ProtoUtil.sink(modelData));
            MemoryUtil.memSet(texture.ptr(), 0xff, texture.size());
            var baked = NativeBakedModel.bake(modelData, 1, texture,
                    TEXTURE_SIZE, TEXTURE_SIZE, ORIGIN_VERSION, false);
            try (var bakedData = baked.bakedData()) {
                var read = NativeBakedModel.read(bakedData, 1);
                try (var bakedModel = read.bakedModel();
                     var state = NativeModelState.create()) {
                    var attributes = identityAttributes();
                    assertTrue(state.extract(bakedModel, attributes));

                    var position = new Vector3f();
                    var forward = new Vector3f();
                    var upward = new Vector3f();
                    // 模型姿态取单位阵：此时结果就是「模型局部坐标」。
                    assertTrue(solver.evaluate(plan, target, state.getBonePoses(),
                            new Matrix4f(), new Matrix3f(), position, forward, upward));
                    assertEquals(0f, position.x, 1.0e-4f);
                    assertEquals(1.7f, position.y, 1.0e-4f);
                    assertEquals(-0.25f, position.z, 1.0e-4f);
                    assertEquals(-1f, forward.z, 1.0e-4f);
                    assertEquals(1f, upward.y, 1.0e-4f);

                    // 模型姿态整体平移 (0, 1, 0)：绑定位置必须跟着走，朝向不变。
                    var translated = new Matrix4f().translate(0f, 1f, 0f);
                    assertTrue(solver.evaluate(plan, target, state.getBonePoses(),
                            translated, new Matrix3f(), position, forward, upward));
                    assertEquals(1.7f + 1f, position.y, 1.0e-4f);
                    assertEquals(-1f, forward.z, 1.0e-4f);
                    assertEquals(1f, upward.y, 1.0e-4f);

                    // 头骨绕自身枢轴旋转：绑定点到枢轴的距离必须保持不变。
                    // 只断言旋转不变量，避免把原生旋转的符号约定写进测试。
                    var pivot = new Vector3f(0f, 1.5f, 0f);
                    var restOffset = new Vector3f(0f, 1.7f, -0.25f).sub(pivot).length();
                    var rotated = identityAttributes();
                    rotated[1] = (float) (Math.PI / 2);
                    assertTrue(state.extract(bakedModel, rotated));
                    assertTrue(solver.evaluate(plan, target, state.getBonePoses(),
                            new Matrix4f(), new Matrix3f(), position, forward, upward));
                    assertEquals(restOffset, new Vector3f(position).sub(pivot).length(), 1.0e-4f);
                }
            }
        }
    }

    @Test
    void aModelWithoutCubesYieldsInsteadOfFailing() throws Exception {
        // 只有骨骼、没有立方体的模型（locator-only 或半成品）不能让解算抛异常：整条路径必须表现为
        // "给不出结果"，由调用方让位。
        var bone = Bone.newBuilder()
                .setName("Root")
                .addPivot(0f).addPivot(0f).addPivot(0f)
                .addRotate(0f).addRotate(0f).addRotate(0f)
                .build();
        var model = GeoModel.newBuilder()
                .addBones(bone)
                .setProperties(GeoProperties.newBuilder()
                        .setTextureWidth(TEXTURE_SIZE)
                        .setTextureHeight(TEXTURE_SIZE)
                        .build())
                .setCubes(ByteBuffer.allocate(0))
                .build();
        try (var modelData = ArrayBuffer.allocate(model.getSerializedSize());
             var texture = NativeBuffer.allocate(TEXTURE_SIZE * TEXTURE_SIZE * 4)) {
            model.writeTo(ProtoUtil.sink(modelData));
            MemoryUtil.memSet(texture.ptr(), 0xff, texture.size());
            var baked = NativeBakedModel.bake(modelData, 1, texture,
                    TEXTURE_SIZE, TEXTURE_SIZE, ORIGIN_VERSION, false);
            try (var bakedData = baked.bakedData()) {
                var read = NativeBakedModel.read(bakedData, 1);
                try (var bakedModel = read.bakedModel()) {
                    var geometry = CameraBindGeometry.extract(bakedModel);
                    assertEquals(0, geometry.quadCount());
                    assertEquals(-1, geometry.findQuad(DEFAULT_POS_U, DEFAULT_POS_V));
                    var solver = new CameraBindSolver(geometry);
                    assertTrue(solver.plan(new CameraBindSolver.Target(DEFAULT_POS_U, DEFAULT_POS_V,
                            DEFAULT_POS_U, DEFAULT_POS_V, DEFAULT_UP_U, DEFAULT_UP_V)).equals(
                            CameraBindSolver.Plan.NONE));
                }
            }
        }
    }

    private static CameraBindGeometry bakedGeometry() throws Exception {
        var model = buildModel();
        try (var modelData = ArrayBuffer.allocate(model.getSerializedSize());
             var texture = NativeBuffer.allocate(TEXTURE_SIZE * TEXTURE_SIZE * 4)) {
            model.writeTo(ProtoUtil.sink(modelData));
            MemoryUtil.memSet(texture.ptr(), 0xff, texture.size());
            var baked = NativeBakedModel.bake(modelData, 1, texture,
                    TEXTURE_SIZE, TEXTURE_SIZE, ORIGIN_VERSION, false);
            try (var bakedData = baked.bakedData()) {
                var read = NativeBakedModel.read(bakedData, 1);
                try (var bakedModel = read.bakedModel()) {
                    return CameraBindGeometry.extract(bakedModel);
                }
            }
        }
    }

    private static float[] identityAttributes() {
        var attributes = new float[AnimatedGeoModel.BONE_ATTRIBUTE_COUNT];
        attributes[6] = 1f;
        attributes[7] = 1f;
        attributes[8] = 1f;
        attributes[12] = 0xFFFFFF;
        attributes[13] = 0xFFFF;
        return attributes;
    }

    /** 与内置 Steve 的头部完全同构：一个 Head 骨骼 + 一个带六面贴图的 8×8×8 立方体。 */
    private static GeoModel buildModel() throws java.io.IOException {
        var cube = CubeLegacy.newBuilder().setFaceCount(FACE_VERTICES.length);
        for (float value : POSITIONS) {
            cube.addPos(value);
        }
        for (var face = 0; face < FACE_VERTICES.length; face++) {
            for (var corner = 0; corner < 4; corner++) {
                cube.addPosIndices(FACE_VERTICES[face][corner]);
                cube.addUv(FACE_UVS[face][corner * 2]);
                cube.addUv(FACE_UVS[face][corner * 2 + 1]);
                // UV 池是按 uvIndices 索引的全局数组：每个面必须用自己的四个槽位，
                // 否则所有面都会引用第一个面的 UV（这正是本测试第一版踩到的坑）。
                cube.addUvIndices(face * 4 + corner);
            }
            cube.addNormal(FACE_NORMALS[face][0]);
            cube.addNormal(FACE_NORMALS[face][1]);
            cube.addNormal(FACE_NORMALS[face][2]);
        }

        var cubes = Cubes.newBuilder().addCubesLegacy(cube.build()).build();
        // 枢轴在 proto 里是模型内部单位（1/16 格），且 X 取负。
        var bone = Bone.newBuilder()
                .setName("Head")
                .addPivot(0f).addPivot(24f).addPivot(0f)
                .addRotate(0f).addRotate(0f).addRotate(0f)
                .setCubeCount(1)
                .build();
        return GeoModel.newBuilder()
                .addBones(bone)
                .setProperties(GeoProperties.newBuilder()
                        .setTextureWidth(TEXTURE_SIZE)
                        .setTextureHeight(TEXTURE_SIZE)
                        .build())
                .setCubes(ByteBuffer.wrap(ProtoUtil.serializeToArray(cubes)))
                .build();
    }
}
