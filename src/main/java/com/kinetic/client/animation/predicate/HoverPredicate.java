package com.kinetic.client.animation.predicate;

import com.kinetic.client.entity.CustomPlayerEntity;
import com.kinetic.client.entity.IPreviewEntity;
import com.kinetic.geckolib3.core.PlayState;
import com.kinetic.geckolib3.core.event.predicate.AnimationEvent;
import com.kinetic.molang.runtime.ExpressionEvaluator;
import org.apache.commons.lang3.StringUtils;

import static com.kinetic.client.animation.predicate.IAnimationPredicate.playLoopAnimation;

public class HoverPredicate implements IAnimationPredicate<CustomPlayerEntity> {
    @Override
    public PlayState test(AnimationEvent<CustomPlayerEntity> event, ExpressionEvaluator<?> evaluator) {
        var previewEntity = (IPreviewEntity) event.getAnimatableEntity();
        String hoverAnimation = previewEntity.getPreviewInfo().getHover();
        if (StringUtils.isNoneBlank(hoverAnimation)) {
            previewEntity.setAllowEmitting(true);
            return playLoopAnimation(event, hoverAnimation);
        }
        previewEntity.setAllowEmitting(false);
        event.getCodedController().stopPlayingSounds();
        return PlayState.STOP;
    }
}
