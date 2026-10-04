package com.elfmcys.ysm.event;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.config.AnimationPriorityConfig;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;

/**
 * 服务端配置加载/重载时刷新动画优先级表。
 *
 * <p>动画优先级放在 {@code ModConfig.Type.SERVER} 配置里，Forge 的 {@code ConfigSync}
 * 会在登录握手时把服务端的 SERVER 配置文件下发给客户端，客户端收到后会重载配置并触发
 * 这里的 {@code Reloading}，因此客户端动画选择使用的就是同步过来的服务端数值。
 */
@Mod.EventBusSubscriber(modid = YesSteveModel.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ConfigSyncHandler {
    private ConfigSyncHandler() {
    }

    @SubscribeEvent
    public static void onConfigLoading(ModConfigEvent.Loading event) {
        refresh(event);
    }

    @SubscribeEvent
    public static void onConfigReloading(ModConfigEvent.Reloading event) {
        refresh(event);
    }

    private static void refresh(ModConfigEvent event) {
        var config = event.getConfig();
        if (config != null && config.getType() == ModConfig.Type.SERVER
                && YesSteveModel.MOD_ID.equals(config.getModId())) {
            AnimationPriorityConfig.refreshFromServerConfig();
        }
    }
}
