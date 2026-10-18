package com.kinetic.client.animation.predicate;

import com.kinetic.client.animation.condition.ConditionalHold;
import com.kinetic.client.entity.CustomHumanoidEntity;
import com.kinetic.client.entity.HumanoidStateTracker;
import com.kinetic.client.entity.IPreviewEntity;
import com.kinetic.client.compat.tacz.TACZCompat;
import com.kinetic.geckolib3.core.PlayState;
import com.kinetic.geckolib3.core.builder.LoopType;
import com.kinetic.geckolib3.core.event.predicate.AnimationEvent;
import com.kinetic.molang.runtime.ExpressionEvaluator;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.apache.commons.lang3.StringUtils;

import static com.kinetic.client.animation.predicate.IAnimationPredicate.playAnimation;

public class OffhandPredicate implements IAnimationPredicate<CustomHumanoidEntity<?>> {
    @Override
    public PlayState test(AnimationEvent<CustomHumanoidEntity<?>> event, ExpressionEvaluator<?> evaluator) {
        LivingEntity entity = event.getAnimatableEntity().getEntity();
        if (entity == null || event.getAnimatableEntity() instanceof IPreviewEntity) {
            return PlayState.STOP;
        }
        // 睡眠 / 死亡 / riptide 的手臂由主频道负责：hold 频道在它之后处理、并且直接覆盖同一份骨骼
        // 快照，不让位的话尸体会保持举枪姿势、睡姿也会被持械姿势盖掉。
        if (entity.isSleeping() || entity.isDeadOrDying() || entity.isAutoSpinAttack()) {
            return PlayState.STOP;
        }

        // 副手手雷蓄力：与主手一致，由模型自带的 tac:offhand:grenade 演拔插销/待投。
        // 不接的话通用 use 动画会被 UsePredicate 停掉，手臂就停在上一帧的持握姿势。
        if (entity.isUsingItem() && entity.getUsedItemHand() == InteractionHand.OFF_HAND
                && TACZCompat.isGrenade(entity.getItemInHand(InteractionHand.OFF_HAND))) {
            return playAnimation(event, "tac:offhand:grenade", LoopType.HOLD_ON_LAST_FRAME);
        }

        if (!checkSwingAndUse(entity, InteractionHand.OFF_HAND)) {
            return PlayState.PAUSE;
        }

        ItemStack offhandItem = entity.getItemInHand(InteractionHand.OFF_HAND);
        if (offhandItem.is(Items.CROSSBOW) && CrossbowItem.isCharged(offhandItem)) {
            return playAnimation(event, "hold_offhand:charged_crossbow");
        }

        var tracker = event.getAnimatableEntity().getStateTracker();
        if (!isSameItem(offhandItem, tracker, InteractionHand.OFF_HAND)) {
            tracker.setHandItem(offhandItem, InteractionHand.OFF_HAND);
            event.getCodedController().indicateReload();
        }

        ConditionalHold conditionalHold = event.getAnimatableEntity().getConditionManager().getHoldOffhand();
        if (conditionalHold != null) {
            String name = conditionalHold.doTest(entity, InteractionHand.OFF_HAND);
            if (StringUtils.isNoneBlank(name)) {
                return playAnimation(event, name);
            }
        }

        return PlayState.STOP;
    }

    private boolean isSameItem(ItemStack playerItem, HumanoidStateTracker<?> tracker, InteractionHand hand) {
        ItemStack preItem = tracker.getHandItem(hand);
        if (preItem.isDamaged()) {
            return ItemStack.isSameItem(playerItem, preItem);
        }
        return ItemStack.matches(playerItem, preItem);
    }

    private boolean checkSwingAndUse(LivingEntity entity, InteractionHand hand) {
        if (entity.swinging && entity.swingingArm == hand) {
            return false;
        }
        return !entity.isUsingItem() || entity.getUsedItemHand() != hand;
    }
}
