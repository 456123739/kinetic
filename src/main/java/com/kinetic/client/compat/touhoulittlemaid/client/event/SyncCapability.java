package com.kinetic.client.compat.touhoulittlemaid.client.event;

import com.kinetic.Kinetic;
import com.kinetic.client.compat.touhoulittlemaid.capability.KineticMaidCapabilityProvider;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

@OnlyIn(Dist.CLIENT)
public final class SyncCapability {
    private static final ResourceLocation KINETIC_MAID_CAP = new ResourceLocation(Kinetic.MOD_ID, "kinetic_maid");

    @SubscribeEvent
    public void onAttachCapabilityEvent(AttachCapabilitiesEvent<Entity> event) {
        if (event.getObject() instanceof EntityMaid maid && maid.level().isClientSide()) {
            event.addCapability(KINETIC_MAID_CAP, new KineticMaidCapabilityProvider(maid));
        }
    }
}
