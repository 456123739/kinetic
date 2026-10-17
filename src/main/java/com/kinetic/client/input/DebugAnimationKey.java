package com.kinetic.client.input;

import com.kinetic.Kinetic;
import com.kinetic.client.gui.overlay.DebugAnimationScreen;
import com.kinetic.util.InputCheckUtil;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.settings.KeyModifier;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;


@Mod.EventBusSubscriber(value = Dist.CLIENT)
public class DebugAnimationKey {
    public static final KeyMapping DEBUG_ANIMATION_KEY = new KeyMapping("key.kinetic.debug_animation.desc",
            KeyConflictContext.IN_GAME, KeyModifier.ALT,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B,
            "key.category.kinetic");

    @SubscribeEvent
    public static void onKeyboardInput(InputEvent.Key event) {
        if (!Kinetic.isAvailable()) {
            return;
        }
        if (!InputCheckUtil.isInGame()) {
            return;
        }
        if (event.getAction() == GLFW.GLFW_PRESS && InputCheckUtil.keyIsMatch(event, DEBUG_ANIMATION_KEY)) {
            if (!DebugAnimationScreen.isEnabled()) {
                DebugAnimationScreen.enable();
            } else {
                DebugAnimationScreen.disable();
            }
        }
    }
}
