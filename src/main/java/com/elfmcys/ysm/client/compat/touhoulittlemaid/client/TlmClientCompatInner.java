package com.elfmcys.ysm.client.compat.touhoulittlemaid.client;

import com.elfmcys.ysm.client.compat.touhoulittlemaid.capability.YsmMaidCapabilityProvider;
import com.elfmcys.ysm.client.compat.touhoulittlemaid.client.event.SyncCapability;
import com.elfmcys.ysm.client.compat.touhoulittlemaid.client.event.UpdateRemoteStruct;
import com.elfmcys.ysm.client.compat.touhoulittlemaid.client.event.YsmMaidTickEvent;
import com.elfmcys.ysm.client.compat.touhoulittlemaid.client.render.CustomYsmMaidRenderer;
import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.EntityMaidRenderer;
import com.github.tartaricacid.touhoulittlemaid.entity.item.EntityChair;
import com.github.tartaricacid.touhoulittlemaid.entity.item.EntitySit;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.IGeoEntityRenderer;
import com.github.tartaricacid.touhoulittlemaid.item.ItemHakureiGohei;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.MinecraftForge;
import org.apache.commons.lang3.StringUtils;

@OnlyIn(Dist.CLIENT)
public class TlmClientCompatInner {
    private static CustomYsmMaidRenderer customYsmMaidRenderer;

    static void registerEvent() {
        // YsmMaidScreenEvent（女仆模型选择界面，PlayerModelScreen 的子类）入口已禁用，
        // 不再注册；模型选择界面整体不可由用户打开。MaidModelScreen 类保留。
        MinecraftForge.EVENT_BUS.register(new SyncCapability());
        MinecraftForge.EVENT_BUS.register(new YsmMaidTickEvent());
        MinecraftForge.EVENT_BUS.register(new UpdateRemoteStruct());
    }

    @SuppressWarnings("all")
    static void registerYsmEntityMaidRenderer() {
        EntityMaidRenderer.YSM_ENTITY_MAID_RENDERER = (manager) -> (IGeoEntityRenderer) (customYsmMaidRenderer = new CustomYsmMaidRenderer(manager));
    }

    static boolean isMaid(Entity entity) {
        return entity instanceof EntityMaid;
    }

    static boolean hasMaidCap(Entity entity) {
        if (entity instanceof EntityMaid maid) {
            return maid.getCapability(YsmMaidCapabilityProvider.CAP).isPresent() && maid.isYsmModel();
        }
        return false;
    }

    static void releaseAnimatable(Entity entity) {
        if (entity instanceof EntityMaid maid) {
            maid.getCapability(YsmMaidCapabilityProvider.CAP)
                    .ifPresent(capability -> capability.reset());
        }
    }

    static boolean isChair(Entity entity) {
        return entity instanceof EntityChair;
    }

    static boolean isSit(Entity entity) {
        return entity instanceof EntitySit;
    }

    static String getChairId(Entity entity) {
        if (entity instanceof EntityChair chair) {
            return chair.getModelId();
        }
        return StringUtils.EMPTY;
    }

    static boolean maidIsFishing(LivingEntity entity) {
        return entity instanceof EntityMaid maid && maid.fishing != null;
    }

    static void markTacGunAnimationNeedReload(LivingEntity entity) {
        entity.getCapability(YsmMaidCapabilityProvider.CAP).ifPresent(cap -> {
            cap.setTacGunAnimationNeedReload(true);
        });
    }

    static boolean isGohei(Item item) {
        return item instanceof ItemHakureiGohei;
    }

    static CustomYsmMaidRenderer getRenderer() {
        return customYsmMaidRenderer;
    }
}
