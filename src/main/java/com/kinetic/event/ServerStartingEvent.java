package com.kinetic.event;

import com.kinetic.Kinetic;
import com.kinetic.model.service.ServerModelService;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber
public class ServerStartingEvent {
    @SubscribeEvent
    public static void onServerInit(final ServerAboutToStartEvent event) {
        if (!Kinetic.isAvailable()) {
            return;
        }
        ServerModelService.start(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(final ServerStoppingEvent event) {
        ServerModelService.current().ifPresent(ServerModelService::close);
    }
}
