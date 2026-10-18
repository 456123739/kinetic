package com.kinetic.client.compat.jade;

import com.kinetic.Kinetic;
import com.kinetic.capability.PlayerAnimatableCapabilityProvider;
import com.kinetic.capability.VehicleAnimatableCapabilityProvider;
import com.kinetic.util.ModelIdUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import snownee.jade.api.*;
import snownee.jade.api.config.IPluginConfig;

@WailaPlugin
@SuppressWarnings("unused")
public class JadePlugin implements IWailaPlugin {
    @Override
    public void registerClient(IWailaClientRegistration reg) {
        reg.registerEntityComponent(new KineticProvider(), Entity.class);
    }

    private static class KineticProvider implements IEntityComponentProvider {
        @SuppressWarnings("removal")
        private static final ResourceLocation UID = new ResourceLocation(Kinetic.MOD_ID, "model_info");

        @Override
        public void appendTooltip(ITooltip tooltip, EntityAccessor entityAccessor, IPluginConfig config) {
            if (entityAccessor.getEntity() instanceof Player player) {
                player.getCapability(PlayerAnimatableCapabilityProvider.CAP).ifPresent(cap -> {
                    var target = cap.isInitializedAndEnabled() ? cap.getModelRenderTarget() : null;
                    if (target != null) {
                        tooltip.add(Component.translatable("top.kinetic.model_info.id")
                                .append(target.getDisplayName(ModelIdUtil.getFileNameFromPath(cap.getModelId()))));
                    }
                });
            } else {
                entityAccessor.getEntity().getCapability(VehicleAnimatableCapabilityProvider.CAP).ifPresent(cap -> {
                    if (cap.isInitialized() && cap.isModelPresent()) {
                        tooltip.add(Component.translatable("top.kinetic.model_info.id")
                                .append(cap.getModelRenderTarget().getDisplayName(ModelIdUtil.getFileNameFromPath(cap.getModelId()))));
                    }
                });
            }
        }

        @Override
        public ResourceLocation getUid() {
            return UID;
        }
    }
}