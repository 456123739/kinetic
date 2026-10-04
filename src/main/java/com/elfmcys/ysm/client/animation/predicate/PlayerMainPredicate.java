package com.elfmcys.ysm.client.animation.predicate;

import com.elfmcys.ysm.client.animation.AnimationState;
import com.elfmcys.ysm.client.animation.Priority;
import com.elfmcys.ysm.config.AnimationPriorityConfig;
import com.elfmcys.ysm.client.compat.create.CreateCompat;
import com.elfmcys.ysm.client.compat.parcool.ParCoolCompat;
import com.elfmcys.ysm.client.compat.slashblade.SlashBladeCompat;
import com.elfmcys.ysm.client.compat.swarfare.SWarfareCompat;
import com.elfmcys.ysm.client.compat.tacz.TACZCompat;
import com.elfmcys.ysm.client.entity.CustomPlayerEntity;
import com.elfmcys.ysm.client.entity.IPreviewEntity;
import com.elfmcys.ysm.geckolib3.core.PlayState;
import com.elfmcys.ysm.geckolib3.core.builder.LoopType;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.molang.runtime.ExpressionEvaluator;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Objects;

import static com.elfmcys.ysm.client.animation.predicate.IAnimationPredicate.playAnimation;

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
        // 跑酷模组兼容
        boolean parcool = ParCoolCompat.hasAnimation(player);
        if (parcool) {
            return PlayState.STOP;
        }
        // 载具动画单独检查
        Entity vehicle = player.getVehicle();
        if (vehicle != null && vehicle.isAlive()) {
            return PlayState.STOP;
        }
        // 机械动力悬链动画
        if (CreateCompat.isHangingSkyhook(player)) {
            // 复用跑酷悬链动画
            return playAnimation(event, "parcool:ride_zipline");
        }

        // 收集所有满足触发条件的候选，再按优先级选最高。
        // 优先级来自服务端配置（AnimationPriorityConfig），未配置的动画使用内置档位权重，
        // 因此“不配置”与改动前的顺序完全一致；优先级只决定播放哪一个，不影响过渡与混合。
        var candidates = new ArrayList<AnimationState<Player, CustomPlayerEntity>>(4);
        var orders = new ArrayList<Integer>(4);
        var order = 0;
        for (int i = Priority.HIGHEST; i <= Priority.LOWEST; i++) {
            for (AnimationState<Player, CustomPlayerEntity> state : DATA[i]) {
                if (state.getPredicate().test(player, event)) {
                    candidates.add(state);
                    orders.add(order);
                }
                order++;
            }
        }
        if (candidates.isEmpty()) {
            return PlayState.STOP;
        }
        var indices = new ArrayList<Integer>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            indices.add(i);
        }
        indices.sort((left, right) -> {
            var leftState = candidates.get(left);
            var rightState = candidates.get(right);
            var leftPriority = AnimationPriorityConfig.priorityFor(leftState.getAnimationName(),
                    Priority.weightOf(leftState.getPriority()));
            var rightPriority = AnimationPriorityConfig.priorityFor(rightState.getAnimationName(),
                    Priority.weightOf(rightState.getPriority()));
            if (leftPriority != rightPriority) {
                return Integer.compare(rightPriority, leftPriority);
            }
            // 优先级相同：回退到原有逻辑（内置档位 + 注册顺序）。
            return Integer.compare(orders.get(left), orders.get(right));
        });

        {
            var winner = candidates.get(indices.get(0));
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
            return Objects.requireNonNullElseGet(gunMainAnimation, () -> playAnimation(event, animationName, loopType));
        }
    }
}
