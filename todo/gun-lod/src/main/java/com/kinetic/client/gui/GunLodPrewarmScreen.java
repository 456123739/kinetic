package com.kinetic.client.gui.overlay;

import com.kinetic.client.compat.tacz.lod.GunLodPrewarm;
import com.kinetic.config.GunLodPrewarmScreenConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

/**
 * 枪械 LOD 后台预热的角落进度条。
 *
 * <p>只在"还有枪等着预热"时出现，全部热完自动消失；没有可预热内容（例如枪包都没变、整包复用）
 * 时根本不出现。它不显示也不影响预热——预热跑在 worker 线程上，与这个界面无关。
 *
 * <p>为了让它在游戏里不碍事，尺寸刻意很小（120×4 的条 + 一行文字），并且只画在配置选定的角落。
 */
public class GunLodPrewarmScreen implements IGuiOverlay {
    private static final int BAR_WIDTH = 120;
    private static final int BAR_HEIGHT = 4;
    private static final int MARGIN = 6;
    private static final int BORDER = 0xC0000000;
    private static final int TRACK = 0x80000000;
    private static final int FILL = 0xFF3FB950;
    private static final int TEXT = 0xFFE0E0E0;

    @Override
    public void render(ForgeGui gui, GuiGraphics guiGraphics, float partialTick,
                       int screenWidth, int screenHeight) {
        if (GunLodPrewarmScreenConfig.DISABLE_GUN_LOD_PREWARM_SCREEN.get()) {
            return;
        }
        if (!GunLodPrewarm.isActive()) {
            return;
        }
        var total = GunLodPrewarm.total();
        if (total <= 0) {
            return;
        }
        var done = Math.min(GunLodPrewarm.completed(), total);
        var text = Component.translatable("gui.kinetic.gun_lod_prewarm",
                        done, total)
                .withStyle(ChatFormatting.GRAY);

        var position = GunLodPrewarmScreenConfig.GUN_LOD_PREWARM_POSITION.get();
        var bottom = position == GunLodPrewarmScreenConfig.Position.BOTTOM_LEFT
                || position == GunLodPrewarmScreenConfig.Position.BOTTOM_CENTER
                || position == GunLodPrewarmScreenConfig.Position.BOTTOM_RIGHT;
        var right = position == GunLodPrewarmScreenConfig.Position.TOP_RIGHT
                || position == GunLodPrewarmScreenConfig.Position.BOTTOM_RIGHT;

        var textWidth = gui.getFont().width(text);
        var blockWidth = Math.max(BAR_WIDTH, textWidth);
        final int left;
        if (right) {
            left = screenWidth - MARGIN - blockWidth;
        } else if (position == GunLodPrewarmScreenConfig.Position.TOP_CENTER
                || position == GunLodPrewarmScreenConfig.Position.BOTTOM_CENTER) {
            left = (screenWidth - blockWidth) / 2;
        } else {
            left = MARGIN;
        }
        var textY = bottom ? screenHeight - MARGIN - BAR_HEIGHT - 2 - gui.getFont().lineHeight
                : MARGIN;
        var barY = bottom ? screenHeight - MARGIN - BAR_HEIGHT : MARGIN + gui.getFont().lineHeight + 2;

        guiGraphics.drawString(gui.getFont(), text, left, textY, TEXT, true);
        guiGraphics.fill(left - 1, barY - 1, left + blockWidth + 1, barY + BAR_HEIGHT + 1, BORDER);
        guiGraphics.fill(left, barY, left + blockWidth, barY + BAR_HEIGHT, TRACK);
        var filled = (int) ((long) blockWidth * done / total);
        if (filled > 0) {
            guiGraphics.fill(left, barY, left + filled, barY + BAR_HEIGHT, FILL);
        }
    }
}
