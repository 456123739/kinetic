package com.kinetic.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 直接拦掉 TaCZ 自己的"取消疾跑"。
 *
 * <p>TaCZ 在 {@code LivingEntityAim#tickSprint} 里对玩家调 {@code setSprinting(false)}（换弹、开火
 * 之后都会走到）。疾跑一没，ParCool 的滑铲动画立刻停止，而 {@code Pose.SWIMMING} 还会保留一瞬，
 * 于是滑铲中的开火被渲染成匍匐。
 *
 * <p>用 {@code targets} 字符串指向 TaCZ 的类，因此本模组不产生对 TaCZ 的编译期依赖；TaCZ 未安装
 * 时这个 mixin 不会被应用。方法体只依赖原版类型。
 */
@Mixin(targets = "com.tacz.guns.entity.shooter.LivingEntityAim")
public abstract class TaczSprintMixin {
    // require = 0：TaCZ 换版本改了这个方法时只记一条日志，绝不让启动崩掉。
    @Redirect(require = 0,
            method = "tickSprint",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;setSprinting(Z)V"))
    private void kinetic$keepSprintWhileProne(LivingEntity entity, boolean sprinting) {
        if (!sprinting && entity instanceof Player player
                && player.getPose() == Pose.SWIMMING && !player.isSwimming()) {
            // 趴下/滑铲姿态：TaCZ 不允许打断疾跑。
            return;
        }
        entity.setSprinting(sprinting);
    }
}
