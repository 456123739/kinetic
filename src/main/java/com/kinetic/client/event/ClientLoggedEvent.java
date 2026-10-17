package com.kinetic.client.event;

import com.kinetic.Kinetic;
import com.kinetic.network.forge.ClientSessionRuntime;
import net.minecraft.network.Connection;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;


@Mod.EventBusSubscriber(value = Dist.CLIENT)
public class ClientLoggedEvent {
    private static Connection loggedIn;

    @SubscribeEvent
    public static void onPlayerLoggedIn(ClientPlayerNetworkEvent.LoggingIn event) {
        var connection = event.getConnection();
        if (loggedIn == connection) {
            return;
        }
        if (!Kinetic.isAvailable()) {
            loggedIn = connection;
            Kinetic.sendUnavailableMessage();
            return;
        }
        ClientSessionRuntime.beginConnection(connection);
        loggedIn = connection;
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        var connection = event.getConnection();
        if (loggedIn != connection) {
            return;
        }
        loggedIn = null;
        if (Kinetic.isAvailable()) {
            ClientSessionRuntime.disconnect(connection);
        }
    }
}
