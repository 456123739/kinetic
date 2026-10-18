package com.kinetic.client.animation.predicate;

import com.kinetic.client.animation.AnimationState;
import com.kinetic.client.animation.Priority;
import com.kinetic.config.AnimationPriorityConfig;
import com.kinetic.client.compat.create.CreateCompat;
import com.kinetic.client.compat.parcool.ParCoolCompat;
import com.kinetic.client.compat.slashblade.SlashBladeCompat;
import com.kinetic.client.compat.swarfare.SWarfareCompat;
import com.kinetic.client.compat.tacz.TACZCompat;
import com.kinetic.client.entity.CustomPlayerEntity;
import com.kinetic.client.entity.IPreviewEntity;
import com.kinetic.geckolib3.core.PlayState;
import com.kinetic.geckolib3.core.builder.LoopType;
import com.kinetic.geckolib3.core.event.predicate.AnimationEvent;
import com.kinetic.molang.runtime.ExpressionEvaluator;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import static com.kinetic.client.animation.predicate.IAnimationPredicate.playAnimation;

public class PlayerMainPredicate implements IAnimationPredicate<CustomPlayerEntity> {
    @SuppressWarnings("unchecked")
    private static final ReferenceArrayList<AnimationState<Player, CustomPlayerEntity>>[] DATA = new ReferenceArrayList[Priority.LOWEST + 1];

    static {
        for (int i = 0; i < DATA.length; i++) {
            DATA[i] = new ReferenceArrayList<>(6);
        }
    }

    public static void register(AnimationState<Player, CustomPlayerEntity> state) {
        DATA[state.getPriority()].add(state);
    }

    @Override
    public PlayState test(AnimationEvent<CustomPlayerEntity> event, ExpressionEvaluator<?> evaluator) {
        Player player = event.getAnimatableEntity().getEntity();
        if (player == null) {
            return PlayState.STOP;
        }
        if (event.getAnimatableEntity() instanceof IPreviewEntity) {
            return PlayState.STOP;
        }
        // 跑酷模组兼容。
        //
        // 必须同时确认该动画在本模型的动画集里确实存在：ParCoolPredicate 在模型里找不到该动画时
        // 会返回 STOP（什么都不播）。若这里仅凭"跑酷有动画名"就让位，就会出现
        // 「主控制器已停 + 跑酷控制器播不出来」= 玩家完全静止。加上存在性判断后，即使将来跑酷
        // 新增了模型尚未适配的动作，也只会回退到普通动画，而不会静止。
        // 死亡必须压过载具与跑酷：否则骑马/坐船时死亡会停在骑乘姿势，跑酷动作中死亡会停在
        // 滑铲/墙跑姿势，尸体永远倒不下去。死亡动画本身仍按注册顺序走主频道。
        boolean dying = player.isDeadOrDying();
        String parcoolAnimation = ParCoolCompat.getAnimation(player);
        if (!dying && parcoolAnimation != null && event.getAnimatableEntity().getAnimation(parcoolAnimation) != null) {
            return PlayState.STOP;
        }
        // 载具动画单独检查
        Entity vehicle = player.getVehicle();
        if (!dying && vehicle != null && vehicle.isAlive()) {
            return PlayState.STOP;
        }
        // 机械动力悬链动画
        if (CreateCompat.isHangingSkyhook(player)) {
            // 复用跑酷悬链动画
            return playAnimation(event, "parcool:ride_zipline");
        }

        // 收集所有满足触发条件的候选中优先级最高者。
        // 优先级来自服务端配置（AnimationPriorityConfig），未配置的动画使用内置档位权重，
        // 因此“不配置”与改动前的顺序完全一致；优先级只决定播放哪一个，不影响过渡与混合。
        // 这里用一次线性扫描取代“收集候选 + 排序”，排序语义（优先级降序，同优先级按注册顺序升序）保持一致。
        AnimationState<Player, CustomPlayerEntity> winner = null;
        int winnerPriority = 0;
        int winnerOrder = 0;
        int order = 0;
        for (int i = Priority.HIGHEST; i <= Priority.LOWEST; i++) {
            for (AnimationState<Player, CustomPlayerEntity> state : DATA[i]) {
                if (state.getPredicate().test(player, event)) {
                    var priority = AnimationPriorityConfig.priorityForLowercase(state.getLowercaseName(),
                            Priority.weightOf(state.getPriority()));
                    if (winner == null || priority > winnerPriority
                            || (priority == winnerPriority && order < winnerOrder)) {
                        winner = state;
                        winnerPriority = priority;
                        winnerOrder = order;
                    }
                }
                order++;
            }
        }
        if (winner == null) {
            return PlayState.STOP;
        }

        {
            String animationName = winner.getAnimationName();
            LoopType loopType = winner.getLoopType();

            // 先判断拔刀剑动画
            PlayState slashBladeAnimation = SlashBladeCompat.playMainAnimation(player, event, animationName, loopType);
            if (slashBladeAnimation != null) {
                return slashBladeAnimation;
            }

            // 再判断 tacz 动画
            PlayState gunMainAnimation = TACZCompat.playGunMainAnimation(player, event, animationName, loopType);
            // 在判断卓越前线的动画
            if (gunMainAnimation == null) {
                gunMainAnimation = SWarfareCompat.playGunMainAnimation(player, event, animationName, loopType);
            }
            if (gunMainAnimation != null) {
                return gunMainAnimation;
            }
            return playAnimation(event, animationName, loopType);
        }
    }
}
