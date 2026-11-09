package com.kinetic.client.compat.quickskin;

import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Quick Skin 可选兼容层的门面。
 *
 * <p><b>隔离契约</b>：本类会在未安装 Quick Skin 的客户端上被加载，因此它的字节码里<b>不得</b>出现任何
 * {@code com.quickskin} 类型或包名——只允许做「是否安装」检查并把工作委托给 {@link QuickSkinCompatInner}。
 * 这条契约由 {@code QuickSkinCompatIsolationTest} 直接扫描常量池守护。
 *
 * <p>真正接触 Quick Skin 类型（这里用反射，见 {@link QuickSkinCompatInner}）的代码放在单独的类里，
 * 只有在确认安装后才会被加载。
 */
public final class QuickSkinCompat {
    private static final String MOD_ID = "quickskin";

    private static boolean installed;

    private QuickSkinCompat() {
    }

    /** 在客户端初始化阶段调用；未安装 Quick Skin 时是空操作。 */
    public static void init() {
        installed = ModList.get().isLoaded(MOD_ID);
        if (installed) {
            QuickSkinCompatInner.register();
        }
    }

    public static boolean isInstalled() {
        return installed;
    }

    /**
     * Quick Skin 记录的该玩家模型名（{@code "classic"} / {@code "slim"}）。
     *
     * <p>它比 {@code AbstractClientPlayer#getModelName()} 更早可用：后者要等 Quick Skin 在
     * {@code CLIENT_PLAYER_JOIN} 里把皮肤应用上去才会变成新值，而这里直接读它的外观服务。
     * 未安装、未绑定或拿到 {@code "auto"} 时返回 {@code null}，调用方应回退到原版取值。
     */
    @Nullable
    public static String playerModelName(@Nullable UUID playerId) {
        return installed ? QuickSkinCompatInner.playerModelName(playerId) : null;
    }

    /**
     * 标题界面选好的皮肤模型名（进世界前就有）。
     *
     * <p>给它存在的理由：{@link #playerModelName(UUID)} 依赖 Quick Skin 已经把外观应用到该 uuid，
     * 而那发生在 {@code CLIENT_PLAYER_JOIN}。玩家在标题界面设成细手臂再进世界时，这一条才是
     * 第一帧就能读到的值。
     */
    @Nullable
    public static String savedModelName() {
        return installed ? QuickSkinCompatInner.savedModelName() : null;
    }
}
