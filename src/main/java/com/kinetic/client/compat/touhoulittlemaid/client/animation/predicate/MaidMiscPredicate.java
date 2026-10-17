package com.kinetic.client.compat.touhoulittlemaid.client.animation.predicate;

import com.kinetic.client.animation.predicate.IAnimationPredicate;
import com.kinetic.client.compat.touhoulittlemaid.client.CustomKineticMaidEntity;
import com.kinetic.client.entity.IPreviewEntity;
import com.kinetic.geckolib3.core.PlayState;
import com.kinetic.geckolib3.core.event.predicate.AnimationEvent;
import com.kinetic.molang.runtime.ExpressionEvaluator;
import com.github.tartaricacid.touhoulittlemaid.entity.item.EntitySit;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.MaidGameRecordManager;

import static com.kinetic.client.animation.predicate.IAnimationPredicate.playLoopAnimation;

public class MaidMiscPredicate implements IAnimationPredicate<CustomKineticMaidEntity> {
    public static final String[] ANIM_LIST = new String[]{"game_win", "game_lost", "beg"};

    @Override
    public PlayState test(AnimationEvent<CustomKineticMaidEntity> event, ExpressionEvaluator<?> evaluator) {
        EntityMaid maid = event.getAnimatableEntity().getEntity();
        if (maid == null || event.getAnimatableEntity() instanceof IPreviewEntity) {
            return PlayState.STOP;
        }
        // 赢棋输棋优先
        if (maid.getVehicle() instanceof EntitySit) {
            MaidGameRecordManager manager = maid.getGameRecordManager();
            if (manager.isWin()) {
                return playLoopAnimation(event, "game_win");
            }
            if (manager.isLost()) {
                return playLoopAnimation(event, "game_lost");
            }
        }
        // 祈求动画
        if (maid.isBegging()) {
            return playLoopAnimation(event, "beg");
        }
        return PlayState.STOP;
    }
}
