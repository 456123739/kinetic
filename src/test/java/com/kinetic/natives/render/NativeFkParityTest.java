package com.kinetic.natives.render;

import com.kinetic.buffer.ArrayBuffer;
import com.kinetic.buffer.NativeBuffer;
import com.kinetic.geckolib3.model.AnimatedGeoModel;
import com.kinetic.proto.mixel.asset.model.data.Bone;
import com.kinetic.proto.mixel.asset.model.data.CubeLegacy;
import com.kinetic.proto.mixel.asset.model.data.Cubes;
import com.kinetic.proto.mixel.asset.model.data.GeoModel;
import com.kinetic.proto.mixel.asset.model.data.GeoProperties;
import com.kinetic.testutil.NativeLibraryExtension;
import com.kinetic.util.ProtoUtil;
import com.google.gson.JsonParser;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lwjgl.system.MemoryUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hard FK parity measurement: does the native {@code ModelState.Extract} produce the same
 * world-space bone pivot as {@code analysis/anim_render.py}?
 *
 * <p>Opt-in twice: {@code KINETIC_NATIVE_PATH} must point at a loadable native library and
 * {@code KINETIC_BENCH_FK=1} must be set. Without the latter the test is skipped, so the normal
 * suite never pays for it.
 *
 * <pre>
 * source /home/admin/NS/gradle-env.sh &amp;&amp; cd /home/admin/NS/kinetic &amp;&amp;
 *   KINETIC_BENCH_FK=1 ./gradlew test --offline --tests '*NativeFkParityTest*'
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "KINETIC_NATIVE_PATH", matches = ".+")
@ExtendWith(NativeLibraryExtension.class)
class NativeFkParityTest {
    private static final String GATE_ENV = "KINETIC_BENCH_FK";
    private static final int TEXTURE_SIZE = 64;
    private static final int ORIGIN_VERSION = 29;
    private static final int MODEL_UNITS_PER_BLOCK = 16;

    private static final String MODEL_PATH =
            "src/main/resources/assets/kinetic/builtin/misc/2_steve/models/main.json";
    private static final String ANIM_PATH =
            "src/main/resources/assets/kinetic/builtin/misc/2_steve/animations/arm.animation.json";
    private static final String ANIM_NAME = "use_mainhand:eat";
    private static final double SAMPLE_TIME = 0.25;

    /** The chain whose pivots we compare, leaf last. */
    private static final List<String> CHAIN = List.of(
            "Root", "MAllBody", "AllBody", "UpBody", "UpperBody", "Arm",
            "RightArm", "RightForeArm", "RightHand");

    @Test
    void comparesNativeAndPythonForwardKinematics() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv(GATE_ENV)),
                "set " + GATE_ENV + "=1 to run the native FK parity measurement");

        var bones = readModelBones(Path.of(MODEL_PATH));
        var animation = readAnimationBoneRotations(Path.of(ANIM_PATH), ANIM_NAME, SAMPLE_TIME);

        System.out.println("=== native FK parity measurement ===");
        System.out.println("model      = " + MODEL_PATH);
        System.out.println("animation  = " + ANIM_NAME + " @ t=" + SAMPLE_TIME + "s");
        System.out.println("bones      = " + bones.size());
        System.out.println();

        for (var entry : animation.entrySet()) {
            System.out.printf("anim rotation %-14s = [%s]%n", entry.getKey(),
                    java.util.Arrays.toString(entry.getValue()));
        }
        System.out.println();

        var nativePose = extractNative(bones, animation, null, null);
        System.out.println("native baked bone order (sorted index -> original name):");
        for (var i = 0; i < bones.size(); i++) {
            System.out.printf("  [%2d] %s%n", i, nativePose.sortedNames().get(i));
        }
        System.out.println();

        var handPivot = pivotOf(bones, "RightHand");
        var nativeHand = pivotWorld(nativePose, "RightHand", handPivot);
        System.out.printf("native RightHand pivot (model units) = (%.6f, %.6f, %.6f)%n",
                nativeHand.x, nativeHand.y, nativeHand.z);

        System.out.println();
        System.out.println("native pivot world positions for the chain (model units):");
        for (var name : CHAIN) {
            var world = pivotWorld(nativePose, name, pivotOf(bones, name));
            System.out.printf("  %-14s (%.6f, %.6f, %.6f)%n", name, world.x, world.y, world.z);
        }

        // Secondary: nonzero position on RightForeArm to isolate the position sign convention.
        var positionProbe = new Vector3f(3.0f, 4.0f, 5.0f);
        var posedPose = extractNative(bones, animation, positionProbe, null);
        var nativeProbe = pivotWorld(posedPose, "RightHand", handPivot);
        System.out.println();
        System.out.printf("position probe (RightForeArm position=(%.1f, %.1f, %.1f))%n",
                positionProbe.x, positionProbe.y, positionProbe.z);
        System.out.printf("native RightHand pivot (model units) = (%.6f, %.6f, %.6f)%n",
                nativeProbe.x, nativeProbe.y, nativeProbe.z);

        // Tertiary: non-uniform scale on RightForeArm to isolate the scale convention.
        var scaleProbe = new Vector3f(2.0f, 3.0f, 0.5f);
        var scaledPose = extractNative(bones, animation, null, scaleProbe);
        var nativeScaled = pivotWorld(scaledPose, "RightHand", handPivot);
        System.out.println();
        System.out.printf("scale probe (RightForeArm scale=(%.1f, %.1f, %.1f))%n",
                scaleProbe.x, scaleProbe.y, scaleProbe.z);
        System.out.printf("native RightHand pivot (model units) = (%.6f, %.6f, %.6f)%n",
                nativeScaled.x, nativeScaled.y, nativeScaled.z);

        System.out.println();
        System.out.println("native accumulated pose matrices (column-major, JOML Matrix4f):");
        for (var name : List.of("RightArm", "RightForeArm", "RightHand")) {
            var matrix = nativePose.poseOf(name);
            System.out.printf("  %s = [", name);
            for (var column = 0; column < 4; column++) {
                for (var row = 0; row < 4; row++) {
                    System.out.printf("%s%.8f", (column == 0 && row == 0) ? "" : ", ",
                            matrix.get(row, column));
                }
            }
            System.out.println("]");
        }

        assertTrue(nativePose.boneCount() > 0, "native pose view must not be empty");
    }

    // ------------------------------------------------------------------ native

    private record NativePose(List<String> sortedNames, List<Matrix4f> poses) {
        int boneCount() {
            return poses.size();
        }

        Matrix4f poseOf(String originalName) {
            var index = sortedNames.indexOf(originalName);
            if (index < 0) {
                throw new IllegalArgumentException("bone not found: " + originalName);
            }
            return poses.get(index);
        }
    }

    private static NativePose extractNative(List<ModelBone> bones,
                                            java.util.Map<String, float[]> rotations,
                                            Vector3f foreArmPosition,
                                            Vector3f foreArmScale) throws Exception {
        var model = buildModel(bones);
        var texture = NativeBuffer.allocate(TEXTURE_SIZE * TEXTURE_SIZE * 4);
        try (var modelData = ArrayBuffer.allocate(model.getSerializedSize())) {
            model.writeTo(ProtoUtil.sink(modelData));
            MemoryUtil.memSet(texture.ptr(), 0xff, texture.size());
            var baked = NativeBakedModel.bake(modelData, bones.size(), texture,
                    TEXTURE_SIZE, TEXTURE_SIZE, ORIGIN_VERSION, false);
            try (var bakedData = baked.bakedData()) {
                var read = NativeBakedModel.read(bakedData, bones.size());
                try (var bakedModel = read.bakedModel();
                     var state = NativeModelState.create()) {
                    var sortedBoneIndices = baked.sortedBoneIndices();
                    var sortedNames = new ArrayList<String>(bones.size());
                    for (var sorted = 0; sorted < bones.size(); sorted++) {
                        sortedNames.add(bones.get(Short.toUnsignedInt(sortedBoneIndices[sorted])).name());
                    }

                    var attributes = new float[bones.size() * AnimatedGeoModel.BONE_ATTRIBUTE_COUNT];
                    for (var sorted = 0; sorted < bones.size(); sorted++) {
                        var original = Short.toUnsignedInt(sortedBoneIndices[sorted]);
                        var offset = sorted * AnimatedGeoModel.BONE_ATTRIBUTE_COUNT;
                        var name = bones.get(original).name();
                        var rotation = rotations.get(name);
                        if (rotation != null) {
                            attributes[offset] = rotation[0];
                            attributes[offset + 1] = rotation[1];
                            attributes[offset + 2] = rotation[2];
                        }
                        if (foreArmPosition != null && "RightForeArm".equals(name)) {
                            attributes[offset + 3] = foreArmPosition.x;
                            attributes[offset + 4] = foreArmPosition.y;
                            attributes[offset + 5] = foreArmPosition.z;
                        }
                        attributes[offset + 6] = 1;
                        attributes[offset + 7] = 1;
                        attributes[offset + 8] = 1;
                        if (foreArmScale != null && "RightForeArm".equals(name)) {
                            attributes[offset + 6] = foreArmScale.x;
                            attributes[offset + 7] = foreArmScale.y;
                            attributes[offset + 8] = foreArmScale.z;
                        }
                        attributes[offset + 12] = 0xFFFFFF;
                        attributes[offset + 13] = 0xFFFF;
                    }

                    assertTrue(state.extract(bakedModel, attributes), "extract failed");
                    var poses = new ArrayList<Matrix4f>(bones.size());
                    var view = state.getBonePoses();
                    assertEquals(bones.size(), view.getBoneCount(), "bone count");
                    for (var sorted = 0; sorted < bones.size(); sorted++) {
                        poses.add(view.getPose(sorted, new Matrix4f()));
                    }
                    return new NativePose(sortedNames, poses);
                }
            }
        } finally {
            texture.close();
        }
    }

    private static Vector3f pivotWorld(NativePose pose, String name, Vector3f pivot) {
        var point = new Vector3f(pivot).mul(1.0f / MODEL_UNITS_PER_BLOCK);
        point.mulPosition(pose.poseOf(name));
        return point.mul(MODEL_UNITS_PER_BLOCK);
    }

    private static Vector3f pivotOf(List<ModelBone> bones, String name) {
        for (var bone : bones) {
            if (bone.name().equals(name)) {
                return new Vector3f(bone.pivotX(), bone.pivotY(), bone.pivotZ());
            }
        }
        throw new IllegalArgumentException("bone not found: " + name);
    }

    // ------------------------------------------------------------------ model json

    private record ModelBone(String name, String parent, float pivotX, float pivotY,
                             float pivotZ) {
    }

    private static List<ModelBone> readModelBones(Path path) throws Exception {
        var root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        var geometry = root.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
        var result = new ArrayList<ModelBone>();
        for (var element : geometry.getAsJsonArray("bones")) {
            var bone = element.getAsJsonObject();
            var name = bone.get("name").getAsString();
            var parent = bone.has("parent") && !bone.get("parent").isJsonNull()
                    ? bone.get("parent").getAsString() : null;
            var pivot = bone.getAsJsonArray("pivot");
            result.add(new ModelBone(name, parent,
                    pivot.get(0).getAsFloat(), pivot.get(1).getAsFloat(),
                    pivot.get(2).getAsFloat()));
        }
        return result;
    }

    /**
     * Reproduces the Java loader's rotation convention: degrees -> radians, then negate X
     * and Y only (RawBoneKeyFrame#init with flip=true,true,false).
     */
    private static java.util.Map<String, float[]> readAnimationBoneRotations(
            Path path, String animationName, double time) throws Exception {
        var root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        var animation = root.getAsJsonObject("animations").getAsJsonObject(animationName);
        var result = new java.util.LinkedHashMap<String, float[]>();
        var bones = animation.getAsJsonObject("bones");
        for (var boneName : bones.keySet()) {
            var channels = bones.getAsJsonObject(boneName);
            if (!channels.has("rotation")) {
                continue;
            }
            var rotation = channels.get("rotation");
            if (!rotation.isJsonObject()) {
                continue;
            }
            var keyframes = rotation.getAsJsonObject();
            var key = String.valueOf(time);
            if (!keyframes.has(key)) {
                continue;
            }
            var frame = keyframes.get(key).getAsJsonObject();
            var value = frame.has("post") ? frame.getAsJsonArray("post")
                    : frame.getAsJsonArray("pre");
            var x = value.get(0).getAsFloat();
            var y = value.get(1).getAsFloat();
            var z = value.get(2).getAsFloat();
            result.put(boneName, new float[]{
                    (float) Math.toRadians(-x), (float) Math.toRadians(-y),
                    (float) Math.toRadians(z)});
        }
        return result;
    }

    private static GeoModel buildModel(List<ModelBone> bones) throws Exception {
        var cubes = Cubes.newBuilder();
        for (var index = 0; index < bones.size(); index++) {
            cubes.addCubesLegacy(box(index));
        }
        var model = GeoModel.newBuilder()
                .setProperties(GeoProperties.newBuilder()
                        .setTextureWidth(TEXTURE_SIZE)
                        .setTextureHeight(TEXTURE_SIZE)
                        .build())
                .setCubes(ByteBuffer.wrap(ProtoUtil.serializeToArray(cubes.build())));
        for (var bone : bones) {
            var builder = Bone.newBuilder()
                    .setName(bone.name())
                    .addPivot(bone.pivotX()).addPivot(bone.pivotY()).addPivot(bone.pivotZ())
                    .addRotate(0).addRotate(0).addRotate(0)
                    .setCubeCount(1);
            if (bone.parent() != null) {
                builder.setParent(bone.parent());
            }
            model.addBones(builder.build());
        }
        return model.build();
    }

    private static final int[][] FACE_VERTICES = {
            {4, 3, 1, 2}, {7, 8, 6, 5}, {3, 7, 5, 1},
            {8, 4, 2, 6}, {4, 8, 7, 3}, {1, 5, 6, 2},
    };
    private static final int[][] FACE_NORMALS = {
            {-1, 0, 0}, {1, 0, 0}, {0, 0, -1}, {0, 0, 1}, {0, 1, 0}, {0, -1, 0},
    };

    /** One small 6-face box, same construction as NativeExtractCostBenchmarkTest#box. */
    private static CubeLegacy box(int index) {
        var sizeX = 2 + index % 2;
        var sizeY = 2 + (index / 2) % 2;
        var sizeZ = 1 + index % 3;
        var originX = -(sizeX / 2f);
        var originY = 0;
        var originZ = -(sizeZ / 2f);
        var x = sizeX / (float) MODEL_UNITS_PER_BLOCK;
        var y = sizeY / (float) MODEL_UNITS_PER_BLOCK;
        var z = sizeZ / (float) MODEL_UNITS_PER_BLOCK;

        var u = (index * 3) % 32;
        var v = (index * 5) % 32;
        var uvCorners = new float[][]{
                {u + z, v + z + y}, {u + z, v + z},
                {u + z + x, v + z + y}, {u + z + x, v + z},
                {u, v + z + y}, {u, v + z},
                {u + z + x, v}, {u + z, v},
        };

        var positions = new float[8 * 3];
        for (var vertex = 0; vertex < 8; vertex++) {
            var px = originX + ((vertex & 1) != 0 ? sizeX : 0);
            var py = originY + ((vertex & 2) != 0 ? sizeY : 0);
            var pz = originZ + ((vertex & 4) != 0 ? sizeZ : 0);
            positions[vertex * 3] = px / MODEL_UNITS_PER_BLOCK;
            positions[vertex * 3 + 1] = py / MODEL_UNITS_PER_BLOCK;
            positions[vertex * 3 + 2] = pz / MODEL_UNITS_PER_BLOCK;
        }

        var cube = CubeLegacy.newBuilder().setFaceCount(6);
        for (var value : positions) {
            cube.addPos(value);
        }
        for (var face = 0; face < 6; face++) {
            for (var corner = 0; corner < 4; corner++) {
                var vertex = FACE_VERTICES[face][corner] - 1;
                cube.addPosIndices(vertex);
                cube.addUv(uvCorners[vertex][0] / TEXTURE_SIZE);
                cube.addUv(uvCorners[vertex][1] / TEXTURE_SIZE);
                cube.addUvIndices(vertex);
            }
            cube.addNormal(FACE_NORMALS[face][0]);
            cube.addNormal(FACE_NORMALS[face][1]);
            cube.addNormal(FACE_NORMALS[face][2]);
        }
        return cube.build();
    }
}
