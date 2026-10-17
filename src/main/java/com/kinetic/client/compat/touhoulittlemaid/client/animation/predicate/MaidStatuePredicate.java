package com.kinetic.client.compat.touhoulittlemaid.client.animation.predicate;

import com.kinetic.client.animation.predicate.IAnimationPredicate;
import com.kinetic.client.compat.touhoulittlemaid.client.CustomKineticMaidEntity;
import com.kinetic.client.entity.IPreviewEntity;
import com.kinetic.geckolib3.core.PlayState;
import com.kinetic.geckolib3.core.event.predicate.AnimationEvent;
import com.kinetic.molang.runtime.ExpressionEvaluator;
import com.github.tartaricacid.touhoulittlemaid.api.client.render.MaidRenderState;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import static com.kinetic.client.animation.predicate.IAnimationPredicate.playLoopAnimation;

public class MaidStatuePredicate implements IAnimationPredicate<CustomKineticMaidEntity> {
    public static final String[] ANIM_LIST = new String[]{"statue", "garage_kit"};

    @Override
    public PlayState test(AnimationEvent<CustomKineticMaidEntity> event, ExpressionEvaluator<?> evaluator) {
        EntityMaid maid = event.getAnimatableEntity().getEntity();
        if (maid == null || event.getAnimatableEntity() instanceof IPreviewEntity) {
            return PlayState.STOP;
        }
        // 检查女仆是否是雕像或者手办状态，如果是，那么播放对应动画
        if (maid.renderState == MaidRenderState.STATUE) {
            return playLoopAnimation(event, "statue");
        }
        if (maid.renderState == MaidRenderState.GARAGE_KIT) {
            return playLoopAnimation(event, "garage_kit");
        }
        return PlayState.STOP;
    }
}
