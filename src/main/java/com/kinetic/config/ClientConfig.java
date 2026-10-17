package com.kinetic.config;

import com.kinetic.network.session.SessionMode;
import net.minecraftforge.common.ForgeConfigSpec;

public class ClientConfig {

    public static ForgeConfigSpec.BooleanValue DISCLAIMER_SHOW;
    public static ForgeConfigSpec.BooleanValue PRINT_ANIMATION_ROULETTE_MSG;
    public static ForgeConfigSpec.BooleanValue DISABLE_SELF_MODEL;
    public static ForgeConfigSpec.BooleanValue DISABLE_OTHER_MODEL;
    public static ForgeConfigSpec.BooleanValue DISABLE_SELF_HANDS;
    public static ForgeConfigSpec.BooleanValue DISABLE_PROJECTILE_MODEL;
    public static ForgeConfigSpec.BooleanValue DISABLE_VEHICLE_MODEL;
    public static ForgeConfigSpec.BooleanValue DISABLE_EXTERNAL_FIRST_PERSON_ANIM;
    public static ForgeConfigSpec.BooleanValue USE_COMPATIBILITY_RENDERER;
    public static ForgeConfigSpec.BooleanValue FORCE_CUTOUT_RENDERER;
    public static ForgeConfigSpec.BooleanValue CULL_BACK_FACES;
    public static ForgeConfigSpec.IntValue MAX_ANIMATION_EVALUATION_RATE;
    public static ForgeConfigSpec.IntValue ANIMATION_EVALUATION_DISTANCE_NEAR;
    public static ForgeConfigSpec.IntValue ANIMATION_EVALUATION_RATE_NEAR;
    public static ForgeConfigSpec.IntValue ANIMATION_EVALUATION_DISTANCE_FAR;
    public static ForgeConfigSpec.IntValue ANIMATION_EVALUATION_RATE_FAR;
    public static ForgeConfigSpec.IntValue ANIMATION_EVALUATION_RATE_HIDDEN;
    public static ForgeConfigSpec.IntValue OTHER_GUN_ITEM_LOD_DISTANCE;
    public static ForgeConfigSpec.DoubleValue OTHER_GUN_ITEM_LOD_EXTENT;
    public static ForgeConfigSpec.BooleanValue OTHER_GUN_ITEM_LOD_SKIP_ATTACHMENTS;
    public static ForgeConfigSpec.DoubleValue OTHER_GUN_ITEM_LOD_SCALE;
    public static ForgeConfigSpec.DoubleValue OTHER_GUN_ITEM_LOD_OFFSET_X;
    public static ForgeConfigSpec.DoubleValue OTHER_GUN_ITEM_LOD_OFFSET_Y;
    public static ForgeConfigSpec.DoubleValue OTHER_GUN_ITEM_LOD_OFFSET_Z;
    public static ForgeConfigSpec.IntValue OTHER_GUN_ANIM_LOD_DISTANCE;
    public static ForgeConfigSpec.DoubleValue SOUND_VOLUME;
    public static ForgeConfigSpec.BooleanValue SHOW_MODEL_ID_FIRST;
    public static ForgeConfigSpec.BooleanValue ENABLE_SOPHISTICATED_BACKPACK_COMPAT;
    public static ForgeConfigSpec.BooleanValue ENABLE_PARCOOL_COMPAT;
    public static ForgeConfigSpec.BooleanValue RAGDOLL_KINETIC_BODY;
    public static ForgeConfigSpec.BooleanValue RAGDOLL_SUB_BONE_PHYSICS;
    public static ForgeConfigSpec.BooleanValue WOUNDS_ENABLED;
    public static ForgeConfigSpec.BooleanValue WOUNDS_THIRD_PARTY_MODELS;
    public static ForgeConfigSpec.ConfigValue<String> WOUNDS_COLOR;
    public static ForgeConfigSpec.IntValue WOUNDS_TIERS;
    public static ForgeConfigSpec.IntValue WOUNDS_DENSITY;
    public static ForgeConfigSpec.DoubleValue WOUNDS_RENDER_DISTANCE;
    public static ForgeConfigSpec.BooleanValue REAL_CAMERA_MODEL_BINDING;
    public static ForgeConfigSpec.BooleanValue REAL_CAMERA_DEATH_RAGDOLL;
    public static ForgeConfigSpec.EnumValue<SessionMode> NETWORK_SESSION_MODE;

    public static ForgeConfigSpec init() {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        init(builder);
        ExtraPlayerScreenConfig.init(builder);
        LoadingStateScreenConfig.init(builder);
        GunLodPrewarmScreenConfig.init(builder);
        return builder.build();
    }

    public static void init(ForgeConfigSpec.Builder builder) {
        builder.push("general");

        builder.comment("Whether to display disclaimer GUI");
        DISCLAIMER_SHOW = builder.define("DisclaimerShow", true);

        builder.comment("Whether to print animation roulette play message");
        PRINT_ANIMATION_ROULETTE_MSG = builder.define("PrintAnimationRouletteMsg", false);

        builder.comment("Prevents rendering of self player's model");
        DISABLE_SELF_MODEL = builder.define("DisableSelfModel", false);

        builder.comment("Prevents rendering of other player's model");
        DISABLE_OTHER_MODEL = builder.define("DisableOtherModel", false);

        builder.comment("Prevents rendering of self player's hand");
        DISABLE_SELF_HANDS = builder.define("DisableSelfHands", false);

        builder.comment("Prevents rendering of projectile model");
        DISABLE_PROJECTILE_MODEL = builder.define("DisableProjectileModel", false);

        builder.comment("Prevents rendering of vehicle model");
        DISABLE_VEHICLE_MODEL = builder.define("DisableVehicleModel", false);

        builder.comment("Disable first person animation from other mods.");
        DISABLE_EXTERNAL_FIRST_PERSON_ANIM = builder.define("DisableExternalFirstPersonAnim", false);

        builder.comment("If rendering errors occur, try turning on this.");
        USE_COMPATIBILITY_RENDERER = builder.define("UseCompatibilityRenderer", false);

        builder.comment("Draw player models with the same alpha-cutout render type vanilla uses,",
                "instead of the translucent (blended, depth-write-off) path.",
                "The translucent path forces every visible model to be sorted on every batch flush",
                "and to be shaded with blending and no depth rejection, which is the largest known",
                "frame cost when many models are on screen.",
                "Turn this off only if a model relies on genuinely semi-transparent texels.");
        FORCE_CUTOUT_RENDERER = builder.define("ForceCutoutRenderer", true);

        builder.comment("Enable back-face culling for KINETIC models (player, ragdoll, gun LOD).",
                "The native baker already emits only the 3 front faces of every closed box, so for a",
                "fully closed model this toggle adds nothing; it only culls the geometry the baker",
                "deliberately left double-sided (cubes with missing faces, and alpha-cutout quads).",
                "Offline verification (analysis/backface_cull_geom_check.py, _lod_check.py) on the four",
                "tacz fixtures: a fully closed model (eos_bayonet, 399/399 boxes) is pixel-identical",
                "with culling on, but packs with partial/hollow cubes regress -- ak47 loses 169 px,",
                "hk416d 26 px, eos_helenas_nail 2083 px of silhouette on the full model (2550 px on the",
                "LOD). Those faces are single-sided by design and the back side is meant to be drawn.",
                "Default off so packs cannot break; turn it on for known fully closed models.");
        CULL_BACK_FACES = builder.define("CullBackFaces", false);

        builder.comment("Upper bound for how many times per second an entity's animation is evaluated.",
                "This is the near/on-screen base rate: effective = min(display refresh rate, this).",
                "0 = uncapped (follow the refresh rate).",
                "60 is smooth enough for animation, so a 144 Hz display no longer evaluates every",
                "visible player 144 times per second. Weak machines can lower it to 30.",
                "The distance tiers below are clamped so they never exceed this value.");
        MAX_ANIMATION_EVALUATION_RATE = builder.defineInRange("MaxAnimationEvaluationRate", 60, 0, 360);

        builder.comment("=== Animation evaluation rate ladder (by distance from the local player) ===",
                "Evaluation only decides how often the pose is re-sampled; animation time keeps",
                "advancing with the entity's age, so a lower rate plays the same animation at the",
                "same speed with fewer samples (coarser, not slower).",
                "Values are clamped at use time so the effective ladder is non-increasing with",
                "distance and never exceeds MaxAnimationEvaluationRate:",
                "  near = min(RateNear, maxRate-based base),",
                "  far  = min(RateFar, near),  hidden = min(RateHidden, far),",
                "  DistanceFar = max(DistanceFar, DistanceNear).",
                "Write whatever you like; the game will clamp it. 1 is the lowest usable rate.");

        builder.comment("Distance in blocks beyond which AnimationEvaluationRateNear is used.");
        ANIMATION_EVALUATION_DISTANCE_NEAR = builder.defineInRange("AnimationEvaluationDistanceNear", 40, 1, 512);

        builder.comment("Evaluation rate for entities between DistanceNear and DistanceFar blocks.");
        ANIMATION_EVALUATION_RATE_NEAR = builder.defineInRange("AnimationEvaluationRateNear", 20, 1, 360);

        builder.comment("Distance in blocks beyond which AnimationEvaluationRateFar is used.");
        ANIMATION_EVALUATION_DISTANCE_FAR = builder.defineInRange("AnimationEvaluationDistanceFar", 64, 1, 512);

        builder.comment("Evaluation rate for entities farther than DistanceFar blocks.");
        ANIMATION_EVALUATION_RATE_FAR = builder.defineInRange("AnimationEvaluationRateFar", 10, 1, 360);

        builder.comment("Evaluation rate for entities that were not rendered in the previous frame",
                "(off-screen, out of range, or removed by EntityCulling).");
        ANIMATION_EVALUATION_RATE_HIDDEN = builder.defineInRange("AnimationEvaluationRateHidden", 10, 1, 360);

        builder.push("other_player_gun_lod");
        builder.comment("=== 物品 LOD 与动画 LOD 是两个独立开关 ===");

        builder.comment("物品 LOD：其他玩家手持枪械从多少格开始改用 KINETIC 自建的简化模型。",
                "0 = 始终使用简化模型（默认；别人手里的枪本来就看不清细节）。",
                "你自己的枪、以及非枪械物品完全不受影响。",
                "TaCZ 自带的 GunLodRenderDistance 对缺少 lod 字段的第三方枪包无效，这里补上那一块。");
        OTHER_GUN_ITEM_LOD_DISTANCE = builder.defineInRange("OtherPlayerGunItemLodDistance", 0, 0, 256);

        builder.comment("简化程度：按「零件体量」整体取舍。",
                "体量 = max(该零件所有方块里最长的一条边, sqrt(该零件表面积)/2)，单位是 1/16 格。",
                "两个量缺一不可：只看最长边会删掉由许多小方块拼成的零件（例如 hk416d 的握把），",
                "只看表面积会删掉又细又长的枪管。",
                "以零件为单位而不是以方块为单位，是为了避免把零件削成碎片——碎片会悬空、也会让枪看起来像一叠散板。",
                "本值是【绝对下限】：体量小于它的零件一定消失。真正的取舍阈值还会按每个模型自适应",
                "（相对分位 + 面数预算），这样不同枪包都会落到同一档削减幅度，不需要为某个包单独调参。",
                "3.0（默认）保留枪管/机匣/枪托/握把/弹匣等可辨识特征，丢掉准星、侧导轨、螺丝等细节；",
                "调小更保真，调大更省顶点。改完做一次资源重载（F3+T）即可生效。");
        OTHER_GUN_ITEM_LOD_EXTENT = builder.defineInRange("OtherPlayerGunItemLodExtent", 3.0, 0.0, 16.0);


        builder.comment("是否连枪械配件一起不渲染（弹匣、瞄具、护木、枪口装置等可安装配件）。",
                "打开后远处玩家手里的枪只剩枪身，能再省一大块顶点，但弹匣这类标志性部件也会消失。",
                "默认关闭：远处仍能靠弹匣/枪管轮廓分辨武器。");
        OTHER_GUN_ITEM_LOD_SKIP_ATTACHMENTS = builder.define("OtherPlayerGunItemLodSkipAttachments", false);

        builder.comment("物品 LOD 的额外缩放倍率，最终缩放 = 该枪 display 里声明的 thirdperson 缩放 × 本值。",
                "简化模型用的是枪本身的骨骼坐标，摆放位置与 TaCZ 内部的原点路径变换是近似关系，",
                "如果觉得远处的枪偏大/偏小，只调这一个值即可（不影响你自己手里的枪）。");
        OTHER_GUN_ITEM_LOD_SCALE = builder.defineInRange("OtherPlayerGunItemLodScale", 1.0, 0.05, 5.0);

        builder.comment("简化模型相对手部 locator 的额外平移（单位：格），用于在游戏里微调位置。",
                "默认 0：模型自带的 thirdperson_hand 骨骼会被摆到 locator 原点上，",
                "所以正常情况下不需要动这三个值。只有当某个枪包的骨骼命名不符合 TaCZ 约定、",
                "看起来明显偏位时才需要用它补偿。");
        OTHER_GUN_ITEM_LOD_OFFSET_X = builder.defineInRange("OtherPlayerGunItemLodOffsetX", 0.0, -2.0, 2.0);
        OTHER_GUN_ITEM_LOD_OFFSET_Y = builder.defineInRange("OtherPlayerGunItemLodOffsetY", 0.0, -2.0, 2.0);
        OTHER_GUN_ITEM_LOD_OFFSET_Z = builder.defineInRange("OtherPlayerGunItemLodOffsetZ", 0.0, -2.0, 2.0);

        builder.comment("动画 LOD：其他玩家手持枪械超过多少格后不再运行 TaCZ 的换弹/拉栓等状态机动画，",
                "只保留持枪等基本姿态。0 = 关闭该优化（默认 32 格）。",
                "枪械动画状态机是逐实体每帧驱动的，远处玩家停掉它能同时省 CPU 与顶点量。");
        OTHER_GUN_ANIM_LOD_DISTANCE = builder.defineInRange("OtherPlayerGunAnimLodDistance", 32, 0, 256);
        builder.pop();

        builder.comment("The amount of volume when the animation is played.");
        SOUND_VOLUME = builder.defineInRange("SoundVolume", 100.0, 0.0, 100.0);

        builder.comment("Whether to display model ID first in the model selection screen, instead of the model name filled in by the model author.");
        SHOW_MODEL_ID_FIRST = builder.define("ShowModelIdFirst", false);

        builder.pop();

        builder.push("network");
        builder.comment("AUTO uses the game-server KINETIC session when its channel is present.");
        builder.comment("LOCAL declines the server session and keeps the local catalog.");
        NETWORK_SESSION_MODE = builder.defineEnum("SessionMode", SessionMode.AUTO);
        builder.pop();

        builder.push("Integration");
        ENABLE_SOPHISTICATED_BACKPACK_COMPAT = builder.define("SophisticatedBackpack", true);
        ENABLE_PARCOOL_COMPAT = builder.define("Parcool", true);
        builder.pop();

        builder.push("Ragdollified");
        builder.comment("Draw the death ragdoll with the player's KINETIC model instead of the vanilla humanoid body.",
                "The physics still comes from Ragdollified; KINETIC only supplies the skeleton and the sub-bone motion.");
        RAGDOLL_KINETIC_BODY = builder.define("KineticBody", true);

        builder.comment("Let KINETIC drive the sub-bones (forearms, hands, lower legs, feet, cape) with damped springs,",
                "so joints keep moving after death instead of freezing in the death pose.");
        RAGDOLL_SUB_BONE_PHYSICS = builder.define("SubBonePhysics", true);

        builder.comment("The ragdoll draw distance and lifetime are NOT configured here on purpose.",
                "Ragdollified owns both (render.renderDistance and gameplay.ragdollLifetime); it also decides",
                "when to stop drawing its own body. When KINETIC used its own, smaller distance, ragdolls",
                "switched back to the vanilla humanoid body at that radius while Ragdollified still believed",
                "KINETIC was drawing them -- a visible pop, and in the other direction an invisible ragdoll.",
                "KINETIC now reads both values from Ragdollified so there is exactly one authority.");
        builder.pop();

        builder.push("RealCamera");
        builder.comment("Resolve Real Camera's camera binding from the KINETIC model's own baked geometry",
                "and bone poses instead of letting it re-render the model 4-8 times per frame to catch",
                "the vertices. Same result, no extra draw calls, and it also works on frames where the",
                "model was not drawn (which is what made the binding drop out and Real Camera disable",
                "itself for a moment).",
                "Turn it off only to compare against Real Camera's own path; the fallback is automatic",
                "whenever KINETIC has no model to offer.");
        REAL_CAMERA_MODEL_BINDING = builder.define("ModelBinding", true);

        builder.comment("After death, keep the Real Camera binding on the ragdoll instead of the hidden",
                "player model, so the death view follows the corpse. Requires Ragdollified with its KINETIC",
                "body takeover active; the provider's own death camera, when enabled, still has the last",
                "word on the camera position.");
        REAL_CAMERA_DEATH_RAGDOLL = builder.define("DeathRagdollBinding", true);
        builder.pop();

        builder.push("Wounds");
        builder.comment("Draw blood wounds on damaged players. Purely cosmetic and local: nothing is sent",
                "over the network, and the effect is computed from the health the client already knows.",
                "Automatically disabled while Visual Health is installed, so the two never stack.");
        WOUNDS_ENABLED = builder.define("Enabled", true);

        builder.comment("Also draw wounds on models that are not the builtin wide/slim player model.",
                "Off by default: third-party models can use any texture layout, so a wound mask built for",
                "the vanilla 64x64 layout lands wherever their UVs happen to point.",
                "A one-time log line says so when a third-party model is skipped.");
        WOUNDS_THIRD_PARTY_MODELS = builder.define("ThirdPartyModels", false);

        builder.comment("Wound colour, as RRGGBB or #RRGGBB. The wound mask is greyscale and is multiplied",
                "by this colour, so darker parts of a bullet hole stay darker than the bleeding edge.");
        WOUNDS_COLOR = builder.define("Color", "9F0000");

        builder.comment("How many damage steps the wounds go through, 2 to 10.",
                "Step 0 is full health (nothing drawn); the last step is death.");
        WOUNDS_TIERS = builder.defineInRange("Tiers", 5, 2, 10);

        builder.comment("How many wounds the last step gets, in percent (10 to 100).",
                "Lower it if the wounds read as noise on a busy server.");
        WOUNDS_DENSITY = builder.defineInRange("Density", 50, 10, 100);

        builder.comment("Distance in blocks beyond which wounds are not drawn. Keep it modest: at long range",
                "a wound is smaller than a screen pixel and only aliases.");
        WOUNDS_RENDER_DISTANCE = builder.defineInRange("RenderDistance", 48.0, 8.0, 256.0);

        builder.comment("There is deliberately no \"derive the colour from the player's skin\" option.",
                "On a multiplayer server a player's skin is downloaded into an HttpTexture, which uploads",
                "the image and then closes it; nothing in the client keeps the pixels around. Sampling",
                "would therefore silently fall back to Color for exactly the servers it was meant for.",
                "Change Color instead if the default red does not fit.");
        builder.pop();
    }
}
