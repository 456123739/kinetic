package com.kinetic.client.animation.predicate;

import com.kinetic.geckolib3.core.PlayState;
import com.kinetic.geckolib3.core.builder.LoopType;
import com.kinetic.geckolib3.core.event.predicate.AnimationEvent;
import com.kinetic.geckolib3.model.AnimatableEntity;
import com.kinetic.molang.runtime.ExpressionEvaluator;
import org.jetbrains.annotations.NotNull;

@FunctionalInterface
public interface IAnimationPredicate<T extends AnimatableEntity<?>> {
    @NotNull
    static <T extends AnimatableEntity<?>> PlayState playAnimation(AnimationEvent<T> event, String animationName, LoopType loopType) {
        event.getCodedController().setAnimation(animationName, loopType);
        return PlayState.CONTINUE;
    }

    @NotNull
    static <P extends AnimatableEntity<?>> PlayState playAnimation(AnimationEvent<P> event, String animationName) {
        event.getCodedController().setAnimation(animationName);
        return PlayState.CONTINUE;
    }

    /**
     * 从 {@code progress}（0~1）处开始播另一段动画。
     *
     * <p>用于"同一动作在两个姿态之间接力"：趴下挥砍播到 60% 时站起来，站姿的挥砍要从 60%
     * 接着播，而不是从第 0 帧重来。新动画的长度通过控制器查（两段长度不一样），
     * 进度按比例映射。
     */
    @NotNull
    static <T extends AnimatableEntity<?>> PlayState playAnimationSeeked(AnimationEvent<T> event,
                                                                        String animationName, float progress) {
        var controller = event.getCodedController();
        if (!animationName.equals(controller.getCurrentAnimationName())) {
            float length = controller.getAnimationLength(animationName);
            if (length > 0.0F && progress > 0.0F) {
                controller.seekAnimation(Math.min(length, progress * length));
            }
        }
        controller.setAnimation(animationName);
        return PlayState.CONTINUE;
    }

    @NotNull
    static <T extends AnimatableEntity<?>> PlayState playLoopAnimation(AnimationEvent<T> event, String animationName) {
        return playAnimation(event, animationName, LoopType.LOOP);
    }

    /**
     * 每个 CodedAnimationController 每个关键帧都会运行一次 AnimationPredicate
     * 这个方法就是你判断动画是否能播放的地方
     */
    PlayState test(AnimationEvent<T> event, ExpressionEvaluator<?> evaluator);
}
