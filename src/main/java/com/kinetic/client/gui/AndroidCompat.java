package com.kinetic.client.gui;

import com.kinetic.Kinetic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 为了兼容 Android 平台运行 Java 版 MC
 * <p>
 * 设计此类用于一些 Android 特有的兼容性处理
 */
public class AndroidCompat {
    public static boolean isAndroid() {
        return Kinetic.isMobilePlatform();
    }

    /**
     * Android 平台的暂停界面附加按钮。
     *
     * <p>模型选择按钮（原 Alt+Y 的移动端等价入口）与动作轮盘按钮已按产品要求移除：
     * 两个界面都不再有任何用户可达入口。这里只保留额外玩家渲染配置按钮。
     */
    @Nullable
    public static List<Button> addKineticSkinButton(PauseScreen screen) {
        if (AndroidCompat.isAndroid()) {
            Minecraft mc = Minecraft.getInstance();

            Component configIcon = Component.literal("\uD83D\uDD27");
            Button extraPlayerButton = Button.builder(configIcon, button -> mc.setScreen(new ExtraPlayerConfigScreen()))
                    .bounds(screen.width / 2 - 120, screen.height - 35, 50, 30).build();
            extraPlayerButton.setTooltip(Tooltip.create(Component.translatable("key.kinetic.open_extra_player_render.desc")));

            return List.of(extraPlayerButton);
        }
        return null;
    }
}
