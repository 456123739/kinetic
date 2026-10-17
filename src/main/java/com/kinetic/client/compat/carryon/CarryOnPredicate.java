package com.kinetic.client.compat.carryon;

import com.kinetic.client.animation.predicate.IAnimationPredicate;
import com.kinetic.client.entity.CustomPlayerEntity;
import com.kinetic.client.entity.IPreviewEntity;
import com.kinetic.geckolib3.core.PlayState;
import com.kinetic.geckolib3.core.event.predicate.AnimationEvent;
import com.kinetic.molang.runtime.ExpressionEvaluator;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;

import static com.kinetic.client.compat.carryon.CarryOnInnerCompat.getCarryOnType;

public class CarryOnPredicate implements IAnimationPredicate<CustomPlayerEntity> {
    @Override
    public PlayState test(AnimationEvent<CustomPlayerEntity> event, ExpressionEvaluator<?> evaluator) {
        Player player = event.getAnimatableEntity().getEntity();
        if (player == null || event.getAnimatableEntity() instanceof IPreviewEntity) {
            return PlayState.STOP;
        }
        if (player.getPose() == Pose.SWIMMING) {
            return PlayState.STOP;
        }
        if (player.getPose() == Pose.FALL_FLYING && player.isFallFlying()) {
            return PlayState.STOP;
        }
        CarryOnInnerCompat.Type carryOnType = getCarryOnType(player);
        switch (carryOnType) {
            case ENTITY -> {
                return IAnimationPredicate.playLoopAnimation(event, "carryon:entity");
            }
            case BLOCK -> {
                return IAnimationPredicate.playLoopAnimation(event, "carryon:block");
            }
            case PLAYER -> {
                return IAnimationPredicate.playLoopAnimation(event, "carryon:player");
            }
        }
        return PlayState.STOP;
    }
}
