package com.kinetic.client.event;

import com.kinetic.Kinetic;
import com.kinetic.capability.PlayerAnimatableCapabilityProvider;
import com.kinetic.config.ClientConfig;
import com.kinetic.geckolib3.core.util.AnimationRateTiers;
import com.kinetic.model.service.ClientModelService;
import com.kinetic.client.compat.ragdollified.RagdollifiedCompat;
import com.kinetic.client.compat.ragdollified.RagdollifiedDeathWatcher;
import com.kinetic.client.texture.CustomTextureManager;
import com.kinetic.network.forge.ClientProtocolGateway;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(value = Dist.CLIENT)
public class ClientTickEvent {
    private static int tickCount;
    private static int refreshRate = 60;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (!Kinetic.isAvailable()) {
            return;
        }
        if (event.phase == TickEvent.Phase.END) {
            return;
        }
        tickCount++;
        // 保持死亡姿态新鲜，直到 Ragdollified 真正用它构造出布娃娃（多人下要等服务器包）
        RagdollifiedCompat.refreshPendingDeathPoses();
        // 玩家死亡时客户端不会收到 LivingDeathEvent（见 RagdollifiedDeathWatcher），只能观察死亡状态
        RagdollifiedDeathWatcher.tick();
        CustomTextureManager.tick();
        ClientModelService.current().ifPresent(ClientModelService::tick);
        // 枪械 LOD 的后台预热：未进世界时全速，进世界后限速并给按需构建让路。
        // 未安装 TaCZ 时它只做一次廉价判断。
        com.kinetic.client.compat.tacz.lod.GunLodPrewarm.tick();
        refreshRate = Minecraft.getInstance().getWindow().getRefreshRate();

        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.getCapability(PlayerAnimatableCapabilityProvider.CAP).ifPresent(capability -> {
                capability.handleRoamingVarsChanges();
                ClientProtocolGateway.tick(player, capability);
            });
        }
    }

    public static int getTickCount() {
        return tickCount;
    }

    public static int getRefreshRate() {
        return refreshRate;
    }

    /**
     * 动画求值频率：{@code min(刷新率, ClientConfig.MAX_ANIMATION_EVALUATION_RATE)}。
     *
     * <p>这是所有「按刷新率求值」路径应该用的入口；距离分档见下面的
     * {@link #getAnimationRateNear()} 等。上限设为 0 表示不设限。
     *
     * <p>求值频率只决定「多久采样一次姿态」，不决定动画时间——动画时间由
     * {@code AnimatableEntity.seekTime}（跟随实体年龄）推进，所以降低求值频率不会拖慢动画。
     */
    public static int getAnimationRefreshRate() {
        return AnimationRateTiers.base(refreshRate,
                ClientConfig.MAX_ANIMATION_EVALUATION_RATE.get());
    }

    /**
     * 距离阶梯的近处档位。所有档位都由 {@link AnimationRateTiers} 钳制成
     * {@code base >= near >= far >= hidden}，配置里写出反向数值也不会让远处更密。
     */
    public static int getAnimationRateNear() {
        return AnimationRateTiers.clamp(ClientConfig.ANIMATION_EVALUATION_RATE_NEAR.get(),
                getAnimationRefreshRate());
    }

    public static int getAnimationRateFar() {
        return AnimationRateTiers.clamp(ClientConfig.ANIMATION_EVALUATION_RATE_FAR.get(),
                getAnimationRateNear());
    }

    public static int getAnimationRateHidden() {
        return AnimationRateTiers.clamp(ClientConfig.ANIMATION_EVALUATION_RATE_HIDDEN.get(),
                getAnimationRateFar());
    }

    public static double getAnimationDistanceNear() {
        return ClientConfig.ANIMATION_EVALUATION_DISTANCE_NEAR.get();
    }

    /** 远处距离不会小于近处距离，否则近处档位为空。 */
    public static double getAnimationDistanceFar() {
        return AnimationRateTiers.farDistance(ClientConfig.ANIMATION_EVALUATION_DISTANCE_FAR.get(),
                ClientConfig.ANIMATION_EVALUATION_DISTANCE_NEAR.get());
    }
}
