package com.kinetic.client.compat.tacz;

import com.kinetic.util.UnsafeUtil;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.tacz.guns.client.model.bedrock.BedrockCube;
import com.tacz.guns.client.model.bedrock.BedrockPolygon;
import com.tacz.guns.client.model.bedrock.BedrockVertex;
import java.lang.reflect.Proxy;
import java.util.Random;
import java.util.function.Consumer;
import org.joml.Vector3f;

/**
 * 用真实 TaCZ 类搭的最小几何夹具。
 *
 * <p>发布版 jar 里 {@code compile} 引用的是 SRG 方法名，单测环境是命名映射，所以类要就地重映射
 * 那三个 Minecraft 调用；除此之外不改 TaCZ 的任何代码。
 */
final class TaczCubeFixture {
    private TaczCubeFixture() {
    }

    static BedrockPolygon[] geometry() throws Exception {
        var random = new Random(123);
        var polygons = new BedrockPolygon[6];
        for (int i = 0; i < polygons.length; i++) {
            var polygon = (BedrockPolygon) UnsafeUtil.getUnsafe()
                    .allocateInstance(BedrockPolygon.class);
            var vertices = new BedrockVertex[4];
            for (int j = 0; j < vertices.length; j++) {
                vertices[j] = new BedrockVertex(random.nextFloat() * 64 - 32,
                        random.nextFloat() * 64 - 32, random.nextFloat() * 64 - 32,
                        random.nextFloat(), random.nextFloat());
            }
            put(polygon, BedrockPolygon.class, "vertices", vertices);
            put(polygon, BedrockPolygon.class, "normal", new Vector3f(.2f, -.3f, .7f));
            polygons[i] = polygon;
        }
        return polygons;
    }

    static BedrockCube cube(Class<? extends BedrockCube> type, BedrockPolygon[] geometry)
            throws Exception {
        var mapped = mapped(type);
        var cube = (BedrockCube) UnsafeUtil.getUnsafe().allocateInstance(mapped);
        put(cube, mapped, "polygons", geometry);
        return cube;
    }

    /** 直接实现（非反射代理）：这才接近真实 BufferBuilder 的调用开销。 */
    static final class DirectSink implements VertexConsumer {
        long vertices;
        float sum;

        @Override
        public void vertex(float x, float y, float z, float r, float g, float b, float a,
                           float u, float v, int overlay, int light, float nx, float ny, float nz) {
            vertices++;
            sum += x + y + z + u + v + nx + ny + nz + overlay + light;
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

    static VertexConsumer sink(Consumer<Object[]> consumer) {
        return (VertexConsumer) Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(),
                new Class<?>[]{VertexConsumer.class}, (proxy, method, args) -> {
                    if (args != null && args.length == 14) {
                        consumer.accept(args);
                    }
                    return method.getReturnType() == VertexConsumer.class ? proxy : null;
                });
    }

    private static Class<?> mapped(Class<?> type) throws Exception {
        byte[] bytes;
        try (var input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            bytes = input.readAllBytes();
        }
        var writer = new org.objectweb.asm.ClassWriter(0);
        new org.objectweb.asm.ClassReader(bytes).accept(new org.objectweb.asm.ClassVisitor(
                org.objectweb.asm.Opcodes.ASM9, writer) {
            @Override
            public org.objectweb.asm.MethodVisitor visitMethod(int access, String name,
                                                               String descriptor, String signature,
                                                               String[] exceptions) {
                return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9,
                        super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method,
                                                String desc, boolean itf) {
                        var remapped = method;
                        if (owner.equals("com/mojang/blaze3d/vertex/PoseStack$Pose")) {
                            if (method.equals("m_252922_")) {
                                remapped = "pose";
                            } else if (method.equals("m_252943_")) {
                                remapped = "normal";
                            }
                        } else if (owner.equals("com/mojang/blaze3d/vertex/VertexConsumer")
                                && method.equals("m_5954_")) {
                            remapped = "vertex";
                        }
                        super.visitMethodInsn(opcode, owner, remapped, desc, itf);
                    }
                };
            }
        }, 0);
        var code = writer.toByteArray();
        return new ClassLoader(type.getClassLoader()) {
            Class<?> define() {
                return defineClass(type.getName(), code, 0, code.length);
            }
        }.define();
    }

    private static void put(Object target, Class<?> type, String name, Object value)
            throws Exception {
        var field = type.getDeclaredField(name);
        UnsafeUtil.getUnsafe().putObject(target,
                UnsafeUtil.getUnsafe().objectFieldOffset(field), value);
    }
}
