package com.kinetic;

import com.kinetic.api.internal.event.KineticEventHandlerLoader;
import com.kinetic.config.ClientConfig;
import com.kinetic.config.ServerConfig;
import com.kinetic.init.ModSounds;
import com.kinetic.util.NativeLibUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.ModLoadingWarning;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

@Mod(Kinetic.MOD_ID)
@SuppressWarnings("removal")
public class Kinetic {
    public static final String MOD_ID = "kinetic";
    public static ModContainer MOD;
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);
    private static IEventBus EVENT_BUS;

    public Kinetic() throws IOException {
        MOD = ModLoadingContext.get().getActiveContainer();
        EVENT_BUS = FMLJavaModLoadingContext.get().getModEventBus();
        initConfig();

        NativeLibUtil.load();
        if (!NativeLibUtil.isAvailable()) {
            LOGGER.error(getUnavailableMessageString());
            return;
        }

        KineticEventHandlerLoader.attach(EVENT_BUS);
    }

    public static void registerEventHandler(Object handler) {
        EVENT_BUS.register(handler);
    }

    private static void initConfig() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, ClientConfig.init());
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, ServerConfig.init());
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ModSounds.SOUNDS.register(FMLJavaModLoadingContext.get().getModEventBus());
        }
    }

    public static boolean postEvent(Event event) {
        return EVENT_BUS.post(event);
    }

    public static boolean isAvailable() {
        return NativeLibUtil.isAvailable();
    }

    public static boolean isMobilePlatform() {
        return NativeLibUtil.isMobilePlatform();
    }

    @OnlyIn(Dist.CLIENT)
    public static void sendUnavailableMessage() {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.sendSystemMessage(getUnavailableMessage());
        }
    }

    public static ModLoadingWarning getUnavailableWarning() {
        return NativeLibUtil.getUnavailableWarning();
    }

    public static Component getUnavailableMessage() {
        return NativeLibUtil.getUnsupportedMsg();
    }

    public static String getUnavailableMessageString() {
        return NativeLibUtil.getUnsupportedMsgStr();
    }
}
