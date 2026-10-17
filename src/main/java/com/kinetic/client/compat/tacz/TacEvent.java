package com.kinetic.client.compat.tacz;

import com.kinetic.Kinetic;
import com.kinetic.capability.PlayerAnimatableCapabilityProvider;
import com.kinetic.client.compat.touhoulittlemaid.client.TlmClientCompat;
import com.tacz.guns.api.event.common.GunFireEvent;
import com.tacz.guns.api.event.common.GunMeleeEvent;
import com.tacz.guns.api.event.common.GunReloadEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public class TacEvent {
    @SubscribeEvent
    public void onGunShoot(GunFireEvent event) {
        if (!Kinetic.isAvailable()) {
            return;
        }
        LivingEntity shooter = event.getShooter();
        shooter.getCapability(PlayerAnimatableCapabilityProvider.CAP).ifPresent(cap -> {
            cap.setTacGunAnimationNeedReload(true);
        });
        TlmClientCompat.markTacGunAnimationNeedReload(shooter);
    }

    @SubscribeEvent
    public void onGunShoot(GunMeleeEvent event) {
        if (!Kinetic.isAvailable()) {
            return;
        }
        LivingEntity shooter = event.getShooter();
        shooter.getCapability(PlayerAnimatableCapabilityProvider.CAP).ifPresent(cap -> {
            cap.setTacGunAnimationNeedReload(true);
        });
        TlmClientCompat.markTacGunAnimationNeedReload(shooter);
    }

    @SubscribeEvent
    public void onGunShoot(GunReloadEvent event) {
        if (!Kinetic.isAvailable()) {
            return;
        }
        LivingEntity shooter = event.getEntity();
        shooter.getCapability(PlayerAnimatableCapabilityProvider.CAP).ifPresent(cap -> {
            cap.setTacGunAnimationNeedReload(true);
        });
        TlmClientCompat.markTacGunAnimationNeedReload(shooter);
    }
}
