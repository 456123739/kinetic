package com.kinetic.client.input;

import com.kinetic.Kinetic;
import com.kinetic.capability.PlayerAnimatableCapabilityProvider;
import com.kinetic.client.event.PlayerMoveEvent;
import com.kinetic.network.forge.ClientProtocolGateway;
import com.kinetic.util.InputCheckUtil;
import com.google.common.collect.Lists;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.settings.KeyModifier;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import java.util.List;

@Mod.EventBusSubscriber(value = Dist.CLIENT)
public class ExtraAnimationKey {
    public static final List<KeyMapping> EXTRA_ANIMATION_KEYS = Lists.newArrayList();

    @SubscribeEvent
    public static void registerKeyBinding(RegisterKeyMappingsEvent event) {
        if (!Kinetic.isAvailable()) {
            return;
        }
        for (int i = 0; i <= 7; i++) {
            String name = String.format("key.kinetic.extra_animation.%d.desc", i);
            KeyMapping keyMapping = new KeyMapping(name,
                    KeyConflictContext.IN_GAME,
                    KeyModifier.NONE,
                    InputConstants.Type.KEYSYM,
                    GLFW.GLFW_KEY_UNKNOWN,
                    "key.category.kinetic");
            event.register(keyMapping);
            EXTRA_ANIMATION_KEYS.add(keyMapping);
        }
    }

    @SubscribeEvent
    public static void onKeyboardInput(InputEvent.Key event) {
        if (!Kinetic.isAvailable()) {
            return;
        }
        if (!InputCheckUtil.isInGame()) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        for (KeyMapping key : EXTRA_ANIMATION_KEYS) {
            if (event.getAction() == GLFW.GLFW_PRESS && InputCheckUtil.keyIsMatch(event, key)
                && player != null && !PlayerMoveEvent.isMoveKey(player)) {
                player.getCapability(PlayerAnimatableCapabilityProvider.CAP).ifPresent(cap -> {
                    var model = cap.getModelRenderTarget();
                    if (model == null) {
                        // 模型还在加载 / 被禁用 / 刚重生：按键不应该把输入线程带崩。
                        return;
                    }
                    int index = EXTRA_ANIMATION_KEYS.indexOf(key);
                    var info = model.info();
                    var animations = info.getExtraAnimations();
                    if (animations.size() > index) {
                        String keyName = animations.get(index).key();
                        if ("#return".equals(keyName)) {
                            // #return 为停止播放轮盘动画
                            ClientProtocolGateway.stopSelfAnimation();
                        } else if (keyName.startsWith("#")) {
                            // 分类键原本打开动作轮盘；轮盘入口已禁用，因此这里不再打开任何界面。
                            // 对应的轮盘动画定义仍保留在模型资源中，供以后恢复入口时使用。
                            return;
                        } else {
                            ClientProtocolGateway.playSelfAnimation(keyName);
                        }
                    }
                });
                return;
            }
        }
    }
}
