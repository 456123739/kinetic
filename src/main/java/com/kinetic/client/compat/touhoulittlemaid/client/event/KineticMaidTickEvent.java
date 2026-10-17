package com.kinetic.client.compat.touhoulittlemaid.client.event;

/*
 * 本文件出现的 Ysm / YSM 拼写属于 TouhouLittleMaid 的公开 API
 * （com.github.tartaricacid.touhoulittlemaid.* 下的类型、字段与方法），
 * 不是本模组自己的命名。引用第三方 API 必须逐字保留其拼写，改成 Kinetic
 * 会导致 NoClassDefFoundError / NoSuchFieldError / NoSuchMethodError。
 */

import com.kinetic.client.compat.touhoulittlemaid.capability.KineticMaidCapabilityProvider;
import com.github.tartaricacid.touhoulittlemaid.compat.ysm.event.YsmMaidClientTickEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.eventbus.api.SubscribeEvent;

@OnlyIn(Dist.CLIENT)
public class KineticMaidTickEvent {
    @SubscribeEvent
    public void onTickKineticMaid(YsmMaidClientTickEvent event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        EntityMaid maid = event.getMaid();
        if (player.getUUID().equals(maid.getOwnerUUID())) {
            submitRoamingVariableChanges(maid);
        }
    }

    private void submitRoamingVariableChanges(EntityMaid maid) {
        maid.getCapability(KineticMaidCapabilityProvider.CAP).ifPresent(cap -> {
            // TODO
        });
    }
}
