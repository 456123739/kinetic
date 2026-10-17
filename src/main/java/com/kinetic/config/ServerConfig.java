package com.kinetic.config;

import com.google.common.collect.Lists;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public class ServerConfig {
    public static ForgeConfigSpec.IntValue THREAD_COUNT;
    public static ForgeConfigSpec.IntValue BANDWIDTH_LIMIT;
    public static ForgeConfigSpec.IntValue DISPATCH_SOFT_LIMIT;
    public static ForgeConfigSpec.IntValue DISPATCH_HARD_LIMIT;
    public static ForgeConfigSpec.BooleanValue RESTRICTED_AUTH;
    public static ForgeConfigSpec.BooleanValue LOW_BANDWIDTH_USAGE;
    public static ForgeConfigSpec.BooleanValue CAN_SWITCH_MODEL;
    public static ForgeConfigSpec.ConfigValue<String> DEFAULT_MODEL_PATH;
    public static ForgeConfigSpec.ConfigValue<String> DEFAULT_MODEL_TEXTURE;

    // 禁止在玩家客户端 GUI 界面显示的模型相对路径
    public static ForgeConfigSpec.ConfigValue<List<String>> CLIENT_NOT_DISPLAY_MODEL_PATHS;

    /**
     * 动画优先级覆盖表，元素形如 {@code "<动画标识>=<数值>"}。
     *
     * <p>默认值来自随模组发布的 {@code serverconfig/kinetic_animation_priority.toml}；
     * 它是 SERVER 类型配置，因此会由 Forge 的配置同步机制（{@code ConfigSync}）在登录时
     * 自动下发到客户端，客户端动画选择使用同步过来的值。
     */
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> ANIMATION_PRIORITIES;

    public static ForgeConfigSpec init() {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        ServerConfig.init(builder);
        return builder.build();
    }

    private static void init(ForgeConfigSpec.Builder builder) {
        builder.comment("The relative path of the default model when a player first enters the game");
        builder.comment("The only builtin models are the vanilla player models: misc/2_steve (wide)"
                + " and misc/1_alex (slim). Slim skins select misc/1_alex automatically.");
        DEFAULT_MODEL_PATH = builder.define("DefaultModelPath", "misc/2_steve");

        builder.comment("The default model texture when a player first enters the game");
        builder.comment("Empty selects the texture declared by the model itself.");
        DEFAULT_MODEL_TEXTURE = builder.define("DefaultModelTexture", "");

        builder.comment("Whether or not players are allowed to switch models");
        CAN_SWITCH_MODEL = builder.define("CanSwitchModel", true);

        builder.comment("Relative model paths that are not displayed on the client model selection screen");
        builder.comment("Example: [\"model.mxc\", \"pack/private/legacy.kinetic\"]");
        CLIENT_NOT_DISPLAY_MODEL_PATHS = builder.define("ClientNotDisplayModelPaths", Lists.newArrayList());

        builder.comment("Animation priority overrides, one \"<animation>=<priority>\" per entry.");
        builder.comment("Higher wins; equal values fall back to the builtin order.");
        builder.comment("Accepts logical aliases (attack/walk/idle/sneak/parcool_slide/tacz_crawl),");
        builder.comment("exact animation ids (\"parcool:sliding\") and prefix keys ending with ':'.");
        builder.comment("Defaults are shipped in serverconfig/kinetic_animation_priority.toml.");
        builder.comment("This is a SERVER config, so Forge syncs it to clients at login.");
        ANIMATION_PRIORITIES = builder
                .comment("See serverconfig/kinetic_animation_priority.toml for the documented default table.")
                .defineListAllowEmpty("AnimationPriorities",
                        AnimationPriorityConfig.defaultEntries(),
                        entry -> entry instanceof String);

        builder.push("server_scheduler");

        builder.comment("Concurrent level for processing models. Value 0 means AUTO.");
        THREAD_COUNT = builder.defineInRange("ThreadCount", 0, 0, Math.max(2, Runtime.getRuntime().availableProcessors() - 1));

        builder.comment("Global model distribution limit in Mbps. 0 means unlimited.");
        BANDWIDTH_LIMIT = builder.defineInRange("BandwidthLimit", 5, 0, 999);

        builder.comment("Per-player queue size that enables one-fragment round-robin visits.");
        DISPATCH_SOFT_LIMIT = builder.defineInRange("DispatchSoftLimit", 24, 1, 255);

        builder.comment("Atomic per-player logical-packet admission cap.");
        DISPATCH_HARD_LIMIT = builder.defineInRange("DispatchHardLimit", 48, 2, 256);

        builder.comment("Restrict unauthorized auth-model icon and chunk downloads.");
        RESTRICTED_AUTH = builder.define("RestrictedAuth", false);

        builder.comment("Suppress network synchronization of partial features to reduce bandwidth usage");
        builder.comment("Only effective when there are tons of players");
        LOW_BANDWIDTH_USAGE = builder.define("LowBandwidthUsage", false);

        builder.pop();
    }
}
