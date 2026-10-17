package com.kinetic.client.compat.realcamera;

import com.kinetic.natives.render.NativeBakedModel;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * 原生烘焙模型的一张「UV → 几何」索引表。
 *
 * <p><b>为什么需要它</b>：真实相机（Real Camera）的绑定配置是用户在「模型视图」界面里点出来的
 * 三个 UV 点（位置、前方、上方）。它自己那一套做法是<b>把相机实体离屏重画 4~8 遍</b>、用一个
 * 捕获顶点的 {@code MultiBufferSource} 接住顶点，再在顶点缓冲里按 UV 多边形反查图元。这条路径
 * 又贵又脆：每帧多出 4~8 次模型烘焙与提交；渲染上下文与正常帧不一致；缓存一热就跳过全量搜索；
 * 而且只要 KINETIC 那一遍渲染因为任何原因提前返回，捕获到的顶点数就是 0，绑定直接判失败。
 *
 * <p>KINETIC 不需要走那条路：模型是自己烘焙的，几何就在手边。这里把
 * {@link NativeBakedModel} 的立方体/面数据抽成一张扁平表，于是「UV 点落在哪个面、面内哪个位置、
 * 面朝哪」全部可以离线算出来，逐帧只剩一次矩阵乘法。
 *
 * <p><b>坐标系</b>：表里的位置是<b>模型局部空间</b>（方块为单位，X 已按 Bedrock 约定镜像，见
 * {@code GeoBuilder}）。要变成实体局部坐标还要乘上「模型→实体」姿态矩阵与所在骨骼的姿态矩阵。
 *
 * <p><b>UV 约定</b>：与真实相机的 {@code TargetConfig} 一致，是 {@code [0,1]} 的归一化贴图坐标，
 * 与 {@code NativeBakedModel} 返回的 UV 同一套（{@code GeoBuilder} 已按贴图尺寸归一化）。
 *
 * <p>本类不做任何渲染，也不持有原生资源；它只是从已经烘焙好的模型里读一份只读快照。
 */
public final class CameraBindGeometry {
    /** 一个面是四边形：四个角点、四组 UV。 */
    public static final int CORNER_COUNT = 4;
    /** 原生烘焙把立方体按「是否裁剪 × 是否透明」分成四个互不相交的分区。 */
    private static final int PARTITION_COUNT = 4;

    /**
     * 判断 UV 是否落在面内时的容差。
     *
     * <p>用户在界面里点是按像素取的，落点常常正好压在面的边上；容差太小会让「点在边线上」判成
     * 未命中，而真实相机自己那份实现（{@code java.awt.Polygon} 含边界）是会命中的。
     */
    private static final float UV_EPSILON = 1.0e-5f;

    /**
     * 一个面。
     *
     * @param positions 四个角点的模型局部坐标，长度 {@code CORNER_COUNT * 3}
     * @param uvs       四个角点的归一化 UV，长度 {@code CORNER_COUNT * 2}，与 {@code positions} 一一对应
     * @param normalX   烘焙时算好的面法线（模型局部空间）
     * @param boneIndex 该面所属骨骼在 {@code GeoModel#sortedBones()} 里的下标
     */
    public record Quad(float[] positions, float[] uvs,
                       float normalX, float normalY, float normalZ,
                       int boneIndex) {
    }

    private final Quad[] quads;
    /** 每个面在 UV 空间的外接矩形，用来在 {@link #findQuad} 里做常数级快速排除。 */
    private final float[] minU;
    private final float[] maxU;
    private final float[] minV;
    private final float[] maxV;

    private CameraBindGeometry(Quad[] quads) {
        this.quads = quads;
        this.minU = new float[quads.length];
        this.maxU = new float[quads.length];
        this.minV = new float[quads.length];
        this.maxV = new float[quads.length];
        for (int index = 0; index < quads.length; index++) {
            float[] uvs = quads[index].uvs();
            float lo = Float.POSITIVE_INFINITY;
            float hi = Float.NEGATIVE_INFINITY;
            float loV = Float.POSITIVE_INFINITY;
            float hiV = Float.NEGATIVE_INFINITY;
            for (int corner = 0; corner < CORNER_COUNT; corner++) {
                float u = uvs[corner * 2];
                float v = uvs[corner * 2 + 1];
                lo = Math.min(lo, u);
                hi = Math.max(hi, u);
                loV = Math.min(loV, v);
                hiV = Math.max(hiV, v);
            }
            minU[index] = lo;
            maxU[index] = hi;
            minV[index] = loV;
            maxV[index] = hiV;
        }
    }

    /**
     * 从一个已烘焙的原生模型里抽出全部面。
     *
     * <p>只在「某个绑定目标第一次需要解析」时调用一次，结果按模型实例缓存（见
     * {@link RealCameraBindingInner}）。调用会做若干次 JNI 查询（每个骨骼每个分区一次），
     * 但完全避开逐帧路径。
     */
    public static CameraBindGeometry extract(NativeBakedModel bakedModel) {
        var info = bakedModel.getInfo();
        int boneCount = info.boneCount();
        if (boneCount <= 0) {
            return new CameraBindGeometry(new Quad[0]);
        }
        var boneInfos = bakedModel.getBoneInfo(0, boneCount);
        List<Quad> quads = new ArrayList<>();
        for (int bone = 0; bone < boneCount; bone++) {
            var boneInfo = boneInfos[bone];
            for (int partition = 0; partition < PARTITION_COUNT; partition++) {
                int cubeCount = cubeCount(boneInfo, partition);
                if (cubeCount <= 0) {
                    continue;
                }
                // 一次把该骨骼该分区的立方体全取回来，避免逐立方体一次 JNI。
                for (var cube : bakedModel.getCubeData(bone, partition, 0, cubeCount)) {
                    appendCube(quads, cube, bone);
                }
            }
        }
        return new CameraBindGeometry(quads.toArray(new Quad[0]));
    }

    private static int cubeCount(NativeBakedModel.BoneInfo boneInfo, int partition) {
        return switch (partition) {
            case 0 -> boneInfo.cutout().cubeCount();
            case 1 -> boneInfo.cutoutNoCulling().cubeCount();
            case 2 -> boneInfo.translucent().cubeCount();
            default -> boneInfo.translucentCulling().cubeCount();
        };
    }

    private static void appendCube(List<Quad> out, NativeBakedModel.CubeData cube, int bone) {
        var positions = cube.positions();
        for (var quad : cube.quads()) {
            float[] corners = new float[CORNER_COUNT * 3];
            int[] indices = {quad.vertex0(), quad.vertex1(), quad.vertex2(), quad.vertex3()};
            boolean degenerate = false;
            for (int corner = 0; corner < CORNER_COUNT; corner++) {
                int vertex = indices[corner];
                if (vertex < 0 || vertex >= positions.length) {
                    degenerate = true;
                    break;
                }
                corners[corner * 3] = positions[vertex].x;
                corners[corner * 3 + 1] = positions[vertex].y;
                corners[corner * 3 + 2] = positions[vertex].z;
            }
            if (degenerate) {
                continue;
            }
            var uv = quad.uv();
            if (uv.length < CORNER_COUNT) {
                continue;
            }
            float[] uvCorners = new float[CORNER_COUNT * 2];
            for (int corner = 0; corner < CORNER_COUNT; corner++) {
                uvCorners[corner * 2] = uv[corner].x;
                uvCorners[corner * 2 + 1] = uv[corner].y;
            }
            out.add(new Quad(corners, uvCorners,
                    quad.normal().x, quad.normal().y, quad.normal().z, bone));
        }
    }

    public int quadCount() {
        return quads.length;
    }

    /** 直接用一组面构造。仅用于单元测试与离线分析：正常路径一律走 {@link #extract}。 */
    static CameraBindGeometry of(Quad[] quads) {
        return new CameraBindGeometry(quads);
    }

    public Quad quad(int index) {
        return quads[index];
    }

    /**
     * 找到 UV 点落在的第一个面。
     *
     * <p>顺序是确定的（骨骼下标 → 分区 → 立方体 → 面），因此同一个模型与同一组 UV 每次都会命中
     * 同一个面；这一点很重要，否则相机绑定会在两帧之间跳到另一个面上。
     *
     * <p>分区顺序与渲染时的提交顺序一致（裁剪 → 不裁剪 → 透明 → 透明裁剪），所以模型重复使用
     * 同一块贴图区域时，命中的是实际会被画出来的那个面。
     *
     * @return 面下标；没有面包含该 UV 时返回 {@code -1}
     */
    public int findQuad(float u, float v) {
        for (int index = 0; index < quads.length; index++) {
            if (u < minU[index] - UV_EPSILON || u > maxU[index] + UV_EPSILON
                    || v < minV[index] - UV_EPSILON || v > maxV[index] + UV_EPSILON) {
                continue;
            }
            if (contains(quads[index], u, v)) {
                return index;
            }
        }
        return -1;
    }

    /** UV 空间的点包含判定：把四边形拆成两个三角形，任一个含该点即算命中。 */
    static boolean contains(Quad quad, float u, float v) {
        float[] uv = quad.uvs();
        if (containsTriangle(uv, 0, 1, 2, u, v)) {
            return true;
        }
        return containsTriangle(uv, 0, 2, 3, u, v);
    }

    private static boolean containsTriangle(float[] uv, int a, int b, int c, float u, float v) {
        float ax = uv[a * 2];
        float ay = uv[a * 2 + 1];
        float bx = uv[b * 2];
        float by = uv[b * 2 + 1];
        float cx = uv[c * 2];
        float cy = uv[c * 2 + 1];
        float area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
        if (Math.abs(area) <= UV_EPSILON * UV_EPSILON) {
            // 退化三角形：单看它判不了，交给另一个三角形；两个都退化时下面的边判定兜底。
            return containsDegenerate(ax, ay, bx, by, cx, cy, u, v);
        }
        float sign = area > 0 ? 1f : -1f;
        float w0 = ((bx - u) * (cy - v) - (by - v) * (cx - u)) * sign;
        float w1 = ((cx - u) * (ay - v) - (cy - v) * (ax - u)) * sign;
        float w2 = ((ax - u) * (by - v) - (ay - v) * (bx - u)) * sign;
        float tolerance = Math.abs(area) * UV_EPSILON;
        return w0 >= -tolerance && w1 >= -tolerance && w2 >= -tolerance;
    }

    /** 三角形退化成线段时的兜底：点落在该线段上即算命中。 */
    private static boolean containsDegenerate(float ax, float ay, float bx, float by,
                                              float cx, float cy, float u, float v) {
        return onSegment(ax, ay, bx, by, u, v)
                || onSegment(bx, by, cx, cy, u, v)
                || onSegment(cx, cy, ax, ay, u, v);
    }

    private static boolean onSegment(float ax, float ay, float bx, float by, float u, float v) {
        float dx = bx - ax;
        float dy = by - ay;
        float lengthSqr = dx * dx + dy * dy;
        if (lengthSqr <= UV_EPSILON * UV_EPSILON) {
            return Math.abs(u - ax) <= UV_EPSILON && Math.abs(v - ay) <= UV_EPSILON;
        }
        float t = ((u - ax) * dx + (v - ay) * dy) / lengthSqr;
        if (t < -UV_EPSILON || t > 1f + UV_EPSILON) {
            return false;
        }
        float px = ax + t * dx;
        float py = ay + t * dy;
        return Math.abs(u - px) <= UV_EPSILON && Math.abs(v - py) <= UV_EPSILON;
    }

    /**
     * 面内某 UV 处的模型局部坐标。
     *
     * <p>与真实相机自己的 {@code VertexData.position} 同构：在四边形的 0/1/2 三个角上做重心插值。
     * 立方体的一个面在 UV 空间与位置空间都是平行四边形，仿射映射下换用哪个三角形结果都一样，
     * 所以这里保持与对方一致，便于逐像素比对。
     *
     * @return {@code dest}；面的 UV 退化导致无法插值时返回 {@code null}
     */
    @Nullable
    public Vector3f positionAt(Quad quad, float u, float v, Vector3f dest) {
        float[] uv = quad.uvs();
        float[] weights = barycentric(uv, u, v);
        if (weights == null) {
            return null;
        }
        float[] positions = quad.positions();
        dest.set(0f, 0f, 0f);
        for (int corner = 0; corner < CORNER_COUNT; corner++) {
            float weight = weights[corner];
            if (weight == 0f) {
                continue;
            }
            dest.x += positions[corner * 3] * weight;
            dest.y += positions[corner * 3 + 1] * weight;
            dest.z += positions[corner * 3 + 2] * weight;
        }
        return dest;
    }

    /**
     * 三个角点上的重心权重。
     *
     * <p>先试包含该点的那个三角形；两个都不包含（点恰在容差边缘）时退回 0/1/2，与真实相机一致。
     */
    @Nullable
    private static float[] barycentric(float[] uv, float u, float v) {
        if (containsTriangle(uv, 0, 1, 2, u, v)) {
            return weights(uv, 0, 1, 2, u, v);
        }
        if (containsTriangle(uv, 0, 2, 3, u, v)) {
            // weights 已经按真实角点下标写入数组，不需要再折算角序。
            return weights(uv, 0, 2, 3, u, v);
        }
        return weights(uv, 0, 1, 2, u, v);
    }

    @Nullable
    private static float[] weights(float[] uv, int a, int b, int c, float u, float v) {
        float ax = uv[a * 2];
        float ay = uv[a * 2 + 1];
        float bx = uv[b * 2];
        float by = uv[b * 2 + 1];
        float cx = uv[c * 2];
        float cy = uv[c * 2 + 1];
        float denominator = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
        if (Math.abs(denominator) <= UV_EPSILON * UV_EPSILON) {
            return null;
        }
        float beta = ((u - ax) * (cy - ay) - (v - ay) * (cx - ax)) / denominator;
        float gamma = ((bx - ax) * (v - ay) - (by - ay) * (u - ax)) / denominator;
        float alpha = 1f - beta - gamma;
        float[] result = new float[CORNER_COUNT];
        result[a] = alpha;
        result[b] = beta;
        result[c] = gamma;
        return result;
    }

    /**
     * 面的几何法线（模型局部空间）。
     *
     * <p>与真实相机自己的 {@code VertexData.normal} 同构：用 0/1/2 三个角点做叉积再归一化，
     * 而不是读烘焙时存的法线。两者的差别只在烘焙法线被刻意掰过的面上，这里选与对方一致。
     *
     * @return {@code dest}；面退化（面积为零）时返回 {@code null}
     */
    @Nullable
    public Vector3f normalAt(Quad quad, Vector3f dest) {
        float[] p = quad.positions();
        float abx = p[3] - p[0];
        float aby = p[4] - p[1];
        float abz = p[5] - p[2];
        float acx = p[6] - p[0];
        float acy = p[7] - p[1];
        float acz = p[8] - p[2];
        float nx = aby * acz - abz * acy;
        float ny = abz * acx - abx * acz;
        float nz = abx * acy - aby * acx;
        float lengthSqr = nx * nx + ny * ny + nz * nz;
        if (!(lengthSqr > 1.0e-12f)) {
            return null;
        }
        float inverse = (float) (1.0 / Math.sqrt(lengthSqr));
        dest.set(nx * inverse, ny * inverse, nz * inverse);
        return dest;
    }
}
