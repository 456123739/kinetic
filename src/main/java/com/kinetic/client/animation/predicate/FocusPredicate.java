package com.kinetic.client.animation.predicate;

import com.kinetic.client.entity.CustomPlayerEntity;
import com.kinetic.client.entity.IPreviewEntity;
import com.kinetic.geckolib3.core.PlayState;
import com.kinetic.geckolib3.core.event.predicate.AnimationEvent;
import com.kinetic.molang.runtime.ExpressionEvaluator;
import org.apache.commons.lang3.StringUtils;

import static com.kinetic.client.animation.predicate.IAnimationPredicate.playLoopAnimation;

public class FocusPredicate implements IAnimationPredicate<CustomPlayerEntity> {
    @Override
    public PlayState test(AnimationEvent<CustomPlayerEntity> event, ExpressionEvaluator<?> evaluator) {
        String focusAnimation = ((IPreviewEntity) event.getAnimatableEntity()).getPreviewInfo().getFocus();
        if (StringUtils.isNoneBlank(focusAnimation)) {
            return playLoopAnimation(event, focusAnimation);
        }
        return PlayState.STOP;
    }
}
