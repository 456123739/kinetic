package com.kinetic.client.compat.tacz.mesh;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.tacz.guns.client.model.bedrock.BedrockModel;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.ItemDisplayContext;

/**
 * 一把枪的静态烘焙结果：每个块一组模型空间顶点 + 块根骨骼的局部变换基准。
 *
 * <p>块按 {@link BedrockModel#getShouldRender()} 的**根级带方块骨骼**划分。三把真实枪实测得到
 * 11~16 块（AK74M 14、MK18 16、db_long 11），每帧只需为每块算一次矩阵——这正是静态烘焙的全部收益来源：
 * 每帧一个顶点都不发。
 *
 * <p><b>不要用"回放"替代它。</b>同一份数据若每帧重新喂给 {@code VertexConsumer}，4.2 万顶点要
 * 349~462 µs，比 TaCZ 原始路径的 215~270 µs 还慢（每顶点四次 Java 调用省不掉）。没有 GPU 缓冲时
 * 正确的做法是**直接放弃烘焙、走原路径**。
 *
 * <p>顶点在烘焙时用 TaCZ 自己的 {@code render} 产生，因此与原始渲染逐位一致，不存在 UV 或法线
 * 约定写反的风险。
 */
public final class TaczBakedGunMesh {
    /** 每个顶点：位置 3 + 颜色 4 + uv 2 + 光照 2 + 法线 3，全部以 float 存，上传时再压成顶点格式。 */
    public static final int FLOATS_PER_VERTEX = 14;

    private final List<Chunk> chunks;
    private final int vertexCount;

    private TaczBakedGunMesh(List<Chunk> chunks, int vertexCount) {
        this.chunks = chunks;
        this.vertexCount = vertexCount;
    }

    public List<Chunk> chunks() {
        return chunks;
    }

    public int vertexCount() {
        return vertexCount;
    }

    /** 顶点按交错 float 存储时的字节数，用于缓存预算。 */
    public long bytes() {
        return (long) vertexCount * FLOATS_PER_VERTEX * Float.BYTES;
    }

    /**
     * 用 TaCZ 自己的渲染路径把模型烘成块。
     *
     * <p><b>已知限制（必须先补上再默认启用）</b>：现在把"根级骨骼的整棵子树"一起烘进来，
     * 如果子树里还有会动的骨骼（拉栓、弹匣、瞄具），它们的姿态会被冻结在烘焙那一刻。
     * 下一步要在 {@link Chunk} 里记下子树内所有骨骼的局部变换，每帧比对；一旦有变化就本帧回退到
     * TaCZ 原路径。在没有这个校验之前，这个开关只能是实验性的。
     *
     * @param roots {@link BedrockModel#getShouldRender()} 给出的根级骨骼
     */
    public static TaczBakedGunMesh bake(List<BedrockPart> roots) {
        var chunks = new ArrayList<Chunk>();
        int total = 0;
        var candidates = new ArrayList<BedrockPart>();
        for (var root : roots) {
            if (root.children.isEmpty()) {
                candidates.add(root);
            } else {
                candidates.addAll(root.children);
            }
        }
        for (var part : candidates) {
            var capture = new VertexCapture();
            var pose = new PoseStack();
            try {
                // 只烘这一根骨骼的局部空间：块的世界矩阵每帧由调用方给。
                part.render(pose, ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, capture,
                        0xF000F0, 0, 1f, 1f, 1f, 1f, null);
            } catch (RuntimeException failure) {
                return null;
            }
            float[] data = capture.toArray();
            if (data.length == 0) {
                continue;
            }
            chunks.add(new Chunk(part, data));
            total += data.length / FLOATS_PER_VERTEX;
        }
        return chunks.isEmpty() ? null : new TaczBakedGunMesh(chunks, total);
    }

    /** 一段静态子树：顶点已经算好，只有根骨骼的变换每帧会变。 */
    public static final class Chunk {
        private final BedrockPart root;
        private final float[] vertices;
        /** 烘焙瞬间整棵子树里每根骨骼的局部变换，每骨骼 3 个平移 + 3 个旋转。 */
        private final BedrockPart[] bones;
        private final float[] snapshot;

        private Chunk(BedrockPart root, float[] vertices) {
            this.root = root;
            this.vertices = vertices;
            var collected = new ArrayList<BedrockPart>();
            collect(root, collected);
            this.bones = collected.toArray(new BedrockPart[0]);
            this.snapshot = new float[this.bones.length * 6];
            for (int i = 0; i < this.bones.length; i++) {
                snapshot[i * 6] = this.bones[i].x;
                snapshot[i * 6 + 1] = this.bones[i].y;
                snapshot[i * 6 + 2] = this.bones[i].z;
                snapshot[i * 6 + 3] = this.bones[i].xRot;
                snapshot[i * 6 + 4] = this.bones[i].yRot;
                snapshot[i * 6 + 5] = this.bones[i].zRot;
            }
        }

        private static void collect(BedrockPart part, List<BedrockPart> out) {
            out.add(part);
            for (var child : part.children) {
                collect(child, out);
            }
        }

        /**
         * 子树里有没有骨骼相对烘焙时刻动过。
         *
         * <p>烘焙把整棵子树的顶点算成了静态几何，所以只要其中任何一根骨骼变了（拉栓、弹匣、
         * 瞄具、换弹时摆动的枪机），这一块的几何就不成立。此时本帧必须回退到 TaCZ 原路径，
         * 否则动画会被冻结在烘焙那一刻——这是视觉错误，不是崩溃，但同样不可接受。
         *
         * <p>成本是每块每帧若干次浮点比较，相对被省下的 215~270 µs 可以忽略。
         */
        public boolean movedSinceBake() {
            for (int i = 0; i < bones.length; i++) {
                var bone = bones[i];
                if (bone.x != snapshot[i * 6] || bone.y != snapshot[i * 6 + 1]
                        || bone.z != snapshot[i * 6 + 2] || bone.xRot != snapshot[i * 6 + 3]
                        || bone.yRot != snapshot[i * 6 + 4] || bone.zRot != snapshot[i * 6 + 5]) {
                    return true;
                }
            }
            return false;
        }

        public BedrockPart root() {
            return root;
        }

        public float[] vertices() {
            return vertices;
        }

        public boolean staticAtThisFrame() {
            return !movedSinceBake();
        }

        public int vertexCount() {
            return vertices.length / FLOATS_PER_VERTEX;
        }
    }

    /** 把 TaCZ 发射的顶点按固定布局记下来。 */
    private static final class VertexCapture implements VertexConsumer {
        private float[] data = new float[1 << 14];
        private int size;
        private float x;
        private float y;
        private float z;
        private float r = 1f;
        private float g = 1f;
        private float b = 1f;
        private float a = 1f;
        private float u;
        private float v;
        private float lightU;
        private float lightV;
        private float nx;
        private float ny = 1f;
        private float nz;

        float[] toArray() {
            return java.util.Arrays.copyOf(data, size);
        }

        @Override
        public VertexConsumer vertex(double px, double py, double pz) {
            x = (float) px;
            y = (float) py;
            z = (float) pz;
            return this;
        }

        @Override
        public VertexConsumer color(int red, int green, int blue, int alpha) {
            r = red / 255f;
            g = green / 255f;
            b = blue / 255f;
            a = alpha / 255f;
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
            lightU = uu;
            lightV = vv;
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
            if (size + FLOATS_PER_VERTEX > data.length) {
                data = java.util.Arrays.copyOf(data, data.length * 2);
            }
            data[size] = x;
            data[size + 1] = y;
            data[size + 2] = z;
            data[size + 3] = r;
            data[size + 4] = g;
            data[size + 5] = b;
            data[size + 6] = a;
            data[size + 7] = u;
            data[size + 8] = v;
            data[size + 9] = lightU;
            data[size + 10] = lightV;
            data[size + 11] = nx;
            data[size + 12] = ny;
            data[size + 13] = nz;
            size += FLOATS_PER_VERTEX;
        }

        @Override
        public void defaultColor(int red, int green, int blue, int alpha) {
            r = red / 255f;
            g = green / 255f;
            b = blue / 255f;
            a = alpha / 255f;
        }

        @Override
        public void unsetDefaultColor() {
            r = 1f;
            g = 1f;
            b = 1f;
            a = 1f;
        }
    }
}
