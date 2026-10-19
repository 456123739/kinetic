package com.kinetic.client.animation.predicate;

import com.kinetic.client.animation.EntityTickStates;
import com.kinetic.client.animation.SlideLock;
import com.kinetic.client.animation.condition.ConditionalUse;
import com.kinetic.client.compat.tacz.TACZCompat;
import com.kinetic.client.entity.CustomHumanoidEntity;
import com.kinetic.client.entity.IPreviewEntity;
import com.kinetic.geckolib3.core.PlayState;
import com.kinetic.geckolib3.core.event.predicate.AnimationEvent;
import com.kinetic.molang.runtime.ExpressionEvaluator;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import org.apache.commons.lang3.StringUtils;

import static com.kinetic.client.animation.predicate.IAnimationPredicate.playAnimation;

public class UsePredicate implements IAnimationPredicate<CustomHumanoidEntity<?>> {

    /**
     * 没有条目匹配时使用的默认「使用中」动画。
     *
     * <p>模组手雷（TaCZ 附属、卓越前线等）把右键蓄力声明为 {@code UseAnim.BOW}，于是会命中
     * {@code use_mainhand:bow}——那是拉弓，用它表现扔雷既不自然也会误导读条。这里对「使用动画是
     * 弓、但物品本身不是弓弩」的情况改用模型自带的专用投掷动画（{@code use_mainhand:grenade} /
     * {@code use_offhand:grenade}）。这些动画只驱动上半身，因此腿部的跑动、滑铲等基础动画不会被替换。
     *
     * <p>模型没有提供该动画时保持原行为，不凭空造动作。
     */
    private static String defaultUseAnimation(CustomHumanoidEntity<?> animatable, LivingEntity entity,
                                              InteractionHand hand) {
        boolean mainHand = hand == InteractionHand.MAIN_HAND;
        ItemStack held = entity.getItemInHand(hand);
        boolean bowAnimationWithoutBow = held.getUseAnimation() == UseAnim.BOW
                && !(held.getItem() instanceof ProjectileWeaponItem);
        if (bowAnimationWithoutBow) {
            String grenade = mainHand ? "tac:mainhand:grenade" : "tac:offhand:grenade";
            if (animatable.getAnimation(grenade) != null) {
                return grenade;
            }
        }
        return mainHand ? "use_mainhand" : "use_offhand";
    }

    @Override
    public PlayState test(AnimationEvent<CustomHumanoidEntity<?>> event, ExpressionEvaluator<?> evaluator) {
        LivingEntity entity = event.getAnimatableEntity().getEntity();
        if (entity == null || event.getAnimatableEntity() instanceof IPreviewEntity) {
            return PlayState.STOP;
        }

        // 滑铲锁定：瞄准/换弹/吃喝等"使用中"动画不许改手臂，持枪姿势固定在 hold 频道。
        if (SlideLock.suppressesOneShot(entity)) {
            return PlayState.STOP;
        }

        if (entity.isUsingItem() && !entity.isSleeping()) {
            // 手雷蓄力：保持 hold 频道的持有/蓄力姿势，不被通用 use 动画顶掉。
            if (TACZCompat.isGrenade(entity.getItemInHand(entity.getUsedItemHand()))) {
                return PlayState.STOP;
            }
            if (entity.getTicksUsingItem() == 1 && event.getAnimatableEntity().getStateTracker().setEntityTickState(EntityTickStates.USING_ITEM)) {
                event.getCodedController().indicateReload();
            }
            var conditionManager = event.getAnimatableEntity().getConditionManager();
            if (entity.getUsedItemHand() == InteractionHand.MAIN_HAND) {
                ConditionalUse conditionalUse = conditionManager.getUseMainhand();
                if (conditionalUse != null) {
                    String name = conditionalUse.doTest(entity, InteractionHand.MAIN_HAND);
                    if (StringUtils.isNoneBlank(name)) {
                        return playAnimation(event, name);
                    }
                }
                return playAnimation(event, defaultUseAnimation(event.getAnimatableEntity(), entity, InteractionHand.MAIN_HAND));
            } else {
                ConditionalUse conditionalUse = conditionManager.getUseOffhand();
                if (conditionalUse != null) {
                    String name = conditionalUse.doTest(entity, InteractionHand.OFF_HAND);
                    if (StringUtils.isNoneBlank(name)) {
                        return playAnimation(event, name);
                    }
                }
                return playAnimation(event, defaultUseAnimation(event.getAnimatableEntity(), entity, InteractionHand.OFF_HAND));
            }
        }
        return PlayState.STOP;
    }
}
