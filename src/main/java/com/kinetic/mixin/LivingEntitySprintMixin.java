package com.kinetic.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 滑铲期间不允许取消疾跑。
 *
 * <p>TaCZ 开火会强制 {@code setSprinting(false)}；疾跑一没，ParCool 的滑铲动画立刻停止，
 * 而玩家的 {@code Pose.SWIMMING} 还会保留一瞬——KINETIC 的 {@code climb}/{@code climbing} 谓词
 * 于是接管，滑铲被渲染成趴地爬行。
 *
 * <p>这里不区分调用方：只要玩家此刻确实在滑铲（{@code Pose.SWIMMING}、在地面、水平速度远高于
 * 爬行），任何"取消疾跑"的请求都被拒绝。滑铲本身只有一两秒，速度一掉或姿态一变就恢复正常，
 * 不会影响其他任何疾跑逻辑。
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntitySprintMixin {
    /** 与 AnimationRegister 的滑铲判定保持一致：爬行约 0.03~0.1 格/tick，滑铲 0.4~0.6。 */
    private static final double KINETIC_SLIDE_SPEED_SQR = 0.04;

    @Inject(method = "setSprinting", at = @At("HEAD"), cancellable = true)
    private void kinetic$keepSprintWhileSliding(boolean sprinting, CallbackInfo ci) {
        if (sprinting) {
            return;
        }
        if (!((Object) this instanceof Player player)) {
            return;
        }
        if (player.getPose() != Pose.SWIMMING || player.isSwimming() || !player.onGround()) {
            return;
        }
        if (player.getDeltaMovement().horizontalDistanceSqr() <= KINETIC_SLIDE_SPEED_SQR) {
            return;
        }
        ci.cancel();
    }
}
