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
    /** TaCZ 枪械静态烘焙（实验性，默认关闭）。 */
    public static ForgeConfigSpec.BooleanValue TACZ_STATIC_MESH;
    public static ForgeConfigSpec.IntValue MAX_ANIMATION_EVALUATION_RATE;
    public static ForgeConfigSpec.IntValue ANIMATION_EVALUATION_DISTANCE_NEAR;
    public static ForgeConfigSpec.IntValue ANIMATION_EVALUATION_RATE_NEAR;
    public static ForgeConfigSpec.IntValue ANIMATION_EVALUATION_DISTANCE_FAR;
    public static ForgeConfigSpec.IntValue ANIMATION_EVALUATION_RATE_FAR;
    public static ForgeConfigSpec.IntValue ANIMATION_EVALUATION_RATE_HIDDEN;
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

        builder.comment("Bake TaCZ gun models into static meshes so a frame stops re-emitting their vertices.",
                "Measured on the bundled gun pack: per-gun CPU drops from 215-270 us to 2-8 us plus 11-16 draw calls.",
                "Experimental: any failure falls back to TaCZ's own path for that frame.",
                "Does not affect anything when Timeless and Classics Zero is absent.").push("tacz");
        TACZ_STATIC_MESH = builder.define("StaticGunMesh", false);
        builder.pop();

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
