package com.kinetic.client.animation;

import com.kinetic.capability.PlayerAnimatableCapability;
import com.kinetic.client.animation.predicate.PlayerMainPredicate;
import com.kinetic.client.compat.ragdollified.RagdollifiedCompat;
import com.kinetic.client.entity.CustomPlayerEntity;
import com.kinetic.geckolib3.core.builder.LoopType;
import com.kinetic.geckolib3.core.event.predicate.AnimationEvent;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;

import java.util.function.BiPredicate;

public class AnimationRegister {
    public static final String IDLE = "idle";
    public static final String HOVER = "hover";
    public static final String HOVER_FADEOUT = "hover_fadeout";
    public static final String FOCUS = "focus";
    public static final String EMPTY = "empty";

    private static final float MIN_SPEED = 0.05f;

    /**
     * 这名玩家此刻是否处在"滑铲锁定"里（用于压掉匍匐/爬行姿态）。
     *
     * <p>真正的状态由 {@link SlideLock} 在跑酷控制器的谓词里每帧观测：持枪滑铲开始即锁定，
     * 跑酷停下后还会续一小段，因此这里只转发，不再自己记一份。旧的单槽静态字段在多玩家/
     * 多线程更新时会互相覆盖，已经去掉。
     */
    public static boolean wasSlidingRecently(Player player) {
        return SlideLock.isLocked(player);
    }

    public static void registerAnimationState() {
        // 死亡：Ragdollified 接管时让出死亡动画（布娃娃构造完成后其 HideDeadEntityMixin 会
        // 取消实体渲染并接管表现），否则维持 KINETIC 原有死亡动画。
        register("death", LoopType.HOLD_ON_LAST_FRAME, Priority.HIGHEST,
                (player, event) -> player.isDeadOrDying() && !RagdollifiedCompat.shouldYieldDeathAnimation(player));
        register("riptide", Priority.HIGHEST, (player, event) -> player.isAutoSpinAttack());
        register("sleep", Priority.HIGHEST, (player, event) -> player.getPose() == Pose.SLEEPING);
        register("swim", Priority.HIGHEST, (player, event) -> player.isSwimming());
        // 爬行/攀爬通道必须排除"滑铲"：滑铲玩家同样处于 Pose.SWIMMING，但水平速度远高于爬行。
        // 一旦开火打断疾跑，ParCool 的滑铲动画会停下，而这一条仍然是 HIGHEST——不排除的话
        // 滑铲中的玩家会被立刻换成趴地爬行姿势。
        register("climb", Priority.HIGHEST, (player, event) -> player.getPose() == Pose.SWIMMING
                && Math.abs(event.getLimbSwingAmount()) > MIN_SPEED && !isFastGroundMovement(player)
                && !wasSlidingRecently(player));
        register("climbing", Priority.HIGHEST, (player, event) -> player.getPose() == Pose.SWIMMING
                && !isFastGroundMovement(player) && !wasSlidingRecently(player));

        register("ladder_up", Priority.HIGHEST, (player, event) -> player.onClimbable() && getVerticalSpeed(player) > 0);
        register("ladder_stillness", Priority.HIGHEST, (player, event) -> player.onClimbable() && getVerticalSpeed(player) == 0);
        register("ladder_down", Priority.HIGHEST, (player, event) -> player.onClimbable() && getVerticalSpeed(player) < 0);

        register("fly", Priority.HIGH, (player, event) -> {
            if (event.getAnimatableEntity() instanceof PlayerAnimatableCapability cap && !cap.isLocalPlayer()) {
                return cap.getStateTracker().isFlying();
            }
            return player.getAbilities().flying;
        });
        register("elytra_fly", Priority.HIGH, (player, event) -> player.getPose() == Pose.FALL_FLYING && player.isFallFlying());

        register("swim_stand", Priority.NORMAL, (player, event) -> player.isInWater() && !player.onGround());
        // 受击动画已按要求移除：受击时保持当前动作，不再切到 attacked。
        register("jump", Priority.NORMAL, (player, event) -> !player.onGround() && !player.isInWater());
        register("sneak", Priority.NORMAL, (player, event) -> player.onGround() && player.getPose() == Pose.CROUCHING && Math.abs(event.getLimbSwingAmount()) > MIN_SPEED);
        register("sneaking", Priority.NORMAL, (player, event) -> player.onGround() && player.getPose() == Pose.CROUCHING);

        register("run", Priority.LOW, (player, event) -> player.onGround() && player.isSprinting());
        register("walk", Priority.LOW, (player, event) -> player.onGround() && event.getLimbSwingAmount() > MIN_SPEED);

        register(IDLE, Priority.LOWEST, (player, event) -> true);
    }

    private static void register(String animationName, LoopType loopType, int priority, BiPredicate<Player, AnimationEvent<CustomPlayerEntity>> predicate) {
        PlayerMainPredicate.register(new AnimationState<>(animationName, loopType, priority, predicate));
    }

    private static void register(String animationName, int priority, BiPredicate<Player, AnimationEvent<CustomPlayerEntity>> predicate) {
        register(animationName, LoopType.LOOP, priority, predicate);
    }

    /**
     * 水平速度是否已经快到只可能是"滑铲"而不是"爬行"。
     *
     * <p>爬行速度约 0.03~0.1 格/tick，滑铲起步约 0.4~0.6。取 0.2 作分界，两侧都留足余量。
     */
    /**
     * 水平速度是否已经快到只可能是"滑铲"而不是"爬行"。
     *
     * <p>爬行速度约 0.03~0.1 格/tick，滑铲起步约 0.4~0.6。取 0.2 作分界，两侧都留足余量。
     * 匍匐动作的趴姿判定复用同一门槛，避免"模型认为是滑铲、动作却认为是爬行"的分裂。
     */
    public static boolean isFastGroundMovement(Player player) {
        return player.getDeltaMovement().horizontalDistanceSqr() > 0.04;
    }

    private static float getVerticalSpeed(Player player) {
        return 20 * (float) (player.position().y - player.yo);
    }
}