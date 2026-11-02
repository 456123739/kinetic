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

import static com.kinetic.client.animation.predicate.IAnimationPredicate.playAnimation;

public class SwingPredicate implements IAnimationPredicate<CustomHumanoidEntity<?>> {
    /** 匍匐挥砍动画名（模型自带才会接管；没有就保持原行为）。 */
    static final String PRONE_SWING = "prone:swing";

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

        // 匍匐挥砍：站姿挥砍的弧线是从"站着的人"出发的，叠到趴姿上就变成往地里砍。
        // 模型自带 prone:swing 时整段换掉（只写躯干、头与持刀手，腿留给主频道的爬行）。
        //
        // 只在**挥砍起始帧**（swingTime == 0）决定用哪一段：否则挥到一半趴下/站起来会让
        // 频道换动画名、整段重播，看起来就是"抽一下"。已经在这段里就继续播完。
        boolean alreadyProneSwing = PRONE_SWING.equals(event.getCodedController() == null
                ? null : event.getCodedController().getCurrentAnimationName());
        boolean useProneSwing = alreadyProneSwing || (entity.swingTime == 0 && ProneAction.isProne(entity));
        if (entity.swinging && !entity.isSleeping()
                && entity.swingingArm == InteractionHand.MAIN_HAND
                && event.getAnimatableEntity().getAnimation(PRONE_SWING) != null
                && useProneSwing) {
            if (entity.swingTime == 0 && event.getAnimatableEntity().getStateTracker().setEntityTickState(EntityTickStates.SWING)) {
                event.getCodedController().indicateReload();
            }
            return playAnimation(event, PRONE_SWING);
        }

        // 其他情况
        if (entity.swinging && !entity.isSleeping()) {
            if (entity.swingTime == 0 && event.getAnimatableEntity().getStateTracker().setEntityTickState(EntityTickStates.SWING)) {
                // swing 开始时重置动画
                event.getCodedController().indicateReload();
            }
            ConditionManager conditionManager = event.getAnimatableEntity().getConditionManager();
            ConditionalSwing conditionalSwing = entity.swingingArm == InteractionHand.MAIN_HAND ? conditionManager.getSwingMainhand() : conditionManager.getSwingOffhand();
            if (conditionalSwing != null) {
                String name = genericSwingFor(conditionalSwing.doTest(entity, entity.swingingArm),
                        entity.swingingArm);
                if (StringUtils.isNoneBlank(name)) {
                    return playAnimation(event, name);
                }
            }
            String defaultSwing = (entity.swingingArm == InteractionHand.MAIN_HAND) ? "swing_hand" : "swing_offhand";
            return playAnimation(event, defaultSwing);
        }
        return PlayState.CONTINUE;
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
