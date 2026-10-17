package com.kinetic.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 枪械 LOD 后台预热的进度显示。
 *
 * <p>预热本身完全跑在 worker 线程上、只写磁盘，不占启动时间也不阻塞渲染；这个进度条只是让
 * "后台在做什么"可见。全部预热完成后自动消失，没有可预热的内容时根本不出现。
 */
public class GunLodPrewarmScreenConfig {
    public static ForgeConfigSpec.BooleanValue DISABLE_GUN_LOD_PREWARM_SCREEN;
    public static ForgeConfigSpec.EnumValue<Position> GUN_LOD_PREWARM_POSITION;

    public static void init(ForgeConfigSpec.Builder builder) {
        builder.push("gun_lod_prewarm_screen");

        builder.comment("Whether to hide the gun LOD prewarm progress bar",
                "It only appears while the background prewarm still has guns to bake,",
                "and disappears by itself once everything is warm.");
        DISABLE_GUN_LOD_PREWARM_SCREEN = builder.define("DisableGunLodPrewarmScreen", false);

        builder.comment("Gun LOD prewarm progress bar position");
        GUN_LOD_PREWARM_POSITION = builder.defineEnum("GunLodPrewarmPosition", Position.BOTTOM_LEFT);

        builder.pop();
    }

    public enum Position {
        TOP_LEFT,
        TOP_CENTER,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_CENTER,
        BOTTOM_RIGHT
    }
}
