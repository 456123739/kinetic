package com.kinetic.client.compat.touhoulittlemaid.client.animation.predicate;

import com.kinetic.client.animation.predicate.IAnimationPredicate;
import com.kinetic.client.compat.touhoulittlemaid.client.CustomKineticMaidEntity;
import com.kinetic.client.entity.IPreviewEntity;
import com.kinetic.geckolib3.core.PlayState;
import com.kinetic.geckolib3.core.event.predicate.AnimationEvent;
import com.kinetic.molang.runtime.ExpressionEvaluator;

import static com.kinetic.client.animation.predicate.IAnimationPredicate.playAnimation;

public class MaidRoulettePredicate implements IAnimationPredicate<CustomKineticMaidEntity> {
    @Override
    public PlayState test(AnimationEvent<CustomKineticMaidEntity> event, ExpressionEvaluator<?> evaluator) {
        CustomKineticMaidEntity animatable = event.getAnimatableEntity();
        if (animatable instanceof IPreviewEntity guiEntity) {
            if (guiEntity.getPreviewInfo().hasPreview()) {
                return IAnimationPredicate.playLoopAnimation(event, guiEntity.getPreviewInfo().getPreview());
            }
            return PlayState.STOP;
        }

        if (animatable.isRouletteAnimPlaying()) {
            if (animatable.shouldResetRouletteAnim()) {
                animatable.clearRouletteAnimDirty();
                event.getCodedController().indicateReload();
            }
            return playAnimation(event, animatable.getRouletteAnim());
        }
        return PlayState.STOP;
    }
}
