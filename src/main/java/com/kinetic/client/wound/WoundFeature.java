package com.kinetic.client.wound;

import com.kinetic.Kinetic;
import com.kinetic.config.ClientConfig;
import com.kinetic.model.catalog.builtin.BuiltinModelIndex;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

/**
 * 受伤外观的开关与参数：把配置、共存判断、模型适用范围收在一个地方，渲染层只问结论。
 *
 * <p><b>为什么和 Visual Health 互斥</b>：两个模组做的是同一件事，同时开就是两层血迹叠在一起，
 * 颜色和数量都会失控。检测到它就整体让位，并记一条日志说明原因——否则用户只会觉得功能没生效。
 *
 * <p><b>为什么默认只画内置玩家模型</b>：内置的宽/细手臂模型严格使用原版 64×64 皮肤布局，
 * 遮罩的位置就是确定的。第三方模型的 UV 布局可以是任意坐标，遮罩落上去就是随机斑点。
 * 想给第三方模型开，用 {@code Wounds.ThirdPartyModels}；跳过时记一次日志，避免用户以为坏了。
 */
public final class WoundFeature {
    private static final String VISUAL_HEALTH_MOD_ID = "visual_health";
    private static final int FALLBACK_COLOR = 0xFF9F0000;
    private static final double DEFAULT_DISTANCE = 48.0;

    private static volatile Boolean visualHealthPresent;
    /** 已经提示过「这个模型默认不画伤口」的模型，避免刷屏。 */
    private static final Set<String> LOGGED_SKIPS = new HashSet<>();

    private WoundFeature() {
    }

    /** 整个特性是否生效。 */
    public static boolean enabled() {
        if (visualHealthPresent()) {
            return false;
        }
        try {
            return ClientConfig.WOUNDS_ENABLED.get();
        } catch (RuntimeException ignored) {
            // 配置还没加载（早期渲染、数据生成）时按关闭处理，不要拿默认值去画。
            return false;
        }
    }

    /** Visual Health 是否在场；结果缓存，{@code ModList} 在启动后就固定了。 */
    public static boolean visualHealthPresent() {
        Boolean cached = visualHealthPresent;
        if (cached != null) {
            return cached;
        }
        boolean present = false;
        try {
            present = ModList.get() != null && ModList.get().isLoaded(VISUAL_HEALTH_MOD_ID);
        } catch (RuntimeException ignored) {
            // ModList 还没建好：这一次当作不在场，不缓存，下次再问。
            return false;
        }
        visualHealthPresent = present;
        if (present) {
            Kinetic.LOGGER.info("Visual Health is installed; KINETIC's own wound overlay stays off "
                    + "so the two do not stack. Disable Visual Health to use KINETIC's version.");
        }
        return present;
    }

    public static boolean includeThirdPartyModels() {
        try {
            return ClientConfig.WOUNDS_THIRD_PARTY_MODELS.get();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public static int tiers() {
        try {
            return WoundTier.clampTiers(ClientConfig.WOUNDS_TIERS.get());
        } catch (RuntimeException ignored) {
            return WoundTier.DEFAULT_TIERS;
        }
    }

    public static int density() {
        try {
            return Math.max(10, Math.min(100, ClientConfig.WOUNDS_DENSITY.get()));
        } catch (RuntimeException ignored) {
            return 50;
        }
    }

    public static double renderDistance() {
        try {
            return ClientConfig.WOUNDS_RENDER_DISTANCE.get();
        } catch (RuntimeException ignored) {
            return DEFAULT_DISTANCE;
        }
    }

    public static double renderDistanceSq() {
        double distance = renderDistance();
        return distance * distance;
    }

    /** 配置里的血色（ARGB）。解析失败退回默认暗红，不会把整条链路拖垮。 */
    public static int configuredTint() {
        String raw;
        try {
            raw = ClientConfig.WOUNDS_COLOR.get();
        } catch (RuntimeException ignored) {
            return FALLBACK_COLOR;
        }
        return parseColor(raw, FALLBACK_COLOR);
    }

    /**
     * 解析 {@code RRGGBB} / {@code #RRGGBB}（也接受 8 位的 {@code AARRGGBB}）。
     *
     * @return 不透明 ARGB；解析不出来时返回 {@code fallback}
     */
    public static int parseColor(@Nullable String raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        String text = raw.trim();
        if (text.startsWith("#")) {
            text = text.substring(1);
        }
        if (text.length() != 6 && text.length() != 8) {
            return fallback;
        }
        int value = 0;
        for (int i = 0; i < text.length(); i++) {
            int digit = Character.digit(text.charAt(i), 16);
            if (digit < 0) {
                return fallback;
            }
            value = (value << 4) | digit;
        }
        return text.length() == 6 ? 0xFF000000 | value : value;
    }

    /** 该模型是否允许画伤口。 */
    public static boolean allowsModel(@Nullable String modelId) {
        if (isBuiltinPlayerModel(modelId)) {
            return true;
        }
        if (includeThirdPartyModels()) {
            return true;
        }
        logSkippedModel(modelId);
        return false;
    }

    /** 宽手臂（{@code misc/2_steve}）或细手臂（{@code misc/1_alex}）的内置玩家模型。 */
    public static boolean isBuiltinPlayerModel(@Nullable String modelId) {
        return BuiltinModelIndex.isBuiltinPlayerModel(modelId);
    }

    private static void logSkippedModel(@Nullable String modelId) {
        if (modelId == null) {
            return;
        }
        synchronized (LOGGED_SKIPS) {
            if (LOGGED_SKIPS.size() >= 64 || !LOGGED_SKIPS.add(modelId)) {
                return;
            }
        }
        Kinetic.LOGGER.info("Model {} is not a builtin player model, so no wounds are drawn on it by "
                + "default. Set Wounds.ThirdPartyModels=true to enable them anyway.", modelId);
    }
}
