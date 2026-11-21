package com.kinetic.client.event;

import com.kinetic.Kinetic;
import com.kinetic.client.animation.ProneUseIntent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 把"玩家按着使用键"这件事喂给 {@link ProneUseIntent}。
 *
 * <p>只搬运输入，不参与任何玩法判定：按下时记下主手物品，每 tick 同步按键是否还按着。
 * 这样匍匐动作不必依赖 {@code isUsingItem()} 这条在趴姿下并不总是成立的中间状态。
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ProneInputEvent {
    @SubscribeEvent
    public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (!Kinetic.isAvailable() || !event.isUseItem()) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        ProneUseIntent.notePress(player, player.getMainHandItem());
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !Kinetic.isAvailable()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            ProneUseIntent.updateKeyState(false);
            return;
        }
        // 打开界面/失去焦点时按键不再算"按着"，避免暂停后姿势卡住。
        boolean down = minecraft.options.keyUse.isDown()
                && minecraft.screen == null
                && minecraft.mouseHandler.isMouseGrabbed();
        ProneUseIntent.updateKeyState(down);
    }
}
