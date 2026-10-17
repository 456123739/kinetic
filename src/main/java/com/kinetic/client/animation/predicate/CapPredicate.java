package com.kinetic.client.animation.predicate;

import com.kinetic.client.entity.CustomPlayerEntity;
import com.kinetic.client.entity.IPreviewEntity;
import com.kinetic.geckolib3.core.PlayState;
import com.kinetic.geckolib3.core.event.predicate.AnimationEvent;
import com.kinetic.molang.runtime.ExpressionEvaluator;

import static com.kinetic.client.animation.predicate.IAnimationPredicate.playAnimation;
import static com.kinetic.client.animation.predicate.IAnimationPredicate.playLoopAnimation;

public class CapPredicate implements IAnimationPredicate<CustomPlayerEntity> {
    @Override
    public PlayState test(AnimationEvent<CustomPlayerEntity> event, ExpressionEvaluator<?> evaluator) {
        CustomPlayerEntity animatable = event.getAnimatableEntity();
        if (animatable instanceof IPreviewEntity guiEntity) {
            if (guiEntity.getPreviewInfo().hasPreview()) {
                return playLoopAnimation(event, guiEntity.getPreviewInfo().getPreview());
            }
            return PlayState.STOP;
        }

        if (animatable.isPlayingExtraAnimation()) {
            if (animatable.shouldResetExtraAnimation()) {
                animatable.clearExtraAnimationDirty();
                event.getCodedController().indicateReload();
            }
            return playAnimation(event, animatable.getExtraAnimationName());
        }
        return PlayState.STOP;
    }
}
