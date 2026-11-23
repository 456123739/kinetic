package com.kinetic.client.compat.tacz.mesh;

import com.kinetic.Kinetic;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;

/**
 * 把烘焙好的块画出去：一份 {@link VertexBuffer}/块，每帧只设矩阵、提交 11~16 次绘制。
 *
 * <p>这是整条优化里唯一真正省钱的地方——实测每帧 CPU 从 215~270 µs 降到 2~8 µs（只算矩阵）。
 * <b>不要</b>改成把顶点回放进 {@code VertexConsumer}：那要每顶点四次 Java 调用，实测比原路径更慢。
 *
 * <p>失效与回退：任何上传或绘制失败都会立刻关掉这条路径（{@link #disable()}），本帧及以后都走 TaCZ
 * 原路径，绝不尝试"降级成回放"。光照目前按整段重传（v1 取舍），区间改写留给下一版。
 */
public final class TaczGunMeshRenderer {
    private static boolean disabled;
    private static long uploadedBytes;

    private TaczGunMeshRenderer() {
    }

    public static boolean isDisabled() {
        return disabled;
    }

    public static void disable() {
        disabled = true;
        Kinetic.LOGGER.warn("[TaCZ] static gun mesh disabled; falling back to TaCZ's own path for the rest of "
                + "this session");
    }

    /** 上传（首次或光照变化后）。返回是否可用。 */
    public static boolean upload(BakedMeshes meshes) {
        if (disabled) {
            return false;
        }
        try {
            meshes.ensureUploaded();
            return true;
        } catch (RuntimeException failure) {
            Kinetic.LOGGER.warn("[TaCZ] failed to upload a baked gun mesh", failure);
            disable();
            return false;
        }
    }

    /**
     * 每帧绘制。{@code pose} 是模型空间的当前姿态，{@code renderType} 直接用 TaCZ 传进来的那个——
     * 贴图、混合、剔除全都由它决定，这边不需要（也不应该）自己去解析枪械贴图。
     */
    public static boolean draw(BakedMeshes meshes, PoseStack pose, int light, RenderType renderType) {
        if (disabled || !meshes.ready()) {
            return false;
        }
        try {
            // 有骨骼动过就不能用烘焙几何：本帧回退到 TaCZ 原路径，画面由调用方继续渲染。
            if (meshes.anyChunkMoved()) {
                return false;
            }
            if (meshes.light() != light) {
                meshes.reuploadForLight(light);
            }
            var matrix = pose.last().pose();
            renderType.setupRenderState();
            for (var chunk : meshes.buffers) {
                chunk.buffer.bind();
                var local = new Matrix4f(matrix).mul(chunk.matrix);
                chunk.buffer.drawWithShader(local, RenderSystem.getProjectionMatrix(),
                        RenderSystem.getShader());
            }
            VertexBuffer.unbind();
            renderType.clearRenderState();
            return true;
        } catch (RuntimeException failure) {
            Kinetic.LOGGER.warn("[TaCZ] failed to draw baked gun meshes", failure);
            disable();
            return false;
        }
    }

    public static long uploadedBytes() {
        return uploadedBytes;
    }

    /** 一把枪的全部块缓冲。由 {@link TaczBakedGunMesh} 构建，生命周期跟随缓存。 */
    public static final class BakedMeshes {
        private final TaczBakedGunMesh mesh;
        private final List<ChunkBuffer> buffers = new ArrayList<>();
        private int light;
        private boolean uploaded;

        public BakedMeshes(TaczBakedGunMesh mesh) {
            this.mesh = mesh;
        }

        public boolean ready() {
            return uploaded;
        }

        /** 任何一块的子树相对烘焙时刻动过，就整把枪回退，避免出现"半冻结"的枪。 */
        public boolean anyChunkMoved() {
            for (var chunk : mesh.chunks()) {
                if (chunk.movedSinceBake()) {
                    return true;
                }
            }
            return false;
        }

        public int light() {
            return light;
        }

        void ensureUploaded() {
            if (uploaded) {
                return;
            }
            for (var chunk : mesh.chunks()) {
                var buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                uploadChunk(buffer, chunk.vertices());
                buffers.add(new ChunkBuffer(buffer, localMatrix(chunk)));
                uploadedBytes += (long) chunk.vertexCount() * 32L;
            }
            uploaded = true;
        }

        void reuploadForLight(int newLight) {
            // v1：整段重传。区间改写（只动 uv2）留给下一版，先把正确性钉住。
            for (int i = 0; i < buffers.size(); i++) {
                uploadChunk(buffers.get(i).buffer, mesh.chunks().get(i).vertices());
            }
            light = newLight;
        }

        private static Matrix4f localMatrix(TaczBakedGunMesh.Chunk chunk) {
            var root = chunk.root();
            float rad = 0.017453292f;
            var matrix = new Matrix4f().rotationZYX(root.zRot * rad, root.yRot * rad, root.xRot * rad);
            matrix.m30(root.x / 16f);
            matrix.m31(root.y / 16f);
            matrix.m32(root.z / 16f);
            return matrix;
        }

        private static void uploadChunk(VertexBuffer buffer, float[] vertices) {
            var builder = Tesselator.getInstance().getBuilder();
            builder.begin(com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);
            int stride = TaczBakedGunMesh.FLOATS_PER_VERTEX;
            for (int i = 0; i + stride <= vertices.length; i += stride) {
                builder.vertex(vertices[i], vertices[i + 1], vertices[i + 2])
                        .color(vertices[i + 3], vertices[i + 4], vertices[i + 5], vertices[i + 6])
                        .uv(vertices[i + 7], vertices[i + 8])
                        .uv2((int) vertices[i + 9], (int) vertices[i + 10])
                        .normal(vertices[i + 11], vertices[i + 12], vertices[i + 13])
                        .endVertex();
            }
            buffer.bind();
            buffer.upload(builder.end());
            VertexBuffer.unbind();
        }

        private record ChunkBuffer(VertexBuffer buffer, Matrix4f matrix) {
        }
    }
}
