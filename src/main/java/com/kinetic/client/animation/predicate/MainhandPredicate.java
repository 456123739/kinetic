package com.kinetic.client.animation.predicate;

import com.kinetic.client.animation.SlideLock;
import com.kinetic.client.animation.condition.ConditionalHold;
import com.kinetic.client.compat.swarfare.SWarfareCompat;
import com.kinetic.client.compat.tacz.TACZCompat;
import com.kinetic.client.compat.touhoulittlemaid.client.TlmClientCompat;
import com.kinetic.client.entity.CustomHumanoidEntity;
import com.kinetic.client.entity.HumanoidStateTracker;
import com.kinetic.client.entity.IPreviewEntity;
import com.kinetic.geckolib3.core.PlayState;
import com.kinetic.geckolib3.core.builder.LoopType;
import com.kinetic.geckolib3.core.event.predicate.AnimationEvent;
import com.kinetic.molang.runtime.ExpressionEvaluator;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.apache.commons.lang3.StringUtils;

import static com.kinetic.client.animation.predicate.IAnimationPredicate.playAnimation;

public class MainhandPredicate implements IAnimationPredicate<CustomHumanoidEntity<?>> {
    /**
     * 手雷蓄力姿势的动画名。抬雷与待投由模型自带（{@code tac.animation.json}），
     * 这里只负责在「右键蓄力」期间把它挂到 hold 频道上。
     */
    private static final String GRENADE_CHARGE_ANIMATION = "tac:mainhand:grenade";

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

        // 滑铲锁定：上半身只允许静止持枪姿势。必须在"挥动/使用就暂停"之前处理，
        // 否则开火那一 tick 的 swinging / aiming 会把 hold 频道按停，手臂被 fire/use 频道接走。
        if (SlideLock.wantsStaticHold(entity)) {
            ItemStack lockedItem = entity.getItemInHand(InteractionHand.MAIN_HAND);
            PlayState staticHold = TACZCompat.playGunStaticHoldAnimation(lockedItem, event);
            if (staticHold == null) {
                staticHold = SWarfareCompat.playGunStaticHoldAnimation(lockedItem, event);
            }
            if (staticHold != null) {
                return staticHold;
            }
        }

        if (!checkSwingAndUse(entity, InteractionHand.MAIN_HAND)) {
            return PlayState.PAUSE;
        }

        ItemStack mainHandItem = entity.getItemInHand(InteractionHand.MAIN_HAND);
        PlayState gunHoldAnimation = TACZCompat.playGunHoldAnimation(mainHandItem, event);
        if (gunHoldAnimation != null) {
            return gunHoldAnimation;
        }
        gunHoldAnimation = SWarfareCompat.playGunHoldAnimation(mainHandItem, event);
        if (gunHoldAnimation != null) {
            return gunHoldAnimation;
        }
        // 通用手雷蓄力：标签是唯一契约，因此即使两个枪械联动模组都没接管（未安装、或该手雷
        // 不属于它们的判定范围），只要模型自带这个动画就仍然给出抬雷/待投姿势。
        if (entity.isUsingItem() && entity.getUsedItemHand() == InteractionHand.MAIN_HAND
                && isGrenade(mainHandItem)
                && event.getAnimatableEntity().getAnimation(GRENADE_CHARGE_ANIMATION) != null) {
            return playAnimation(event, GRENADE_CHARGE_ANIMATION, LoopType.HOLD_ON_LAST_FRAME);
        }
        if (mainHandItem.is(Items.CROSSBOW) && CrossbowItem.isCharged(mainHandItem)) {
            return playAnimation(event, "hold_mainhand:charged_crossbow");
        }
        boolean playerIsFishing = entity instanceof Player player && player.fishing != null;
        boolean maidIsFishing = TlmClientCompat.isMaidFishing(entity);
        if (playerIsFishing || maidIsFishing) {
            return playAnimation(event, "hold_mainhand:fishing");
        }

        var tracker = event.getAnimatableEntity().getStateTracker();
        if (!isSameItem(mainHandItem, tracker, InteractionHand.MAIN_HAND)) {
            tracker.setHandItem(mainHandItem, InteractionHand.MAIN_HAND);
            event.getCodedController().indicateReload();
        }

        ConditionalHold conditionalHold = event.getAnimatableEntity().getConditionManager().getHoldMainhand();
        if (conditionalHold != null) {
            String name = conditionalHold.doTest(entity, InteractionHand.MAIN_HAND);
            if (StringUtils.isNoneBlank(name)) {
                // 趴下持刀不能把站姿"垂刀"直接叠到爬姿上：小臂朝下时刀尖必然穿地。
                // 这里只换持械手臂的预备位；躯干仍由主频道爬行，挥刀首末帧与它相同。
                boolean prone = entity instanceof Player player && com.kinetic.client.animation.ProneAction.isProne(player);
                String proneHold = proneHoldFor(prone, name,
                        event.getAnimatableEntity().getAnimation("prone:hold:sword") != null);
                return playAnimation(event, proneHold);
            }
        }

        return PlayState.STOP;
    }

    /** 趴下且模型提供预备位时，持剑走 prone:hold:sword；其他物品完全不变。 */
    static String proneHoldFor(boolean prone, String original, boolean proneHoldAvailable) {
        return prone && proneHoldAvailable && "hold_mainhand:sword".equals(original)
                ? "prone:hold:sword" : original;
    }

    private boolean isSameItem(ItemStack playerItem, HumanoidStateTracker<?> tracker, InteractionHand hand) {
        ItemStack preItem = tracker.getHandItem(hand);
        if (preItem.isDamaged()) {
            return ItemStack.isSameItem(playerItem, preItem);
        }
        return ItemStack.matches(playerItem, preItem);
    }

    private boolean checkSwingAndUse(LivingEntity entity, InteractionHand hand) {
        return holdChannelRuns(entity.swinging && entity.swingingArm == hand,
                entity.isUsingItem() && entity.getUsedItemHand() == hand,
                isGrenade(entity.getItemInHand(hand)));
    }

    /**
     * hold 频道本帧是否继续参与。
     *
     * <p>挥动中交给 swing 频道，暂停；「使用中」通常交给 use 频道，也暂停——<b>手雷是唯一例外</b>：
     * use 频道对它返回 STOP，抬雷/待投姿势由 hold 频道的 {@code tac:mainhand:grenade} 给出。
     * 若这里照常暂停，蓄力期间 hold 控制器会停在上一次的状态上，表现就是「姿势回到初始状态、
     * 只剩投出瞬间那一下动画」。
     */
    static boolean holdChannelRuns(boolean swingingOnThisHand, boolean usingThisHand, boolean grenade) {
        if (swingingOnThisHand) {
            return false;
        }
        if (usingThisHand) {
            return grenade;
        }
        return true;
    }

    private static boolean isGrenade(ItemStack stack) {
        return TACZCompat.isGrenade(stack) || SWarfareCompat.isGrenade(stack);
    }
}
