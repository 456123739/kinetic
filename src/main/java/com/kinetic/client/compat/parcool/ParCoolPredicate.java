package com.kinetic.client.compat.parcool;

import com.kinetic.client.animation.SlideLock;
import com.kinetic.client.animation.predicate.IAnimationPredicate;
import com.kinetic.client.entity.CustomPlayerEntity;
import com.kinetic.client.entity.IPreviewEntity;
import com.kinetic.geckolib3.core.PlayState;
import com.kinetic.geckolib3.core.event.predicate.AnimationEvent;
import com.kinetic.molang.runtime.ExpressionEvaluator;
import net.minecraft.world.entity.player.Player;

import static com.kinetic.client.animation.predicate.IAnimationPredicate.playAnimation;

public class ParCoolPredicate implements IAnimationPredicate<CustomPlayerEntity> {
    @Override
    public PlayState test(AnimationEvent<CustomPlayerEntity> event, ExpressionEvaluator<?> evaluator) {
        Player player = event.getAnimatableEntity().getEntity();
        if (player == null || event.getAnimatableEntity() instanceof IPreviewEntity) {
            return null;
        }
        String parCoolAnimation = ParCoolCompat.getAnimation(player);
        boolean modelHasSlide = event.getAnimatableEntity().getAnimation(SlideLock.SLIDE_ANIMATION) != null;
        // 观测本帧的滑铲状态；持枪滑铲会进入锁定，后续所有频道都以它为准。
        boolean locked = SlideLock.observe(player, parCoolAnimation, modelHasSlide,
                SlideLock.isGunInMainHand(player));
        if (parCoolAnimation != null) {
            if (event.getAnimatableEntity().getAnimation(parCoolAnimation) != null) {
                return playAnimation(event, parCoolAnimation);
            }
        }
        if (locked) {
            // ParCool 已经停报滑铲（动作结束、换成了 CrawlAnimator）——锁定期间继续把滑铲喂给
            // 同一个控制器。动画名和循环类型都没变，播放头不会重播，只是继续往下走。
            return playAnimation(event, SlideLock.SLIDE_ANIMATION);
        }
        return PlayState.STOP;
    }
}
