package com.kinetic.event;

import com.kinetic.Kinetic;
import com.kinetic.capability.AuthModelsCapability;
import com.kinetic.capability.ModelInfoCapability;
import com.kinetic.capability.PlayerAnimatableCapability;
import com.kinetic.capability.ProjectileAnimatableCapability;
import com.kinetic.capability.ProjectileModelInfoCapability;
import com.kinetic.capability.StarModelsCapability;
import com.kinetic.capability.VehicleAnimatableCapability;
import com.kinetic.capability.VehicleModelInfoCapability;
import com.kinetic.client.compat.touhoulittlemaid.TlmCommonCompat;
import com.kinetic.model.ModelRuntime;
import com.kinetic.network.NetworkHandler;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.loading.FMLEnvironment;

@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
public final class CommonEvent {
    @SubscribeEvent
    public static void onSetupEvent(FMLCommonSetupEvent event) {
        if (!Kinetic.isAvailable()) {
            event.enqueueWork(() -> ModLoader.get().addWarning(Kinetic.getUnavailableWarning()));
            return;
        }
        event.enqueueWork(() -> {
            ModelRuntime.initialize();
            NetworkHandler.init();
            TlmCommonCompat.registerEvent();
        });
    }

    @SubscribeEvent
    public static void registerCapability(RegisterCapabilitiesEvent event) {
        if (!Kinetic.isAvailable()) {
            return;
        }
        event.register(ModelInfoCapability.class);
        event.register(ProjectileModelInfoCapability.class);
        event.register(VehicleModelInfoCapability.class);
        event.register(AuthModelsCapability.class);
        event.register(StarModelsCapability.class);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            event.register(PlayerAnimatableCapability.class);
            event.register(ProjectileAnimatableCapability.class);
            event.register(VehicleAnimatableCapability.class);
        }
    }

}
