package com.kinetic.client.compat.ragdollified;

import org.joml.Vector3f;

/**
 * 细分子骨骼的积分核心：不依赖布娃娃提供方的任何类型，可在无模组环境下单测。
 *
 * <p><b>为什么单独抽出来</b>：这些数值曾经挂在 {@code KineticRagdollState} 里，而那个类在静态初始化时
 * 就引用 {@code RagdollPart}，在 JUnit 里根本加载不了——于是"关节会不会抖"这类问题只能靠实机肉眼看，
 * 出了问题也无从复现。抽出来之后，力矩与积分都能离线断言。
 */
final class SubJointDynamics {
    /**
     * 角速度上限（弧度/秒）。
     *
     * <p>提供方在接触求解、休眠切换时会有毫米级的瞬时修正；任何基于位置差分的力矩都会把它放大。
     * 这里除了不使用差分项，再给角速度封顶，保证再离谱的力矩也不会把子骨骼甩出去。
     */
    static final float MAX_ANGULAR_SPEED = 20f;

    private SubJointDynamics() {
    }

    /** 单轴角速度推进：{@code ω' = clamp(ω + τ·dt)}。 */
    static float stepAngularVelocity(float velocity, float torque, float dt) {
        float next = velocity + torque * dt;
        if (next > MAX_ANGULAR_SPEED) {
            return MAX_ANGULAR_SPEED;
        }
        if (next < -MAX_ANGULAR_SPEED) {
            return -MAX_ANGULAR_SPEED;
        }
        return next;
    }

    /**
     * 重力下垂力矩：{@code rest × gravityInParent × gain}。
     *
     * <p>休息方向与重力平行时为零，这就是"摆到自然下垂就停住"的平衡点，不需要额外回位弹簧。
     */
    static void gravityTorque(Vector3f restDirection, Vector3f gravityInParent, float gain,
                              Vector3f out) {
        restDirection.cross(gravityInParent, out).mul(gain);
    }
}
