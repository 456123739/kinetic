package com.kinetic.client.compat.ragdollified;

import net.minecraft.util.Mth;
import org.joml.Quaternionf;

/**
 * 布娃娃姿态映射中与外部类型无关的纯计算，便于单独验证。
 *
 * <p>三件事：
 * <ul>
 *   <li><b>大关节常量偏移</b>：{@code C = q_torso(0)⁻¹ · Q_part(0) · L_captured⁻¹}，
 *       以及由它反解局部旋转 {@code L = (q_torso · C)⁻¹ · Q_part}。首帧时
 *       {@code L} 恰好等于捕获到的局部角度，这就是「变成布娃娃的瞬间不跳变」的保证；</li>
 *   <li><b>欧拉角约定</b>：与原生渲染一致，先 Z 再 Y 后 X；</li>
 *   <li><b>关节范围</b>：静止目标取夹取后的值，硬限制放宽到包含死亡瞬间的角度，
 *       因此越界的初始姿态只会被弹簧平滑拉回，不会被硬掰。</li>
 * </ul>
 */
final class KineticRagdollMath {
    private KineticRagdollMath() {
    }

    /** 解出大关节常量偏移，使首帧局部旋转等于捕获角度。 */
    static Quaternionf solveMacroOffset(Quaternionf torsoRotation, Quaternionf partRotation,
                                        float capturedX, float capturedY, float capturedZ,
                                        Quaternionf destination) {
        Quaternionf captured = eulerToQuaternion(capturedX, capturedY, capturedZ, new Quaternionf()).invert();
        return destination.set(torsoRotation).invert().mul(partRotation).mul(captured);
    }

    /** 由常量偏移反解局部旋转：{@code L = (q_torso · C)⁻¹ · Q_part}。 */
    static Quaternionf macroLocal(Quaternionf torsoRotation, Quaternionf offset, Quaternionf partRotation,
                                  Quaternionf destination) {
        return destination.set(torsoRotation).mul(offset).invert().mul(partRotation);
    }

    /** 与原生 {@code PoseStack.RotateZYX} 一致：{@code Rz(z)·Ry(y)·Rx(x)}。 */
    static Quaternionf eulerToQuaternion(float x, float y, float z, Quaternionf destination) {
        return destination.rotationZYX(z, y, x);
    }

    /** 硬限制下界：放宽到包含死亡瞬间的角度，避免首帧被硬掰。 */
    static float limitLower(float captured, float minimum) {
        return Math.min(minimum, captured);
    }

    /** 硬限制上界：放宽到包含死亡瞬间的角度。 */
    static float limitUpper(float captured, float maximum) {
        return Math.max(maximum, captured);
    }
}
