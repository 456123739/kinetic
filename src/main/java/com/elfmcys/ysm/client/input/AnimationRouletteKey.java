package com.elfmcys.ysm.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.settings.KeyModifier;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * 动作轮盘的快捷键定义。
 *
 * <p>产品要求禁止用户使用动画轮盘，因此 {@code Z} 与 {@code Alt+L} 都不再在
 * {@code ClientSetupEvent#onRegisterKeyMappings} 注册，也不再有打开
 * {@code AnimationRouletteScreen} 或切换轮盘锁定状态的输入处理逻辑。
 * {@code AnimationRouletteScreen} 本身保留；常量继续保留是因为界面仍用
 * {@code ANIMATION_ROULETTE_KEY} 判断“再次按下该键关闭轮盘”。重新启用入口只需恢复
 * 注册与输入处理。
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT)
public class AnimationRouletteKey {
    public static final KeyMapping ANIMATION_ROULETTE_KEY = new KeyMapping("key.yes_steve_model.animation_roulette.desc",
            KeyConflictContext.IN_GAME,
            KeyModifier.NONE,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_Z,
            "key.category.yes_steve_model");

    public static final KeyMapping LOCK_ROULETTE_KEY = new KeyMapping("key.yes_steve_model.lock_roulette.desc",
            KeyConflictContext.IN_GAME,
            KeyModifier.ALT,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_L,
            "key.category.yes_steve_model");
}
