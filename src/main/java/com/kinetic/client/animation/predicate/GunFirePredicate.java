package com.kinetic.client.animation.predicate;

import com.kinetic.client.animation.SlideLock;
import com.kinetic.client.compat.swarfare.SWarfareCompat;
import com.kinetic.client.compat.tacz.TACZCompat;
import com.kinetic.client.entity.CustomHumanoidEntity;
import com.kinetic.client.entity.IPreviewEntity;
import com.kinetic.geckolib3.core.PlayState;
import com.kinetic.geckolib3.core.event.predicate.AnimationEvent;
import com.kinetic.molang.runtime.ExpressionEvaluator;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

public class GunFirePredicate implements IAnimationPredicate<CustomHumanoidEntity<?>> {
    @Override
    public PlayState test(AnimationEvent<CustomHumanoidEntity<?>> event, ExpressionEvaluator<?> evaluator) {
        LivingEntity entity = event.getAnimatableEntity().getEntity();
        if (entity == null || event.getAnimatableEntity() instanceof IPreviewEntity) {
            return PlayState.STOP;
        }
        // 滑铲锁定：开火/换弹/近战这些一次性动画一律不播，手臂保持静止持枪。
        // 枪口火光、抛壳、弹道都不走这里，射击本身不受影响。
        if (SlideLock.suppressesOneShot(entity)) {
            return PlayState.STOP;
        }
        boolean reloading = TACZCompat.isReloading(entity) || SWarfareCompat.isReloading(entity)
                || isReloadAnimation(event.getCodedController().getCurrentAnimationName());
        if (mayPlayOneShot(entity.swinging, entity.isUsingItem(), reloading)) {
            ItemStack mainHandItem = entity.getItemInHand(InteractionHand.MAIN_HAND);
            PlayState result = TACZCompat.playGunOnceAnimation(mainHandItem, event);
            if (result == null) {
                result = SWarfareCompat.playGunOnceAnimation(mainHandItem, event);
            }
            return Objects.requireNonNullElse(result, PlayState.STOP);
        }
        return PlayState.STOP;
    }

    /**
     * 换弹优先级高于挥动/使用互斥门。
     *
     * <p>TaCZ 的本地换弹事件先于服务端同步到达；这一帧可能同时存在 swinging 或
     * isUsingItem。若沿用普通开火动画的门，本地起手和同步后的换弹都会被整个吞掉。
     * 普通开火/近战仍保持原互斥，只有真实换弹信号放行。
     */
    public static boolean isReloadAnimation(@Nullable String animation) {
        return animation != null && animation.startsWith("tac:reload:");
    }

    static boolean mayPlayOneShot(boolean swinging, boolean usingItem, boolean reloading) {
        return reloading || (!swinging && !usingItem);
    }

    public static boolean available() {
        return TACZCompat.isInstalled() || SWarfareCompat.isInstalled();
    }
}
