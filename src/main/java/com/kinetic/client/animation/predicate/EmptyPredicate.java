package com.kinetic.client.animation.predicate;

import com.kinetic.geckolib3.core.PlayState;
import com.kinetic.geckolib3.core.event.predicate.AnimationEvent;
import com.kinetic.geckolib3.model.AnimatableEntity;
import com.kinetic.molang.runtime.ExpressionEvaluator;
import net.minecraft.world.entity.LivingEntity;

public class EmptyPredicate implements IAnimationPredicate<AnimatableEntity<? extends LivingEntity>> {
    public static final EmptyPredicate INSTANCE = new EmptyPredicate();

    @Override
    public PlayState test(AnimationEvent<AnimatableEntity<? extends LivingEntity>> event, ExpressionEvaluator<?> evaluator) {
        return PlayState.STOP;
    }
}
