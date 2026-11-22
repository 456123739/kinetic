package com.kinetic.client.compat.tacz;

import com.google.gson.Gson;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.tacz.guns.client.model.bedrock.BedrockModel;
import com.tacz.guns.client.resource.pojo.model.BedrockModelPOJO;
import com.tacz.guns.client.resource.pojo.model.BedrockVersion;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import net.minecraft.world.item.ItemDisplayContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TaCZ 逐帧路径的 CPU 基线：整棵骨骼遍历 + 每个 cube 的顶点生成。
 *
 * <p>这是参考方案里 "geometry traversal 0.311 ms/帧" 那一项，也是任何缓存方案要削减的对象。
 * 它不是游戏 FPS：没有 GPU、没有着色器、没有批次 flush，只回答"这一帧在 CPU 上花了多少"。
 *
 * <p>夹具按真实枪械形状造：1 个 root + 19 个骨骼，每骨骼 3 个 cube（共 57 个 cube、1368 顶点）。
 * 变换每帧都改，模拟动画在跑，避免把"完全静止"当成常态。
 */
class TaczModelRenderBenchmarkTest {
    private static final String MODEL = json(3);

    @Test
    void measureFullTraversalPerFrame() throws Exception {
        var pojo = new Gson().fromJson(MODEL, BedrockModelPOJO.class);
        var loader = new TaczDevRemapper(TaczModelRenderBenchmarkTest.class.getClassLoader());
        var modelClass = loader.loadClass("com.tacz.guns.client.model.bedrock.BedrockModel");
        var model = modelClass.getConstructor(BedrockModelPOJO.class, BedrockVersion.class)
                .newInstance(pojo, BedrockVersion.NEW);
        var sink = new CountingSink();
        @SuppressWarnings("unchecked")
        var roots = (java.util.List<Object>) modelClass.getMethod("getShouldRender").invoke(model);
        // 用带 MultiBufferSource 的重载：无参重载内部会去问 Forge ModList（Oculus 兼容），单测里不可用。
        var render = roots.get(0).getClass().getMethod("render", PoseStack.class,
                ItemDisplayContext.class, VertexConsumer.class, int.class, int.class,
                float.class, float.class, float.class, float.class,
                net.minecraft.client.renderer.MultiBufferSource.class);
        var buffers = new net.minecraft.client.renderer.MultiBufferSource() {
            @Override public VertexConsumer getBuffer(net.minecraft.client.renderer.RenderType type) {
                return sink;
            }
        };
        assertTrue(roots.size() > 0, "夹具必须解析出骨骼");
        Runnable frame = () -> {
            var pose = new PoseStack();
            pose.pushPose();
            pose.translate(0.1, -0.2, 0.35);
            try {
                for (var part : roots) {
                    render.invoke(part, pose, ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, sink,
                            0xF000F0, 0, 1F, 1F, 1F, 1F, buffers);
                }
            } catch (java.lang.reflect.InvocationTargetException error) {
                throw new IllegalStateException(String.valueOf(error.getCause()), error.getCause());
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException(error);
            }
            pose.popPose();
        };
        animate(roots, 0);
        for (int i = 0; i < 20_000; i++) {
            animate(roots, i);
            frame.run();
        }
        long[] times = new long[9];
        long[] bytes = new long[9];
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        var thread = Thread.currentThread().getId();
        var frames = 5_000;
        for (int round = 0; round < times.length; round++) {
            for (int i = 0; i < frames; i++) {
                animate(roots, round * frames + i);
            }
            var beforeBytes = bean.getThreadAllocatedBytes(thread);
            var before = System.nanoTime();
            for (int i = 0; i < frames; i++) {
                animate(roots, i);
                frame.run();
            }
            times[round] = System.nanoTime() - before;
            bytes[round] = bean.getThreadAllocatedBytes(thread) - beforeBytes;
        }
        Arrays.sort(times);
        Arrays.sort(bytes);
        var vertices = sink.vertices;
        System.out.printf("[tacz-frame] cubes=%d vertices/frame=%d%n", 19 * 3, vertices);
        System.out.printf("[tacz-frame] median %.1f us/frame, %.1f B/frame (9 rounds x %d frames)%n",
                times[4] / 1000.0 / frames, bytes[4] / (double) frames, frames);
        assertTrue(times[4] > 0 && vertices > 0);
    }

    /**
     * 同一棵树、但去掉所有 cube：用来把"遍历/矩阵"与"顶点发射"的开销拆开。
     *
     * <p>如果这一项仍然分配明显，说明分配来自骨骼变换（每个节点每次 {@code Axis.rotation} 都会
     * 新建四元数），而不是顶点写入 —— 那是唯一还能在 CPU 侧拿到的收益。
     */
    @Test
    void measureTraversalWithoutCubes() throws Exception {
        var withoutCubes = json(0);
        var pojo = new Gson().fromJson(withoutCubes, BedrockModelPOJO.class);
        var loader = new TaczDevRemapper(TaczModelRenderBenchmarkTest.class.getClassLoader());
        var modelClass = loader.loadClass("com.tacz.guns.client.model.bedrock.BedrockModel");
        var model = modelClass.getConstructor(BedrockModelPOJO.class, BedrockVersion.class)
                .newInstance(pojo, BedrockVersion.NEW);
        @SuppressWarnings("unchecked")
        var roots = (java.util.List<Object>) modelClass.getMethod("getShouldRender").invoke(model);
        var sink = new CountingSink();
        var render = roots.get(0).getClass().getMethod("render", PoseStack.class,
                ItemDisplayContext.class, VertexConsumer.class, int.class, int.class,
                float.class, float.class, float.class, float.class,
                net.minecraft.client.renderer.MultiBufferSource.class);
        var buffers = new net.minecraft.client.renderer.MultiBufferSource() {
            @Override public VertexConsumer getBuffer(net.minecraft.client.renderer.RenderType type) {
                return sink;
            }
        };
        for (int i = 0; i < 20_000; i++) {
            animate(roots, i);
            frame(roots, render, sink, buffers);
        }
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        var thread = Thread.currentThread().getId();
        long[] times = new long[9];
        long[] bytes = new long[9];
        var frames = 5_000;
        for (int round = 0; round < times.length; round++) {
            for (int i = 0; i < frames; i++) {
                animate(roots, round * frames + i);
            }
            var beforeBytes = bean.getThreadAllocatedBytes(thread);
            var before = System.nanoTime();
            for (int i = 0; i < frames; i++) {
                animate(roots, i);
                frame(roots, render, sink, buffers);
            }
            times[round] = System.nanoTime() - before;
            bytes[round] = bean.getThreadAllocatedBytes(thread) - beforeBytes;
        }
        Arrays.sort(times);
        Arrays.sort(bytes);
        System.out.printf("[tacz-frame] cubes=0 median %.1f us/frame, %.1f B/frame%n",
                times[4] / 1000.0 / frames, bytes[4] / (double) frames);
        assertTrue(times[4] > 0);
    }

    private static void frame(java.util.List<Object> roots, java.lang.reflect.Method render,
                              VertexConsumer sink,
                              net.minecraft.client.renderer.MultiBufferSource buffers) {
        var pose = new PoseStack();
        pose.pushPose();
        pose.translate(0.1, -0.2, 0.35);
        try {
            for (var part : roots) {
                render.invoke(part, pose, ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, sink,
                        0xF000F0, 0, 1F, 1F, 1F, 1F, buffers);
            }
        } catch (java.lang.reflect.InvocationTargetException error) {
            throw new IllegalStateException(String.valueOf(error.getCause()), error.getCause());
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
        pose.popPose();
    }

    /** 每帧改一点骨骼旋转，模拟动画在跑；根节点也跟着动。 */
    private static void animate(java.util.List<Object> roots, int frame) {
        var phase = frame * 0.05F;
        try {
            for (int i = 0; i < roots.size(); i++) {
                var part = roots.get(i);
                var type = part.getClass();
                type.getField("xRot").setFloat(part, (float) Math.sin(phase + i) * 4F);
                type.getField("yRot").setFloat(part, (float) Math.cos(phase + i * 0.5) * 3F);
                type.getField("zRot").setFloat(part, (float) Math.sin(phase * 0.7 + i) * 2F);
            }
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    static final class CountingSink implements VertexConsumer {
        long vertices;
        float sum;

        @Override
        public void vertex(float x, float y, float z, float r, float g, float b, float a,
                           float u, float v, int overlay, int light, float nx, float ny, float nz) {
            vertices++;
            sum += x + y + z + u + v + nx + ny + nz;
        }

        @Override public VertexConsumer vertex(double x, double y, double z) { return this; }
        @Override public VertexConsumer color(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer uv(float u, float v) { return this; }
        @Override public VertexConsumer overlayCoords(int u, int v) { return this; }
        @Override public VertexConsumer uv2(int u, int v) { return this; }
        @Override public VertexConsumer normal(float x, float y, float z) { return this; }
        @Override public void endVertex() { }
        @Override public void defaultColor(int r, int g, int b, int a) { }
        @Override public void unsetDefaultColor() { }
    }

    private static String json(int cubesPerBone) {
        var builder = new StringBuilder("""
                {"formatVersion":"1.12.0","minecraft:geometry":[
                {"description":{"texture_width":64,"texture_height":64,"visible_bounds_width":30,
                "visible_bounds_height":20,"visible_bounds_offset":[0,0,0]},"bones":[
                {"name":"root","pivot":[0,0,0]},
                """);
        for (int bone = 0; bone < 19; bone++) {
            builder.append("{\"name\":\"b").append(bone).append("\",\"parent\":\"")
                    .append(bone == 0 ? "root" : "b" + (bone - 1))
                    .append("\",\"pivot\":[").append(bone).append(",0,0],\"cubes\":[");
            for (int cube = 0; cube < cubesPerBone; cube++) {
                var x = bone * 2 + cube * 3;
                builder.append("{\"origin\":[").append(x).append(",0,0],")
                        .append("\"size\":[").append(1 + cube).append(",2,3],")
                        .append("\"uv\":[").append(cube * 4).append(",").append(bone).append("]}");
                if (cube < cubesPerBone - 1) {
                    builder.append(',');
                }
            }
            builder.append("]}");
            if (bone < 18) {
                builder.append(',');
            }
        }
        return builder.append("]}]}").toString();
    }
}
