package com.kinetic.client.compat.realcamera;

import com.kinetic.natives.render.BonePoseView;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

/**
 * 把真实相机的一组绑定 UV 解析成「模型上的位置与朝向」。
 *
 * <p>真实相机的绑定配置是三个 UV 点：
 * <ul>
 *   <li>{@code posUV} —— 相机要待的那个点（例如头骨正面、眼睛高度处）；</li>
 *   <li>{@code forwardUV} —— 落在某个面上，取该面的法线作为「前方」；</li>
 *   <li>{@code upwardUV} —— 同上，作为「上方」。</li>
 * </ul>
 * 三个点各自独立，默认配置里 {@code posUV} 与 {@code forwardUV} 就是同一个点（头骨正面），
 * {@code upwardUV} 落在头顶。
 *
 * <p>解析（UV → 面）只在第一次用到时做一次并按目标缓存；此后逐帧只剩几次矩阵乘法。这取代了
 * 真实相机自己那条「每帧重画模型 4~8 遍再反查顶点」的路径。
 *
 * <p>本类只在渲染线程使用；内部的临时向量是可复用的 scratch，不对外暴露。
 */
public final class CameraBindSolver {
    /**
     * 一组绑定 UV。用 record 是为了拿到基于 {@code Float.equals} 的值语义缓存键：配置里的 UV
     * 在一次会话内是常量，因此缓存永远命中。
     */
    public record Target(float posU, float posV,
                         float forwardU, float forwardV,
                         float upwardU, float upwardV) {
    }

    /** 三个 UV 分别命中的面。{@link #NONE} 表示至少有一个点没找到面。 */
    public record Plan(int positionQuad, int forwardQuad, int upwardQuad) {
        public static final Plan NONE = new Plan(-1, -1, -1);

        public boolean resolved() {
            return positionQuad >= 0 && forwardQuad >= 0 && upwardQuad >= 0;
        }
    }

    private final CameraBindGeometry geometry;
    private final Map<Target, Plan> plans = new HashMap<>();

    private final Matrix4f scratchBonePose = new Matrix4f();
    private final Matrix4f scratchCombinedPose = new Matrix4f();
    private final Matrix3f scratchBoneNormal = new Matrix3f();
    private final Matrix3f scratchCombinedNormal = new Matrix3f();
    private final Vector3f scratchLocal = new Vector3f();
    private final Vector3f scratchNormal = new Vector3f();

    public CameraBindSolver(CameraBindGeometry geometry) {
        this.geometry = geometry;
    }

    public CameraBindGeometry geometry() {
        return geometry;
    }

    /** 解析（并缓存）一组绑定 UV 命中的面。 */
    public Plan plan(Target target) {
        return plans.computeIfAbsent(target, this::resolve);
    }

    private Plan resolve(Target target) {
        int position = geometry.findQuad(target.posU(), target.posV());
        if (position < 0) {
            return Plan.NONE;
        }
        int forward = geometry.findQuad(target.forwardU(), target.forwardV());
        if (forward < 0) {
            return Plan.NONE;
        }
        int upward = geometry.findQuad(target.upwardU(), target.upwardV());
        if (upward < 0) {
            return Plan.NONE;
        }
        return new Plan(position, forward, upward);
    }

    /**
     * 用当前骨骼姿态求出绑定位置与朝向。
     *
     * <p>{@code modelToSpace} / {@code normalToSpace} 是「模型局部 → 目标空间」的姿态矩阵；对玩家
     * 来说是实体局部空间（真实相机自己会再加实体位置），对布娃娃来说是世界空间。
     *
     * @return 是否成功；任一环节退化（面找不到位置、法线为零、骨骼下标越界）都返回 {@code false}，
     *         调用方应放弃这个目标而不是塞一个 NaN 给真实相机
     */
    public boolean evaluate(Plan plan, Target target, BonePoseView bones,
                            Matrix4f modelToSpace, Matrix3f normalToSpace,
                            Vector3f outPosition, Vector3f outForward, Vector3f outUpward) {
        if (!plan.resolved() || bones == null) {
            return false;
        }
        var positionQuad = geometry.quad(plan.positionQuad());
        var forwardQuad = geometry.quad(plan.forwardQuad());
        var upwardQuad = geometry.quad(plan.upwardQuad());
        if (!boneInRange(bones, positionQuad.boneIndex())
                || !boneInRange(bones, forwardQuad.boneIndex())
                || !boneInRange(bones, upwardQuad.boneIndex())) {
            return false;
        }

        if (geometry.positionAt(positionQuad, target.posU(), target.posV(), scratchLocal) == null) {
            return false;
        }
        bones.getPose(positionQuad.boneIndex(), scratchBonePose);
        scratchCombinedPose.set(modelToSpace).mul(scratchBonePose);
        scratchCombinedPose.transformPosition(scratchLocal);
        outPosition.set(scratchLocal);

        if (!normalOf(forwardQuad, bones, normalToSpace, outForward)) {
            return false;
        }
        if (!normalOf(upwardQuad, bones, normalToSpace, outUpward)) {
            return false;
        }
        return finite(outPosition) && finite(outForward) && finite(outUpward);
    }

    private boolean normalOf(CameraBindGeometry.Quad quad, BonePoseView bones,
                             Matrix3f normalToSpace, Vector3f dest) {
        if (geometry.normalAt(quad, scratchNormal) == null) {
            return false;
        }
        bones.getNormal(quad.boneIndex(), scratchBoneNormal);
        scratchCombinedNormal.set(normalToSpace).mul(scratchBoneNormal);
        scratchCombinedNormal.transform(scratchNormal, dest);
        return finite(dest);
    }

    private static boolean boneInRange(BonePoseView bones, int boneIndex) {
        return boneIndex >= 0 && boneIndex < bones.getBoneCount();
    }

    private static boolean finite(Vector3f value) {
        return Float.isFinite(value.x) && Float.isFinite(value.y) && Float.isFinite(value.z);
    }
}
