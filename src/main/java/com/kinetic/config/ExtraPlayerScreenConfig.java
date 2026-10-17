package com.kinetic.config;

import net.minecraftforge.common.ForgeConfigSpec;

public class ExtraPlayerScreenConfig {
    public static ForgeConfigSpec.BooleanValue DISABLE_PLAYER_RENDER;
    public static ForgeConfigSpec.IntValue PLAYER_POS_X;
    public static ForgeConfigSpec.IntValue PLAYER_POS_Y;
    public static ForgeConfigSpec.DoubleValue PLAYER_SCALE;
    public static ForgeConfigSpec.DoubleValue PLAYER_YAW_OFFSET;

    public static void init(ForgeConfigSpec.Builder builder) {
        builder.push("extra_player_render");

        builder.comment("Whether to hide the HUD model preview in the top-left corner.",
                "It costs a full extra model evaluation, native extract and draw on the render thread",
                "every frame, and because the HUD context is mutable it bypasses the animation rate",
                "limiter entirely. Disabled by default.",
                "The key was renamed from DisablePlayerRender so that config files written by older",
                "versions (which always contained DisablePlayerRender = false) also end up disabled.");
        DISABLE_PLAYER_RENDER = builder.define("DisableExtraPlayerRender", true);

        builder.comment("Player position x in screen");
        PLAYER_POS_X = builder.defineInRange("PlayerPosX", 10, 0, Integer.MAX_VALUE);

        builder.comment("Player position y in screen");
        PLAYER_POS_Y = builder.defineInRange("PlayerPosY", 10, 0, Integer.MAX_VALUE);

        builder.comment("Player scale in screen");
        PLAYER_SCALE = builder.defineInRange("PlayerScale", 40, 8.0, 360.0);

        builder.comment("Player yaw offset in screen");
        PLAYER_YAW_OFFSET = builder.defineInRange("PlayerYawOffset", 5, Double.MIN_VALUE, Double.MAX_VALUE);

        builder.pop();
    }
}