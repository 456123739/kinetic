package com.kinetic.client.event;

import com.kinetic.Kinetic;
import com.kinetic.capability.PlayerAnimatableCapabilityProvider;
import com.kinetic.network.NetworkHandler;
import com.kinetic.network.forge.ClientProtocolGateway;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(value = Dist.CLIENT)
public class LocalPlayerRespawnEvent {
    @SubscribeEvent
    public static void onFire(ClientPlayerNetworkEvent.Clone event) {
        if (!Kinetic.isAvailable()) {
            return;
        }
        // 仅客户端运行时，执行后续的 copyFrom 会出现 null 值问题
        if (!NetworkHandler.isRemoteChannelPresent()) {
            return;
        }

        event.getOldPlayer().reviveCaps();
        event.getOldPlayer().getCapability(PlayerAnimatableCapabilityProvider.CAP).ifPresent(oldCap ->
                event.getNewPlayer().getCapability(PlayerAnimatableCapabilityProvider.CAP).ifPresent(newCap ->
                        newCap.moveFrom(oldCap)));
        event.getOldPlayer().invalidateCaps();
        ClientProtocolGateway.localPlayerCloned();
    }
}
