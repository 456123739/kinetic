package com.kinetic.mixin.client.parcool;

import com.alrex.parcool.client.animation.impl.ExaggeratedSideDodgeAnimator;
import com.alrex.parcool.common.action.impl.Dodge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 读取 Parcool「夸张侧闪」动画的方向。
 *
 * <p>{@link ExaggeratedSideDodgeAnimator} 直接继承 {@code Animator}，并不是
 * {@code DodgeAnimator} 的子类，因此 {@link DodgeAnimatorAccessor} 拦不住它。若放任其走
 * 类名回退，会得到模型中不存在的 {@code parcool:exaggerated_side_dodge}，该动作期间玩家
 * 会静止不动。它与 {@code DodgeAnimator} 一样持有 {@code direction} 字段，故单独取用。
 */
@Mixin(ExaggeratedSideDodgeAnimator.class)
public interface ExaggeratedSideDodgeAnimatorAccessor {
    @Accessor(value = "direction", remap = false)
    Dodge.DodgeDirection getDirection();
}
