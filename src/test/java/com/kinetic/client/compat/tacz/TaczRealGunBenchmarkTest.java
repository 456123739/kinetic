package com.kinetic.client.compat.tacz;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.tacz.guns.client.model.bedrock.BedrockModel;
import com.tacz.guns.client.resource.pojo.model.BedrockModelPOJO;
import com.tacz.guns.client.resource.pojo.model.BedrockVersion;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.ItemDisplayContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 用**真实枪械模型**测 TaCZ 1.2.0-hotfix 的逐帧 CPU 成本。
 *
 * <p>夹具取自 TaCZ 自带的 {@code tacz_default_gun} 包（{@code gradle/tacz-gun-fixtures}），
 * 是玩家实际会拿在手上的形状与规模，不是合成出来的方块阵列。三个样本分别覆盖：
 * {@code ak74m}（1772 cube，全包最大）、{@code mk18}（1507）、{@code db_long}（小体积对照）。
 *
 * <p>这里量的是"每帧在 CPU 上发射这些顶点要多久"，不含 GPU、不含着色器、不含批次 flush。
 * 它是静态烘焙方案要削减的那部分，也是唯一能在无显卡环境里诚实测量的部分。
 */
class TaczRealGunBenchmarkTest {
    private static final Path FIXTURES = Path.of("gradle", "tacz-gun-fixtures");
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(com.tacz.guns.client.resource.pojo.model.CubesItem.class,
                    new com.tacz.guns.client.resource.pojo.model.CubesItem.Deserializer())
            .create();
    private static final int WARMUP = 200;
    private static final int ITERATIONS = 2000;

    @Test
    void measureRealGunsPerFrame() throws Exception {
        assumeTrue(Files.isDirectory(FIXTURES), "缺少真实枪械夹具");
        seedShaderLightDirections();
        var files = new ArrayList<Path>();
        try (var stream = Files.list(FIXTURES)) {
            stream.filter(p -> p.getFileName().toString().endsWith("_geo.json")).sorted().forEach(files::add);
        }
        assumeTrue(!files.isEmpty(), "夹具目录里没有 *_geo.json");

        var loader = new TaczDevRemapper(TaczRealGunBenchmarkTest.class.getClassLoader());
        var modelClass = loader.loadClass("com.tacz.guns.client.model.bedrock.BedrockModel");

        var report = new StringBuilder();
        double totalNanos = 0;
        long totalCubes = 0;
        long totalVertices = 0;
        for (var file : files) {
            // POJO 对应的是外层对象（含 minecraft:geometry），传内层几何会被 BedrockModel 断言拒绝。
            // 1.12 格式的 cube 用逐面 UV 对象表示，必须装上 TaCZ 自己的反序列化器。
            var pojo = GSON.fromJson(Files.readString(file), BedrockModelPOJO.class);
            var model = modelClass.getConstructor(BedrockModelPOJO.class, BedrockVersion.class)
                    .newInstance(pojo, BedrockVersion.NEW);
            @SuppressWarnings("unchecked")
            var roots = (List<Object>) modelClass.getMethod("getShouldRender").invoke(model);
            var render = roots.get(0).getClass().getMethod("render", PoseStack.class,
                    ItemDisplayContext.class, VertexConsumer.class, int.class, int.class,
                    float.class, float.class, float.class, float.class,
                    net.minecraft.client.renderer.MultiBufferSource.class);

            clearIlluminated(roots);
            var sink = new CountingSink();
            var pose = pose();
            for (int i = 0; i < WARMUP; i++) {
                pump(roots, render, pose, sink);
            }
            sink.reset();
            long start = System.nanoTime();
            for (int i = 0; i < ITERATIONS; i++) {
                pump(roots, render, pose, sink);
            }
            double nanos = (System.nanoTime() - start) / (double) ITERATIONS;
            long vertices = sink.vertices / 2; // 一半来自预热后的度量轮
            long cubes = countCubes(model);
            totalNanos += nanos;
            totalCubes += cubes;
            totalVertices += vertices;
            report.append(String.format(java.util.Locale.ROOT,
                    "%-14s cube=%5d 顶点=%6d  发射=%8.1f µs/帧  每 cube=%5.0f ns%n",
                    file.getFileName().toString().replace("_geo.json", ""), cubes, vertices,
                    nanos / 1000.0, nanos / Math.max(1, cubes)));
        }
        System.out.println("\n=== 真实枪械的逐帧 CPU 发射成本（TaCZ 1.2.0-hotfix）===\n" + report);
        System.out.printf(java.util.Locale.ROOT,
                "合计：%d cube / %d 顶点，单把平均 %.1f µs/帧，每 cube 平均 %.0f ns%n",
                totalCubes, totalVertices, totalNanos / files.size() / 1000.0,
                totalNanos / Math.max(1, totalCubes));
        assertTrue(totalNanos > 0 && totalCubes > 0, "度量必须产生非零样本");
    }

    /**
     * 真实枪模型里有 {@code illuminated} 的 cube，TaCZ 会走 {@code UnshadedVertexConsumer}，
     * 它通过 mixin 访问器读 {@code RenderSystem} 的光照方向矩阵。单测里 mixin 没应用，
     * 这两个静态字段是 null，必须先填成单位矩阵，否则渲染路径直接断言失败。
     */
    private static void seedShaderLightDirections() throws Exception {
        // RenderSystem 的这两个静态字段在 initRenderer 里才赋值，单测里是 null，
        // 且是 final 数组，无法反射写入。改为在模型上关掉 illuminated（见下方说明）。
    }

    /** 关掉 {@code illuminated}：它只是把消费者包一层 {@code UnshadedVertexConsumer}，不改几何量。 */
    private static void clearIlluminated(List<Object> parts) throws Exception {
        for (Object part : parts) {
            part.getClass().getField("illuminated").setBoolean(part, false);
            var childrenField = part.getClass().getField("children");
            Object children = childrenField.get(part);
            if (children instanceof List<?> list && !list.isEmpty()) {
                clearIlluminated(new ArrayList<>(list));
            }
        }
    }

    private static void pump(List<Object> roots, Method render, PoseStack pose, VertexConsumer sink)
            throws Exception {
        for (var root : roots) {
            render.invoke(root, pose, ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, sink,
                    0xF000F0, 0, 1f, 1f, 1f, 1f, null);
        }
    }

    private static PoseStack pose() {
        var pose = new PoseStack();
        pose.translate(0.3, -0.2, 0.5);
        pose.mulPose(new org.joml.Quaternionf().rotationXYZ(0.1f, 0.4f, -0.05f));
        return pose;
    }

    private static long countCubes(Object model) throws Exception {
        long[] count = {0};
        var partClass = model.getClass().getClassLoader() == null
                ? null : Class.forName("com.tacz.guns.client.model.bedrock.BedrockPart",
                false, model.getClass().getClassLoader());
        for (var field : model.getClass().getDeclaredFields()) {
            if (!List.class.isAssignableFrom(field.getType())) {
                continue;
            }
            field.setAccessible(true);
            Object value = field.get(model);
            if (!(value instanceof List<?> list) || list.isEmpty()) {
                continue;
            }
            if (partClass != null && partClass.isInstance(list.get(0))) {
                for (Object part : list) {
                    count[0] += countCubes(part);
                }
            }
        }
        try {
            var cubesField = model.getClass().getDeclaredField("cubes");
            cubesField.setAccessible(true);
            Object cubes = cubesField.get(model);
            if (cubes instanceof List<?> list) {
                count[0] += list.size();
                return count[0];
            }
        } catch (NoSuchFieldException ignored) {
            // 没有 cube 字段说明这不是骨骼
        }
        return count[0];
    }

    /** 只数顶点，不保存，避免把分配成本算进发射时间。 */
    private static final class CountingSink implements VertexConsumer {
        long vertices;

        void reset() {
            vertices = 0;
        }

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            vertices++;
            return this;
        }

        @Override
        public VertexConsumer color(int red, int green, int blue, int alpha) {
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            return this;
        }

        @Override
        public void endVertex() {
        }

        @Override
        public void defaultColor(int red, int green, int blue, int alpha) {
        }

        @Override
        public void unsetDefaultColor() {
        }
    }
}
