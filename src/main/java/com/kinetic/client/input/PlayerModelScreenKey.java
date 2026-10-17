package com.kinetic.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.settings.KeyModifier;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * 模型选择界面的快捷键定义。
 *
 * <p>产品要求禁止用户通过 UI 切换模型，因此 {@code Alt+Y} 不再在
 * {@code ClientSetupEvent#onRegisterKeyMappings} 注册，也不再有任何打开
 * {@code PlayerModelScreen} 的输入处理逻辑。{@code PlayerModelScreen} 本身保留；
 * 常量继续保留是因为界面仍用它判断“再次按下该键关闭界面”的行为。重新启用入口只需
 * 恢复注册与输入处理。
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT)
public class PlayerModelScreenKey {
    public static final KeyMapping PLAYER_MODEL_KEY = new KeyMapping("key.kinetic.player_model.desc",
            KeyConflictContext.IN_GAME,
            KeyModifier.ALT,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_Y,
            "key.category.kinetic");
}
