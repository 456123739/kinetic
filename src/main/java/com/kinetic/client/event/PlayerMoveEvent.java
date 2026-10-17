package com.kinetic.client.event;

import com.kinetic.Kinetic;
import com.kinetic.capability.PlayerAnimatableCapabilityProvider;
import com.kinetic.network.NetworkHandler;
import com.kinetic.network.forge.ClientProtocolGateway;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(value = Dist.CLIENT)
public class PlayerMoveEvent {
    private static boolean LOCK_EXTRA_ANIMATION = false;

    // 轮盘锁定（Alt+L）的按键入口已随动作轮盘一起禁用；锁定状态本身保留，
    // 因为保留的 AnimationRouletteScreen 仍会读写它。

    /**
     * 改用 TickEvent.ClientTickEvent 监听按键状态，避免与其他模组（如 Touch Controller） 冲突时漏判按键。
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (!Kinetic.isAvailable()) {
            return;
        }
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (LOCK_EXTRA_ANIMATION) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && isMoveKey(player)) {
            player.getCapability(PlayerAnimatableCapabilityProvider.CAP).ifPresent(cap -> {
                if (cap.isPlayingExtraAnimation()) {
                    cap.stopExtraAnimation();
                    if (NetworkHandler.isRemoteChannelPresent()) {
                        ClientProtocolGateway.stopSelfAnimation();
                    }
                }
            });
        }
    }

    public static boolean isMoveKey(LocalPlayer player) {
        Input input = player.input;
        return input != null && (hasImpulse(input.leftImpulse) || hasImpulse(input.forwardImpulse)
               || input.jumping || input.shiftKeyDown);
    }

    private static boolean hasImpulse(float impulse) {
        return Math.abs(impulse) > 1.0E-5F;
    }

    public static void switchLock() {
        LOCK_EXTRA_ANIMATION = !LOCK_EXTRA_ANIMATION;
    }

    public static boolean isLocked() {
        return LOCK_EXTRA_ANIMATION;
    }
}
