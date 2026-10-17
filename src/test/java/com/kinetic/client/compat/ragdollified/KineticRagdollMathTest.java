package com.kinetic.client.compat.ragdollified;

import com.kinetic.geckolib3.core.util.MathUtil;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 布娃娃姿态映射的纯计算验证。
 *
 * <p>重点是「零跳变」：大关节常量偏移必须让布娃娃的第一帧局部旋转精确等于死亡瞬间捕获的角度。
 * 只要这一点成立，肘、膝等关节就不会在玩家变成布娃娃的瞬间被拉直或弹回。
 */
class KineticRagdollMathTest {
    private static final float EPSILON = 1.0e-4f;

    /** 随机姿态下，解出的常量偏移都必须让首帧局部旋转回到捕获角度。 */
    @Test
    void macroOffsetReproducesCapturedPoseExactly() {
        var random = new Random(20261006L);
        for (int iteration = 0; iteration < 2000; iteration++) {
            var torso = randomQuaternion(random);
            var part = randomQuaternion(random);
            float capturedX = random.nextFloat() * 2f - 1f;
            float capturedY = random.nextFloat() * 2f - 1f;
            float capturedZ = random.nextFloat() * 2f - 1f;

            var offset = KineticRagdollMath.solveMacroOffset(torso, part, capturedX, capturedY, capturedZ,
                    new Quaternionf());
            var local = KineticRagdollMath.macroLocal(torso, offset, part, new Quaternionf());

            var expected = KineticRagdollMath.eulerToQuaternion(capturedX, capturedY, capturedZ,
                    new Quaternionf());
            assertQuaternionEquals(expected, local, "iteration " + iteration);
        }
    }

    /** 物理朝向变化后，局部旋转必须真的跟着变（常量偏移不能把姿态固定死）。 */
    @Test
    void macroLocalFollowsPhysics() {
        var torso = new Quaternionf().rotationY(0.7f);
        var part = new Quaternionf().rotationY(0.7f);
        var offset = KineticRagdollMath.solveMacroOffset(torso, part, 0.3f, 0f, 0f, new Quaternionf());

        var moved = new Quaternionf().rotationY(1.4f);
        var local = KineticRagdollMath.macroLocal(torso, offset, moved, new Quaternionf());
        // L = L_captured · Q_part(0)⁻¹ · q_torso(0) · q_torso⁻¹ · Q_part
        var expected = KineticRagdollMath.eulerToQuaternion(0.3f, 0f, 0f, new Quaternionf())
                .mul(new Quaternionf(torso).invert().mul(moved));

        assertQuaternionEquals(expected, local, "moved part");
        assertTrue(Math.abs(local.w - expected.w) < EPSILON);
    }

    /** 欧拉角约定必须与原生渲染、以及 KINETIC 自己的换算工具一致。 */
    @Test
    void eulerConventionMatchesNativeZyx() {
        var random = new Random(7L);
        for (int iteration = 0; iteration < 500; iteration++) {
            float x = random.nextFloat() * 3f - 1.5f;
            float y = random.nextFloat() * 3f - 1.5f;
            float z = random.nextFloat() * 3f - 1.5f;

            var quaternion = KineticRagdollMath.eulerToQuaternion(x, y, z, new Quaternionf());
            var reference = MathUtil.getQuatFromEulerZYX(new Vector3f(x, y, z));
            assertQuaternionEquals(reference, quaternion, "euler " + x + "," + y + "," + z);

            // 反解回来必须还是同一组角度。
            var back = MathUtil.getEulerAnglesZYX(quaternion, new Vector3f());
            assertEquals(x, back.x, EPSILON);
            assertEquals(y, back.y, EPSILON);
            assertEquals(z, back.z, EPSILON);
        }
    }

    /** 范围内的死亡姿态：硬限制就是解剖范围。 */
    @Test
    void limitKeepsInRangePoseAsIs() {
        float minimum = (float) Math.toRadians(-12);
        float maximum = (float) Math.toRadians(145);
        float captured = (float) Math.toRadians(90);

        assertEquals(minimum, KineticRagdollMath.limitLower(captured, minimum), EPSILON);
        assertEquals(maximum, KineticRagdollMath.limitUpper(captured, maximum), EPSILON);
    }

    /**
     * 越界的死亡姿态：首帧必须被完整保留（硬限制放宽到包含它），此后由重力与阻尼自然收敛，
     * 而不是被弹簧拉回死亡姿势。
     */
    @Test
    void limitPreservesOutOfRangeStart() {
        float minimum = (float) Math.toRadians(-12);
        float maximum = (float) Math.toRadians(145);
        float captured = (float) Math.toRadians(200);

        assertEquals(minimum, KineticRagdollMath.limitLower(captured, minimum), EPSILON);
        assertEquals(captured, KineticRagdollMath.limitUpper(captured, maximum), EPSILON);

        // 下界方向同样成立。
        float hyperExtended = (float) Math.toRadians(-60);
        assertEquals(hyperExtended, KineticRagdollMath.limitLower(hyperExtended, minimum), EPSILON);
        assertEquals(maximum, KineticRagdollMath.limitUpper(hyperExtended, maximum), EPSILON);
    }

    /** 铰链关节的非转动轴：下界与上界都等于捕获角度，等于锁死。 */
    @Test
    void hingeAxisIsLocked() {
        float captured = (float) Math.toRadians(37);
        assertEquals(captured, KineticRagdollMath.limitLower(captured, captured), EPSILON);
        assertEquals(captured, KineticRagdollMath.limitUpper(captured, captured), EPSILON);
    }

    private static Quaternionf randomQuaternion(Random random) {
        return new Quaternionf(random.nextFloat() * 2f - 1f, random.nextFloat() * 2f - 1f,
                random.nextFloat() * 2f - 1f, random.nextFloat() * 2f - 1f).normalize();
    }

    private static void assertQuaternionEquals(Quaternionf expected, Quaternionf actual, String message) {
        // q 与 -q 表示同一个旋转。
        float dot = expected.x * actual.x + expected.y * actual.y
                + expected.z * actual.z + expected.w * actual.w;
        float sign = dot < 0f ? -1f : 1f;
        assertEquals(expected.x * sign, actual.x, EPSILON, message);
        assertEquals(expected.y * sign, actual.y, EPSILON, message);
        assertEquals(expected.z * sign, actual.z, EPSILON, message);
        assertEquals(expected.w * sign, actual.w, EPSILON, message);
    }
}
