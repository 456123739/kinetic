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
        if (!entity.swinging && !entity.isUsingItem()) {
            ItemStack mainHandItem = entity.getItemInHand(InteractionHand.MAIN_HAND);
            PlayState result = TACZCompat.playGunOnceAnimation(mainHandItem, event);
            if (result == null) {
                result = SWarfareCompat.playGunOnceAnimation(mainHandItem, event);
            }
            return Objects.requireNonNullElse(result, PlayState.STOP);
        }
        return PlayState.STOP;
    }

    public static boolean available() {
        return TACZCompat.isInstalled() || SWarfareCompat.isInstalled();
    }
}
