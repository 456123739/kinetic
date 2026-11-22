package com.kinetic.client.compat.tacz;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.tacz.guns.client.model.bedrock.BedrockCube;
import com.tacz.guns.client.model.bedrock.BedrockCubeBox;
import com.tacz.guns.client.model.bedrock.BedrockPolygon;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 顶点发射循环的候选实现对比：TaCZ 原版 vs 去掉每顶点三次除法的版本。
 *
 * <p>原版对每个顶点算 {@code x/16, y/16, z/16}。1/16 是 2 的幂，改成 {@code *0.0625f}
 * 在正规数范围内逐位相同，但省掉三次除法。
 *
 * <p>先证明逐位一致（14 个属性全比），再报时间；两者都成立才值得往 TaCZ 里掺一脚。
 */
class TaczCubeVariantBenchmarkTest {
    private static final class Variant {
        private final Vector4f position = new Vector4f();
        private final Vector3f normal = new Vector3f();

        void emit(BedrockPolygon[] polygons, PoseStack.Pose pose, VertexConsumer consumer,
                  int light, int overlay, float red, float green, float blue, float alpha) {
            var poseMatrix = pose.pose();
            var normalMatrix = pose.normal();
            for (var polygon : polygons) {
                normal.set(polygon.normal).mul(normalMatrix);
                float nx = normal.x();
                float ny = normal.y();
                float nz = normal.z();
                for (var vertex : polygon.vertices) {
                    var source = vertex.pos;
                    position.set(source.x() * 0.0625f, source.y() * 0.0625f,
                                    source.z() * 0.0625f, 1.0f)
                            .mul(poseMatrix);
                    consumer.vertex(position.x(), position.y(), position.z(),
                            red, green, blue, alpha, vertex.u, vertex.v,
                            overlay, light, nx, ny, nz);
                }
            }
        }
    }

    @Test
    void scaledConversionIsBitIdenticalToTaczDivision() throws Exception {
        var geometry = TaczCubeFixture.geometry();
        var cube = TaczCubeFixture.cube(BedrockCubeBox.class, geometry);
        var variant = new Variant();
        var random = new java.util.Random(4242);
        for (int round = 0; round < 200; round++) {
            var stack = new PoseStack();
            stack.translate(random.nextFloat() * 8 - 4, random.nextFloat() * 8 - 4,
                    random.nextFloat() * 8 - 4);
            stack.mulPose(new org.joml.Quaternionf().rotationXYZ(
                    random.nextFloat() * 6, random.nextFloat() * 6, random.nextFloat() * 6));
            stack.scale(random.nextFloat() * 3 + .1f, random.nextFloat() * 3 + .1f,
                    random.nextFloat() * 3 + .1f);
            var reference = new ArrayList<List<Object>>();
            var actual = new ArrayList<List<Object>>();
            cube.compile(stack.last(), TaczCubeFixture.sink(args -> reference.add(List.of(args))),
                    0xF000F0, 77, .25f, .5f, .75f, 1f);
            variant.emit(geometry, stack.last(), TaczCubeFixture.sink(args -> actual.add(List.of(args))),
                    0xF000F0, 77, .25f, .5f, .75f, 1f);
            assertEquals(reference, actual, "第 " + round + " 组变换下顶点必须逐位相同");
        }
    }

    @Test
    void measureBothLoops() throws Exception {
        var geometry = TaczCubeFixture.geometry();
        var cube = TaczCubeFixture.cube(BedrockCubeBox.class, geometry);
        var variant = new Variant();
        var stack = new PoseStack();
        stack.translate(.1, .2, .3);
        stack.scale(-.7f, 1.3f, .9f);
        var pose = stack.last();
        final var direct = new TaczCubeFixture.DirectSink();
        var sink = (VertexConsumer) direct;
        Runnable baseline = () -> cube.compile(pose, sink, 123, 456, .3f, .5f, .7f, 1f);
        Runnable candidate = () -> variant.emit(geometry, pose, sink, 123, 456, .3f, .5f, .7f, 1f);
        for (int i = 0; i < 200_000; i++) {
            baseline.run();
            candidate.run();
        }
        var count = 300_000;
        long[] base = new long[9];
        long[] cand = new long[9];
        for (int round = 0; round < base.length; round++) {
            if ((round & 1) == 0) {
                base[round] = time(baseline, count);
                cand[round] = time(candidate, count);
            } else {
                cand[round] = time(candidate, count);
                base[round] = time(baseline, count);
            }
        }
        Arrays.sort(base);
        Arrays.sort(cand);
        System.out.printf("[tacz-div] baseline %.2f ns/cube, x0.0625 %.2f ns/cube (%.1f%%)%n",
                base[4] / (double) count, cand[4] / (double) count,
                cand[4] * 100.0 / base[4]);
        assertTrue(cand[4] > 0);
    }

    private static long time(Runnable action, int count) {
        long before = System.nanoTime();
        for (int i = 0; i < count; i++) {
            action.run();
        }
        return System.nanoTime() - before;
    }

}
