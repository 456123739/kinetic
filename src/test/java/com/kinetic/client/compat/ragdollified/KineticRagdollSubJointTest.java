package com.kinetic.client.compat.ragdollified;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 子关节积分的稳定性约束。
 *
 * <p>实机症状是"尸体静止时小腿/前臂抽搐"。旧实现的力矩里含一项
 * {@code rest × 父级线加速度 × inertia}，而父级加速度由相邻帧位置差分求得：
 * 提供方的接触求解与休眠切换会产生毫米级修正，二阶差分把它放大成十几弧度/秒，
 * 于是细分子骨骼自己抖起来。
 *
 * <p>这一组测试钉住两件事：力矩只由重力决定（与任何位置差分无关），以及角速度永远有上限。
 */
class KineticRagdollSubJointTest {
    private static final float GRAVITY_GAIN = 6f;
    private static final float DT = 1f / 60f;

    @Test
    void gravityTorqueIsZeroWhenTheJointHangsStraightDown() {
        var rest = new Vector3f(0f, -1f, 0f);
        var gravity = new Vector3f(0f, -1f, 0f);
        var torque = new Vector3f();
        SubJointDynamics.gravityTorque(rest, gravity, GRAVITY_GAIN, torque);
        assertEquals(0f, torque.length(), 1.0e-6f,
                "静止下垂时不应有持续力矩，否则关节会绕着平衡点一直抖");
    }

    @Test
    void gravityTorqueIsBoundedByTheGain() {
        var rest = new Vector3f(1f, 0f, 0f);
        var gravity = new Vector3f(0f, -1f, 0f);
        var torque = new Vector3f();
        SubJointDynamics.gravityTorque(rest, gravity, GRAVITY_GAIN, torque);
        assertTrue(torque.length() <= GRAVITY_GAIN + 1.0e-4f,
                "力矩不得超过 rest×gravity 的最大模长乘以增益");
    }

    @Test
    void angularVelocityNeverExceedsTheCapEvenWithAbsurdTorque() {
        float velocity = 0f;
        for (int step = 0; step < 120; step++) {
            velocity = SubJointDynamics.stepAngularVelocity(velocity, 10_000f, DT);
            assertTrue(Math.abs(velocity) <= 20f + 1.0e-4f,
                    "第 " + step + " 步角速度 " + velocity + " 越过了上限");
        }
    }

    @Test
    void jointSettlesInsteadOfOscillatingForever() {
        // 单轴钟摆：τ = -k·sin(θ) - c·ω。只在重力项存在时应当收敛到 0 角速度。
        float angle = 0.6f;
        float velocity = 0f;
        float damping = 2.4f;
        float gravity = 8f;
        for (int step = 0; step < 600; step++) {
            float torque = -gravity * (float) Math.sin(angle) - damping * velocity;
            velocity = SubJointDynamics.stepAngularVelocity(velocity, torque, DT);
            angle += velocity * DT;
        }
        assertEquals(0f, velocity, 0.05f, "两秒内必须停下，不能持续摆动");
        assertEquals(0f, angle, 0.05f, "应当收敛到自然下垂方向");
    }

    @Test
    void positionNoiseCannotInjectEnergyIntoTheJoint() {
        // 旧实现把父级加速度投影进力矩；现在没有这个入口，等价于"噪声对力矩没有贡献"。
        // 这里用一个只在重力项下运动的关节跑 300 步，期间注入随机位置噪声（模拟提供方的微修正），
        // 断言角速度仍然收敛。
        var random = new java.util.Random(7);
        var rest = new Vector3f(0.3f, -0.9f, 0.1f).normalize();
        var gravity = new Vector3f(0f, -1f, 0f);
        var torque = new Vector3f();
        var noise = new Vector3f();
        float velocity = 0f;
        float angle = 0.4f;
        for (int step = 0; step < 300; step++) {
            noise.set((float) random.nextGaussian(), (float) random.nextGaussian(),
                    (float) random.nextGaussian()).mul(0.01f);
            var direction = new Vector3f(rest).rotateAxis(angle, 1f, 0f, 0f).add(noise);
            SubJointDynamics.gravityTorque(direction, gravity, GRAVITY_GAIN, torque);
            float dampingTorque = -2.4f * velocity;
            velocity = SubJointDynamics.stepAngularVelocity(velocity, torque.y + dampingTorque, DT);
            angle += velocity * DT;
        }
        assertTrue(Math.abs(velocity) < 0.5f,
                "注入位置噪声后角速度仍然要收敛，实测 " + velocity);
    }
}
