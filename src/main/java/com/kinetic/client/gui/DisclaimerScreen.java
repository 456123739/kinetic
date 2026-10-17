package com.kinetic.client.gui;

import com.kinetic.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

public class DisclaimerScreen extends Screen {
    private Checkbox readCheckbox;
    private int x;
    private int y;

    public DisclaimerScreen() {
        super(Component.literal("Disclaimer GUI"));
    }

    @Override
    protected void init() {
        this.clearWidgets();

        MutableComponent mainText = Component.translatable("gui.kinetic.disclaimer.text");
        List<FormattedCharSequence> splitMainText = font.split(mainText, 400);
        int totalHeight = splitMainText.size() * font.lineHeight + 20 + 20 + 10 + 20;
        this.x = (width - 400) / 2;
        this.y = (height - totalHeight) / 2;

        MutableComponent readCheckboxText = Component.translatable("gui.kinetic.disclaimer.read");
        int readTextWidth = font.width(readCheckboxText);
        readCheckbox = new Checkbox((width - readTextWidth) / 2, y + totalHeight - 50, readTextWidth, 20, readCheckboxText, !ClientConfig.DISCLAIMER_SHOW.get());
        addRenderableWidget(readCheckbox);
        addRenderableWidget(new Button.Builder(Component.translatable("gui.kinetic.disclaimer.close"), b -> {
            if (readCheckbox.selected()) {
                ClientConfig.DISCLAIMER_SHOW.set(false);
                // 模型选择界面入口已禁用，这里不再跳转到 PlayerModelScreen，只关闭当前界面。
                Minecraft.getInstance().setScreen(null);
            } else {
                Minecraft.getInstance().setScreen(null);
            }
        }).size(300, 20).pos((width - 300) / 2, y + totalHeight - 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int pMouseX, int pMouseY, float pPartialTick) {
        renderBackground(graphics);
        graphics.drawWordWrap(font, Component.translatable("gui.kinetic.disclaimer.text"), x, y, 400, 0xffffffff);
        super.render(graphics, pMouseX, pMouseY, pPartialTick);
    }
}
