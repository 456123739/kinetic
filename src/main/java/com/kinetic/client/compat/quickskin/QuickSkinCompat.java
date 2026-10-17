package com.kinetic.client.compat.quickskin;

import net.minecraftforge.fml.ModList;

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
}
