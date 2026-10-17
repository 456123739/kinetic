package com.kinetic.client.event;

import com.kinetic.Kinetic;
import com.kinetic.client.skin.DefaultPlayerSkins;
import com.kinetic.event.api.SpecialPlayerRenderEvent;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;

@Mod.EventBusSubscriber(value = Dist.CLIENT)
@SuppressWarnings("removal")
public class VanillaPlayerRenderEvent {
    private static final String STEVE = "misc/2_steve";
    private static final String ALEX = "misc/1_alex";

    @SubscribeEvent
    public static void onRenderPlayer(SpecialPlayerRenderEvent event) {
        if (!Kinetic.isAvailable()) {
            return;
        }
        Player player = event.getPlayer();
        if (!isVanillaPlayer(event.getModelId()) || !(player instanceof AbstractClientPlayer clientPlayer)) {
            return;
        }

        // 优先采用玩家对象自身的皮肤。
        //
        // AbstractClientPlayer#getSkinTextureLocation 是皮肤类模组覆盖的 vanilla 入口：
        // Quick Skin 会在该方法 HEAD 处注入并返回它选中的皮肤纹理。若这里绕过它直接查
        // SkinManager + GameProfile，拿到的始终是 Mojang 原始皮肤，皮肤模组选中的皮肤就会失效。
        ResourceLocation fromPlayer = DefaultPlayerSkins.loadedSkinOf(clientPlayer);
        if (fromPlayer != null) {
            event.setTextureLocationOverride(fromPlayer);
            return;
        }

        // 玩家皮肤尚未就绪（拿到的仍是按 UUID 推断的默认皮肤）时，沿用原有的
        // SkinManager 查询与内置回退，保持与改动前一致的行为。
        Minecraft minecraft = Minecraft.getInstance();
        Map<MinecraftProfileTexture.Type, MinecraftProfileTexture> map =
                minecraft.getSkinManager().getInsecureSkinInformation(clientPlayer.getGameProfile());
        ResourceLocation location;
        if (map.containsKey(MinecraftProfileTexture.Type.SKIN)) {
            location = minecraft.getSkinManager()
                    .registerTexture(map.get(MinecraftProfileTexture.Type.SKIN), MinecraftProfileTexture.Type.SKIN);
        } else {
            location = DefaultPlayerSkins.forModelId(event.getModelId());
        }
        event.setTextureLocationOverride(location);
    }

    private static boolean isVanillaPlayer(String modelId) {
        return modelId.equals(STEVE) || modelId.equals(ALEX);
    }
}
