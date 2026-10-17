package com.kinetic.client.compat.realcamera;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CameraBindSolver} 的目标解析与缓存行为。
 *
 * <p>真实相机的绑定配置是用户手点的三个 UV，缺一个就不该硬算——宁可让位给它自己的兜底路径，
 * 也不能把半个结果塞进相机。
 */
class CameraBindSolverTest {
    private static CameraBindGeometry.Quad quad(int bone, float uOffset, float vOffset) {
        return new CameraBindGeometry.Quad(
                new float[]{0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f},
                new float[]{uOffset, vOffset, uOffset + 0.25f, vOffset,
                        uOffset + 0.25f, vOffset + 0.25f, uOffset, vOffset + 0.25f},
                0f, 0f, 1f, bone);
    }

    private static CameraBindSolver solver() {
        // 两块互不重叠的 UV 区域：0.0~0.25 与 0.5~0.75。
        return new CameraBindSolver(CameraBindGeometry.of(new CameraBindGeometry.Quad[]{
                quad(0, 0f, 0f), quad(1, 0.5f, 0.5f)}));
    }

    @Test
    void resolvesEachUvToItsOwnQuad() {
        var solver = solver();
        var plan = solver.plan(new CameraBindSolver.Target(0.1f, 0.1f, 0.6f, 0.6f, 0.1f, 0.2f));
        assertTrue(plan.resolved());
        assertEquals(0, plan.positionQuad());
        assertEquals(1, plan.forwardQuad());
        assertEquals(0, plan.upwardQuad());
    }

    @Test
    void givesUpWhenAnyOfTheThreeUvsMisses() {
        var solver = solver();
        // 位置点在 0.3/0.3，两块面都不覆盖它。
        var plan = solver.plan(new CameraBindSolver.Target(0.3f, 0.3f, 0.6f, 0.6f, 0.1f, 0.2f));
        assertFalse(plan.resolved());
        assertSame(CameraBindSolver.Plan.NONE, plan);
    }

    @Test
    void cachesThePlanSoTheScanRunsOnce() {
        var solver = solver();
        var target = new CameraBindSolver.Target(0.1f, 0.1f, 0.6f, 0.6f, 0.1f, 0.2f);
        assertSame(solver.plan(target), solver.plan(target));
        // 未命中同样要缓存：否则每帧都要把整个模型的 UV 扫一遍。
        var miss = new CameraBindSolver.Target(0.3f, 0.3f, 0.6f, 0.6f, 0.1f, 0.2f);
        assertSame(solver.plan(miss), solver.plan(miss));
    }

    @Test
    void refusesToEvaluateWithoutBonePoses() {
        var solver = solver();
        var target = new CameraBindSolver.Target(0.1f, 0.1f, 0.6f, 0.6f, 0.1f, 0.2f);
        var plan = solver.plan(target);
        assertFalse(solver.evaluate(plan, target, null, new Matrix4f(), new Matrix3f(),
                new Vector3f(), new Vector3f(), new Vector3f()));
    }

    @Test
    void refusesToEvaluateAnUnresolvedPlan() {
        var solver = solver();
        var target = new CameraBindSolver.Target(0.3f, 0.3f, 0.6f, 0.6f, 0.1f, 0.2f);
        assertFalse(solver.evaluate(CameraBindSolver.Plan.NONE, target, null,
                new Matrix4f(), new Matrix3f(),
                new Vector3f(), new Vector3f(), new Vector3f()));
    }
}
