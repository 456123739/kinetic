package com.kinetic.client.animation.predicate;

import com.kinetic.client.animation.EntityTickStates;
import com.kinetic.client.animation.ProneAction;
import com.kinetic.client.animation.SlideLock;
import com.kinetic.client.animation.condition.ConditionManager;
import com.kinetic.client.animation.condition.ConditionalSwing;
import com.kinetic.client.compat.ironsspellbooks.IronsSpellBooksCompat;
import com.kinetic.client.compat.slashblade.SlashBladeCompat;
import com.kinetic.client.entity.CustomHumanoidEntity;
import com.kinetic.client.entity.IPreviewEntity;
import com.kinetic.geckolib3.core.PlayState;
import com.kinetic.geckolib3.core.event.predicate.AnimationEvent;
import com.kinetic.molang.runtime.ExpressionEvaluator;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;

import static com.kinetic.client.animation.predicate.IAnimationPredicate.playAnimation;

public class SwingPredicate implements IAnimationPredicate<CustomHumanoidEntity<?>> {
    /** 匍匐挥砍动画名（模型自带才会接管；没有就保持原行为）。 */
    static final String PRONE_SWING = "prone:swing";
    /** 匍匐投掷动画名（手雷/药水等 throwable 的挥动）。 */
    static final String PRONE_THROW = "prone:throw";

    @Override
    public PlayState test(AnimationEvent<CustomHumanoidEntity<?>> event, ExpressionEvaluator<?> evaluator) {
        LivingEntity entity = event.getAnimatableEntity().getEntity();
        if (entity == null || event.getAnimatableEntity() instanceof IPreviewEntity) {
            return PlayState.STOP;
        }
        // 滑铲锁定：持枪时左键触发的挥动不允许改手臂姿势（滑铲的下身由锁定层负责）。
        if (SlideLock.suppressesOneShot(entity)) {
            return PlayState.STOP;
        }

        // 铁魔法兼容
        PlayState playState = IronsSpellBooksCompat.playAnimation(event, entity);
        if (playState != null) {
            return playState;
        }

        // 拔刀剑兼容，拔刀剑的使用不受 swing 限制
        if (!entity.isSleeping() && SlashBladeCompat.isSlashBladeItem(entity.getItemInHand(InteractionHand.MAIN_HAND))) {
            // 起手阻止后续原挥剑动画
            if (event.getCodedController().isAnimFinished()) {
                // 重置动画
                event.getCodedController().indicateReload();
            }
            String animationName = SlashBladeCompat.getAnimationName(event);
            if (StringUtils.isNoneBlank(animationName)) {
                if (event.getAnimatableEntity().getAnimation(animationName) != null) {
                    return playAnimation(event, animationName);
                }
                return PlayState.CONTINUE;
            }
        }

        if (entity.swinging && !entity.isSleeping()) {
            if (entity.swingTime == 0 && event.getAnimatableEntity().getStateTracker().setEntityTickState(EntityTickStates.SWING)) {
                // swing 开始时重置动画
                event.getCodedController().indicateReload();
            }
            ConditionManager conditionManager = event.getAnimatableEntity().getConditionManager();
            ConditionalSwing conditionalSwing = entity.swingingArm == InteractionHand.MAIN_HAND
                    ? conditionManager.getSwingMainhand() : conditionManager.getSwingOffhand();
            String name = null;
            if (conditionalSwing != null) {
                name = genericSwingFor(conditionalSwing.doTest(entity, entity.swingingArm), entity.swingingArm);
            }
            if (StringUtils.isBlank(name)) {
                name = (entity.swingingArm == InteractionHand.MAIN_HAND) ? "swing_hand" : "swing_offhand";
            }
            // 趴下时换成匍匐专用的弧线：站姿弧线（尤其投掷）叠到躺姿上会往地里砍/往地上扔。
            // 每帧按趴姿重新判定——站起来的那一帧立刻回到站姿弧线，不会继续演"抬着头挥刀"。
            if (entity.swingingArm == InteractionHand.MAIN_HAND && ProneAction.isProne(entity)) {
                String variant = name.contains("throwable_potion") ? PRONE_THROW : PRONE_SWING;
                if (event.getAnimatableEntity().getAnimation(variant) != null) {
                    return playAnimation(event, variant);
                }
            }
            return playAnimation(event, name);
        }
        // 挥动已经结束（entity.swinging 窗口只有 6 tick，而趴下弧线可能更长）：控制器会继续把
        // prone:swing / prone:throw 播完，并且一直覆盖右臂。玩家如果在这段里站起来，就会看到
        // 站着的人还在做趴下挥砍。因此一旦不再趴着，立刻停止趴下变体，让上层姿势接管。
        if (shouldStopProneVariant(ProneAction.isProne(entity),
                event.getCodedController().getCurrentAnimationName())) {
            return PlayState.STOP;
        }
        return PlayState.CONTINUE;
    }

    /**
     * 挥动结束后，是否应当立刻停掉正在播的趴下变体。
     *
     * <p>控制器在 {@code entity.swinging} 变 false 之后仍会 {@code CONTINUE} 把当前动画播完，
     * 而趴下弧线比原版 6 tick 的挥动窗口长，所以站起来的瞬间它还在覆盖右臂。规则只有一条：
     * **不再趴着、且当前播的是趴下变体** → 停。还趴着就让它播完（那正是要的效果）。
     *
     * @param prone            此刻是否趴着
     * @param currentAnimation 控制器当前动画名（可能为 null）
     */
    static boolean shouldStopProneVariant(boolean prone, @Nullable String currentAnimation) {
        if (prone || currentAnimation == null) {
            return false;
        }
        return PRONE_SWING.equals(currentAnimation) || PRONE_THROW.equals(currentAnimation);
    }

    /**
     * 剑类挥砍回落到通用手臂挥砍。
     *
     * <p>模型自带的 {@code swing:sword} 是一套带 {@code UpBody}/{@code UpperBody}/{@code Head} 轨道的
     * 三段连招，而且分支靠 timeline 里的 {@code v.jump = q.is_jumping} 在动画第 0 帧算一次——
     * 控制器复用同一段动画时这个值会滞留，于是一挥就是跳劈变体；同时躯干轨道会盖掉跑步的上半身
     * 摆动，看起来像"攻击把跑步打断了"。
     *
     * <p>玩家要的是只动手臂的挥砍，所以剑类统一回到 {@code swing_hand}/{@code swing_offhand}
     * （这两条只写 {@code RightArm}/{@code RightForeArm}，不碰躯干与腿），剑的持握姿势仍由
     * {@code hold_mainhand:sword} 负责，不受影响。
     */
    private static String genericSwingFor(String name, InteractionHand hand) {
        if (name == null) {
            return null;
        }
        // swing:slashblade 是空动画（只剩 loop、没有骨骼）。被归到 slashblade 分类的近战武器
        // （lrtactical:melee 就在这类里）不能退回 swing_hand，否则劈砍会变成原版的小挥臂；
        // 统一改用刀剑劈砍 swing:sword。
        if ("swing:slashblade".equals(name)) {
            return hand == InteractionHand.MAIN_HAND ? "swing:sword" : "swing_offhand";
        }
        return name;
    }
}
