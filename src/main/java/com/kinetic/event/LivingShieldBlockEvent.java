package com.kinetic.event;

import com.kinetic.Kinetic;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.ShieldBlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Kinetic.MOD_ID)
public class LivingShieldBlockEvent {
    public static final String COOLDOWN = "kinetic$shield_block_cooldown";

    @SubscribeEvent
    public static void onShieldBlockEvent(ShieldBlockEvent event) {
        LivingEntity entity = event.getEntity();
        entity.getPersistentData().putInt(COOLDOWN, 5);
    }

    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.getPersistentData().contains(COOLDOWN)) {
            int cooldown = entity.getPersistentData().getInt(COOLDOWN);
            if (cooldown > 0) {
                entity.getPersistentData().putInt(COOLDOWN, cooldown - 1);
            } else {
                entity.getPersistentData().remove(COOLDOWN);
            }
        }
    }

    public static boolean inShieldBlockCooldown(LivingEntity entity) {
        return entity.getPersistentData().contains(COOLDOWN);
    }
}
