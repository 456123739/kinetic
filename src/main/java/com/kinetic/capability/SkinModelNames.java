package com.kinetic.capability;

import com.kinetic.client.compat.quickskin.QuickSkinCompat;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

/**
 * "这个玩家现在该按哪个皮肤模型名判定粗/细手臂"的唯一入口。
 *
 * <p>三个调用方必须用同一份答案，否则会出现"本地已经切成细手臂，服务端却还存着宽"的矛盾：
 * {@link PlayerAnimatableCapability}（本地渲染）、{@link com.kinetic.network.forge.ClientProtocolGateway}
 * （把选择同步给服务端）。
 *
 * <p>来源按可用性分三级：
 * <ol>
 *   <li>Quick Skin 的外观服务记着这个 uuid 的模型（进世界后）；</li>
 *   <li>Quick Skin 在标题界面保存的皮肤与粗细偏好（进世界前就有）；</li>
 *   <li>原版 {@code AbstractClientPlayer#getModelName()}。</li>
 * </ol>
 * 第 2 级是关键：Quick Skin 的外观服务靠 {@code applyLook} 填充，而 {@code applyLook} 发生在
 * {@code CLIENT_PLAYER_JOIN}，KINETIC 的第一次判定可能早于它。
 */
@OnlyIn(Dist.CLIENT)
public final class SkinModelNames {
    private SkinModelNames() {
    }

    /** 该玩家当前应当使用的皮肤模型名（{@code "slim"} / {@code "default"} / {@code "classic"}）。 */
    public static String resolve(AbstractClientPlayer player) {
        var applied = QuickSkinCompat.playerModelName(player.getUUID());
        var chosen = applied != null ? applied : QuickSkinCompat.savedModelName();
        return prefer(chosen, player.getModelName());
    }

    /**
     * 在"皮肤模组给出的名字"和"原版皮肤名"之间选一个。
     *
     * <p>{@code "auto"} 表示尚未解析，交给原版取值（此时皮肤模组已经通过 mixin 把解析结果
     * 写进 {@code getSkin()}）。
     */
    static String prefer(@Nullable String quickSkinModelName, String vanillaModelName) {
        if (quickSkinModelName == null || quickSkinModelName.isEmpty()
                || "auto".equalsIgnoreCase(quickSkinModelName)) {
            return vanillaModelName;
        }
        return quickSkinModelName;
    }
}
