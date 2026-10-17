package com.kinetic.client.compat.touhoulittlemaid.client.event;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class UpdateRemoteStruct {
    /* 施工中
    @SubscribeEvent
    public void onUpdateRemote(UpdateRemoteStructEvent event) {
        event.getMaid().getCapability(KineticMaidCapabilityProvider.CAP).ifPresent(cap -> cap.setRemoteStruct(event.getRoamingVars()));
    }
   */
}
