package com.kinetic.client.event;

import com.kinetic.Kinetic;
import com.kinetic.capability.PlayerAnimatableCapability;
import com.kinetic.capability.PlayerAnimatableCapabilityProvider;
import com.kinetic.client.compat.FirstPersonCompat;
import com.kinetic.client.compat.PlayerAnimatorCompat;
import com.kinetic.client.compat.realcamera.RealCameraCompat;
import com.kinetic.config.ClientConfig;
import com.kinetic.util.PersonView;
import com.kinetic.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(value = Dist.CLIENT)
public class ReplacePlayerRenderEvent {
    @SubscribeEvent
    public static void onRender(RenderPlayerEvent.Pre event) {
        if (!Kinetic.isAvailable()) {
            return;
        }
        Player playerRender = event.getEntity();
        LocalPlayer playerSelf = Minecraft.getInstance().player;
        if (playerRender.equals(playerSelf) && ClientConfig.DISABLE_SELF_MODEL.get()) {
            return;
        }
        if (!playerRender.equals(playerSelf) && ClientConfig.DISABLE_OTHER_MODEL.get()) {
            return;
        }
        if (event.getEntity().isSpectator()) {
            return;
        }
        playerRender.getCapability(PlayerAnimatableCapabilityProvider.CAP).ifPresent(cap -> {
            var ctx = RenderUtil.extractRenderContext();
            if (cap.isInitializedAndEnabled()) {
                if (shouldDrawSelfModel(cap, playerSelf)) {
                    event.setCanceled(true);
                    RegisterEntityRenderersEvent.getPlayerRenderer().render(
                            event.getEntity(), event.getEntity().getYRot(), event.getPartialTick(),
                            event.getPoseStack(), event.getMultiBufferSource(), event.getPackedLight());
                }
            }
        });
    }

    /**
     * 第一人称下是否仍然要画自己的身体。
     *
     * <p>四种情况需要：不是第一人称（本来就该画）；外部第一人称模组正在画玩家；真实相机在接管相机
     * （它需要看得见身体）；外部第一人称动画模组没有可用动作、或用户强制关闭了那个外部动画路径。
     *
     * <p>注意 {@link PersonView#isFirstPersonView} 依赖 {@code RenderUtil.isRenderingLevel()}，而在
     * 关卡渲染之外调用本事件时该标记为 {@code false}。真实相机的相机绑定不再走「重画一遍模型」这条
     * 路径（见 {@code RealCameraBindSource}），所以这里不需要再为它额外放宽判定。
     */
    private static boolean shouldDrawSelfModel(PlayerAnimatableCapability cap, LocalPlayer playerSelf) {
        if (!PersonView.isFirstPersonView(cap)) {
            return true;
        }
        if (FirstPersonCompat.isRenderingPlayer()) {
            return true;
        }
        if (RealCameraCompat.isActive()) {
            return true;
        }
        return ClientConfig.DISABLE_EXTERNAL_FIRST_PERSON_ANIM.get()
                || !PlayerAnimatorCompat.hasThirdPersonModelAnim(playerSelf);
    }
}
