package com.kinetic.client.gui.overlay;

import com.kinetic.client.gui.ExtraPlayerConfigScreen;
import com.kinetic.config.ExtraPlayerScreenConfig;
import com.kinetic.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

public class ExtraPlayerScreen implements IGuiOverlay {
    @Override
    public void render(ForgeGui gui, GuiGraphics graphics, float partialTick, int width, int height) {
        if (ExtraPlayerScreenConfig.DISABLE_PLAYER_RENDER.get()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        if (mc.screen instanceof ExtraPlayerConfigScreen) {
            return;
        }

        double posX = ExtraPlayerScreenConfig.PLAYER_POS_X.get();
        double posY = ExtraPlayerScreenConfig.PLAYER_POS_Y.get();
        float scale = ExtraPlayerScreenConfig.PLAYER_SCALE.get().floatValue();
        float yawOffset = ExtraPlayerScreenConfig.PLAYER_YAW_OFFSET.get().floatValue();

        RenderUtil.renderExtraPlayerEntity(graphics, player, posX, posY, scale, yawOffset, -500, mc.getFrameTime());
    }
}
