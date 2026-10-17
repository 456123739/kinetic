package com.kinetic.client.compat.touhoulittlemaid.client.input;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.EntityHitResult;

/**
 * 女仆动作轮盘的输入入口。
 *
 * <p>动作轮盘界面已按产品要求禁用，因此 {@code onRouletteMainKeyPressed} 被移除：
 * 这里不再有任何打开 {@code AnimationRouletteScreen} 的调用。
 * {@link #pointToMaid()} 作为“视线是否指向自己的女仆”的判定保留，供以后恢复入口时复用。
 */
public class OpenRouletteScreen {
    public static boolean pointToMaid() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }
        if (!(Minecraft.getInstance().hitResult instanceof EntityHitResult result)) {
            return false;
        }
        if (result.getEntity() instanceof EntityMaid maid) {
            if (!maid.isYsmModel()) {
                return false;
            }
            return player.getUUID().equals(maid.getOwnerUUID());
        }
        return false;
    }
}
