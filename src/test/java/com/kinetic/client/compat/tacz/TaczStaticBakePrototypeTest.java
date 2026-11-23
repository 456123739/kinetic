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
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 静态烘焙的 CPU 侧原型：把真实枪械模型烘成"每块一组模型空间顶点"，再比较两种每帧代价。
 *
 * <p>回答的问题是"烘焙之后还剩多少 CPU"，而不是"能不能画出来"——本类不碰 GL。
 * 两种口径分别对应两条实现路线：
 * <ul>
 *   <li><b>VBO 路线</b>：顶点常驻显存，每帧只算每块的变换矩阵 → 只看 {@code 矩阵} 那一列；</li>
 *   <li><b>回放兜底</b>：没有 VBO 时把烘焙好的顶点重新喂给 {@code VertexConsumer}（CGC 在新版本上
 *       的做法）→ 看 {@code 矩阵+回放} 那一列。</li>
 * </ul>
 *
 * <p>块的划分沿用"静态子树"口径：一个块 = 一段不被动画牵动的子树。这里用 TaCZ 模型的实际骨骼层级，
 * 块根取 {@code root} 的直接子树中带 cube 的骨骼，因此块数等于"参与动画的带 cube 骨骼数 + 1"，
 * 是最保守（块数偏多）的划法，收益估算因此偏保守。
 */
class TaczStaticBakePrototypeTest {
    private static final Path FIXTURES = Path.of("gradle", "tacz-gun-fixtures");
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(com.tacz.guns.client.resource.pojo.model.CubesItem.class,
                    new com.tacz.guns.client.resource.pojo.model.CubesItem.Deserializer())
            .create();
    private static final int WARMUP = 200;
    private static final int ITERATIONS = 1000;

    @Test
    void compareImmediateVersusBaked() throws Exception {
        assumeTrue(Files.isDirectory(FIXTURES), "缺少真实枪械夹具");
        var files = new ArrayList<Path>();
        try (var stream = Files.list(FIXTURES)) {
            stream.filter(p -> p.getFileName().toString().endsWith("_geo.json")).sorted().forEach(files::add);
        }
        assumeTrue(!files.isEmpty(), "夹具目录里没有 *_geo.json");

        var loader = new TaczDevRemapper(TaczStaticBakePrototypeTest.class.getClassLoader());
        var modelClass = loader.loadClass("com.tacz.guns.client.model.bedrock.BedrockModel");

        var report = new StringBuilder();
        for (var file : files) {
            var pojo = GSON.fromJson(Files.readString(file), BedrockModelPOJO.class);
            var model = modelClass.getConstructor(BedrockModelPOJO.class, BedrockVersion.class)
                    .newInstance(pojo, BedrockVersion.NEW);
            @SuppressWarnings("unchecked")
            var roots = (List<Object>) modelClass.getMethod("getShouldRender").invoke(model);
            clearIlluminated(roots);
            var render = roots.get(0).getClass().getMethod("render", PoseStack.class,
                    ItemDisplayContext.class, VertexConsumer.class, int.class, int.class,
                    float.class, float.class, float.class, float.class,
                    net.minecraft.client.renderer.MultiBufferSource.class);

            var chunks = bakeChunks(roots, render);
            int vertices = 0;
            for (var chunk : chunks) {
                vertices += chunk.vertexCount();
            }

            // 口径 A：即时模式（现状）
            var pose = pose();
            var sink = new CountingSink();
            double immediate = measure(() -> pump(roots, render, pose, sink));
            long immediateVertices = sink.vertices;

            // 口径 B：烘焙后只算每块矩阵
            double matrixOnly = measure(() -> {
                var stack = pose();
                for (var chunk : chunks) {
                    chunk.worldMatrix(stack.last().pose(), new Matrix4f());
                }
            });

            // 口径 C：烘焙后矩阵 + 顶点回放（无 VBO 的兜底路径）
            double withReplay = measure(() -> {
                var stack = pose();
                var target = new CountingSink();
                for (var chunk : chunks) {
                    chunk.replay(stack.last().pose(), target);
                }
            });

            report.append(String.format(java.util.Locale.ROOT,
                    "%-10s 块=%3d 顶点=%6d | 即时 %7.1f µs | 矩阵 %6.1f µs | 矩阵+回放 %7.1f µs | "
                            + "VBO 收益 %4.1fx | 回放收益 %4.1fx%n",
                    file.getFileName().toString().replace("_geo.json", ""), chunks.size(), vertices,
                    immediate / 1000.0, matrixOnly / 1000.0, withReplay / 1000.0,
                    immediate / Math.max(1.0, matrixOnly), immediate / Math.max(1.0, withReplay)));
            assertTrue(immediateVertices > 0, "即时模式必须真的发射了顶点");
        }
        System.out.println("\n=== 静态烘焙原型：同一批真实模型的每帧 CPU 对比 ===\n" + report);
    }

    @FunctionalInterface
    private interface Body {
        void run() throws Exception;
    }

    private static double measure(Body body) {
        try {
            for (int i = 0; i < WARMUP; i++) {
                body.run();
            }
            long start = System.nanoTime();
            for (int i = 0; i < ITERATIONS; i++) {
                body.run();
            }
            return (System.nanoTime() - start) / (double) ITERATIONS;
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** 把每根"带 cube 的根级子树"烘成一块：块内顶点用当前（静态）姿态算好，存模型空间。 */
    private static List<Chunk> bakeChunks(List<Object> roots, Method render) throws Exception {
        var chunks = new ArrayList<Chunk>();
        for (var root : roots) {
            var children = childrenOf(root);
            if (children.isEmpty()) {
                chunks.add(Chunk.bake(root, render, new ArrayList<>(List.of(root))));
                continue;
            }
            for (var child : children) {
                chunks.add(Chunk.bake(child, render, new ArrayList<>(List.of(child))));
            }
        }
        return chunks;
    }

    private static List<Object> childrenOf(Object part) throws Exception {
        Object children = part.getClass().getField("children").get(part);
        return children instanceof List<?> list ? new ArrayList<>(list) : List.of();
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

    private static void clearIlluminated(List<Object> parts) throws Exception {
        for (Object part : parts) {
            part.getClass().getField("illuminated").setBoolean(part, false);
            var children = childrenOf(part);
            if (!children.isEmpty()) {
                clearIlluminated(children);
            }
        }
    }

    /** 一段静态子树烘焙后的顶点与它的根骨骼。 */
    private static final class Chunk {
        private final Object root;
        private final float[] vertices;
        private float x;
        private float y;
        private float z;
        private float xRot;
        private float yRot;
        private float zRot;

        private Chunk(Object root, float[] vertices) {
            this.root = root;
            this.vertices = vertices;
            try {
                this.x = root.getClass().getField("x").getFloat(root);
                this.y = root.getClass().getField("y").getFloat(root);
                this.z = root.getClass().getField("z").getFloat(root);
                this.xRot = root.getClass().getField("xRot").getFloat(root);
                this.yRot = root.getClass().getField("yRot").getFloat(root);
                this.zRot = root.getClass().getField("zRot").getFloat(root);
            } catch (ReflectiveOperationException ignored) {
                // 缺字段就保持零变换
            }
        }

        static Chunk bake(Object root, Method render, List<Object> path) throws Exception {
            var capture = new CapturingSink();
            var pose = new PoseStack();
            // 只烘这一根骨骼自己的局部空间：块的世界矩阵由调用方每帧给。
            var method = root.getClass().getMethod("render", PoseStack.class,
                    ItemDisplayContext.class, VertexConsumer.class, int.class, int.class,
                    float.class, float.class, float.class, float.class,
                    net.minecraft.client.renderer.MultiBufferSource.class);
            method.invoke(root, pose, ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, capture,
                    0xF000F0, 0, 1f, 1f, 1f, 1f, null);
            return new Chunk(root, capture.toArray());
        }

        int vertexCount() {
            return vertices.length / 8;
        }

        /**
         * 该块骨骼的局部变换（TaCZ 的 translate + ZYX 旋转），直接写进 destination。
         *
         * <p>不新建 {@code PoseStack}、不反射取字段——真实实现里这些量都从骨骼对象上直接读，
         * 这里预存下来，是为了让"矩阵"这一列反映纯数学成本，而不是被分配噪声污染。
         */
        void worldMatrix(Matrix4f parent, Matrix4f destination) {
            float rad = 0.017453292f;
            var rotation = new Matrix4f().rotationZYX(zRot * rad, yRot * rad, xRot * rad);
            rotation.m30(x / 16f);
            rotation.m31(y / 16f);
            rotation.m32(z / 16f);
            destination.set(parent).mul(rotation);
        }

        void replay(Matrix4f parent, VertexConsumer sink) {
            var matrix = new Matrix4f();
            worldMatrix(parent, matrix);
            var normal = new Matrix3f(matrix).invert().transpose();
            for (int i = 0; i < vertices.length; i += 8) {
                float x = vertices[i];
                float y = vertices[i + 1];
                float z = vertices[i + 2];
                float nx = vertices[i + 5];
                float ny = vertices[i + 6];
                float nz = vertices[i + 7];
                float tx = matrix.m00() * x + matrix.m10() * y + matrix.m20() * z + matrix.m30();
                float ty = matrix.m01() * x + matrix.m11() * y + matrix.m21() * z + matrix.m31();
                float tz = matrix.m02() * x + matrix.m12() * y + matrix.m22() * z + matrix.m32();
                sink.vertex(tx, ty, tz);
                sink.uv(vertices[i + 3], vertices[i + 4]);
                sink.normal(normal.m00() * nx + normal.m10() * ny + normal.m20() * nz,
                        normal.m01() * nx + normal.m11() * ny + normal.m21() * nz,
                        normal.m02() * nx + normal.m12() * ny + normal.m22() * nz);
                sink.endVertex();
            }
        }
    }

    /** 记下烘焙时的顶点；每顶点 8 个 float（位置 3 + uv 2 + 法线 3）。 */
    private static final class CapturingSink implements VertexConsumer {
        private float[] data = new float[1 << 16];
        private int size;
        private float x;
        private float y;
        private float z;
        private float u;
        private float v;
        private float nx;
        private float ny;
        private float nz;

        float[] toArray() {
            return java.util.Arrays.copyOf(data, size);
        }

        private void push() {
            if (size + 8 > data.length) {
                data = java.util.Arrays.copyOf(data, data.length * 2);
            }
            data[size++] = x;
            data[size++] = y;
            data[size++] = z;
            data[size++] = u;
            data[size++] = v;
            data[size++] = nx;
            data[size++] = ny;
            data[size++] = nz;
        }

        @Override
        public VertexConsumer vertex(double px, double py, double pz) {
            x = (float) px;
            y = (float) py;
            z = (float) pz;
            return this;
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public VertexConsumer uv(float uu, float vv) {
            u = uu;
            v = vv;
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int uu, int vv) {
            return this;
        }

        @Override
        public VertexConsumer uv2(int uu, int vv) {
            return this;
        }

        @Override
        public VertexConsumer normal(float ax, float ay, float az) {
            nx = ax;
            ny = ay;
            nz = az;
            return this;
        }

        @Override
        public void endVertex() {
            push();
        }

        @Override
        public void defaultColor(int r, int g, int b, int a) {
        }

        @Override
        public void unsetDefaultColor() {
        }
    }

    private static final class CountingSink implements VertexConsumer {
        long vertices;

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            vertices++;
            return this;
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
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
        public void defaultColor(int r, int g, int b, int a) {
        }

        @Override
        public void unsetDefaultColor() {
        }
    }
}
