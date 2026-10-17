package com.kinetic.client.animation.predicate;

import com.kinetic.client.entity.CustomVehicleEntity;
import com.kinetic.geckolib3.core.PlayState;
import com.kinetic.geckolib3.core.event.predicate.AnimationEvent;
import com.kinetic.molang.runtime.ExpressionEvaluator;
import net.minecraft.world.entity.Entity;

import static com.kinetic.client.animation.predicate.IAnimationPredicate.playAnimation;

public class VehicleMainPredicate implements IAnimationPredicate<CustomVehicleEntity> {
    public static final String[] ANIM_LIST = new String[]{"water", "ground", "fly"};

    @Override
    public PlayState test(AnimationEvent<CustomVehicleEntity> event, ExpressionEvaluator<?> evaluator) {
        Entity entity = event.getAnimatableEntity().getEntity();
        if (entity == null) {
            return PlayState.STOP;
        }
        if (entity.isInWater()) {
            return playAnimation(event, "water");
        }
        if (entity.onGround()) {
            return playAnimation(event, "ground");
        }
        return playAnimation(event, "fly");
    }
}
