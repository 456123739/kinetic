package com.kinetic.client.wound;

import com.kinetic.client.compat.ragdollified.RagdollTuning;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 受伤外观的开关、配色解析与适用范围。
 *
 * <p>这些判断决定了"什么时候根本不画"，写错了的表现是功能悄悄失效（例如配置文件里写了个
 * 带 {@code #} 的颜色就退回默认，或者第三方模型被当成内置模型画了一身错位的伤口）。
 */
class WoundFeatureTest {

    @Test
    void colourAcceptsBothFormsAndHash() {
        assertEquals(0xFF9F0000, WoundFeature.parseColor("9F0000", 0));
        assertEquals(0xFF9F0000, WoundFeature.parseColor("#9F0000", 0));
        assertEquals(0xFF9F0000, WoundFeature.parseColor("  #9F0000 ", 0));
        assertEquals(0xFF9F0000, WoundFeature.parseColor("ff9f0000", 0));
        assertEquals(0xFF000000, WoundFeature.parseColor("000000", 0xFFFF00FF));
    }

    @Test
    void colourFallsBackInsteadOfThrowing() {
        int fallback = 0xFF123456;
        assertEquals(fallback, WoundFeature.parseColor(null, fallback));
        assertEquals(fallback, WoundFeature.parseColor("", fallback));
        assertEquals(fallback, WoundFeature.parseColor("#", fallback));
        assertEquals(fallback, WoundFeature.parseColor("12345", fallback));
        assertEquals(fallback, WoundFeature.parseColor("1234567", fallback));
        assertEquals(fallback, WoundFeature.parseColor("GGGGGG", fallback));
        assertEquals(fallback, WoundFeature.parseColor("#ZZ0000", fallback));
    }

    @Test
    void onlyTheTwoBuiltinPlayerModelsAreRecognised() {
        assertTrue(WoundFeature.isBuiltinPlayerModel("misc/2_steve"), "宽手臂内置模型");
        assertTrue(WoundFeature.isBuiltinPlayerModel("misc/1_alex"), "细手臂内置模型");
        assertFalse(WoundFeature.isBuiltinPlayerModel("misc/2_steve2"), "前缀相同但不同模型");
        assertFalse(WoundFeature.isBuiltinPlayerModel("custom/whatever"));
        assertFalse(WoundFeature.isBuiltinPlayerModel(null));
    }

    @Test
    void thirdPartyModelsAreRefusedByDefault() {
        // 单测环境里配置未加载，ThirdPartyModels 取不到值，等价于"默认关闭"。
        assertTrue(WoundFeature.allowsModel("misc/2_steve"));
        assertTrue(WoundFeature.allowsModel("misc/1_alex"));
        assertFalse(WoundFeature.allowsModel("custom/whatever"),
                "第三方模型默认不画伤口：它们的 UV 布局是任意的");
    }

    @Test
    void ragdollDistanceFollowsTheProvider() {
        // 拿不到提供方配置时退回提供方文档里的默认值，而不是 KINETIC 原来的 64。
        assertEquals(RagdollTuning.DEFAULT_RENDER_DISTANCE, RagdollTuning.renderDistance(-1.0), 1.0e-9);
        assertEquals(RagdollTuning.DEFAULT_RENDER_DISTANCE, RagdollTuning.renderDistance(0.0), 1.0e-9);
        assertEquals(RagdollTuning.DEFAULT_RENDER_DISTANCE, RagdollTuning.renderDistance(Double.NaN), 1.0e-9);
        assertEquals(RagdollTuning.DEFAULT_RENDER_DISTANCE,
                RagdollTuning.renderDistance(Double.POSITIVE_INFINITY), 1.0e-9);
        // 拿得到就用提供方的值，一个字都不改。
        assertEquals(128.0, RagdollTuning.renderDistance(128.0), 1.0e-9);
        assertEquals(32.0, RagdollTuning.renderDistance(32.0), 1.0e-9);
        assertEquals(512.0, RagdollTuning.renderDistance(512.0), 1.0e-9);
    }

    @Test
    void ragdollCaptureTtlFollowsTheProviderLifetime() {
        // 默认 200 tick（10 秒）的存活时间 -> 10 秒 + 余量。
        assertEquals(200 * 50L + RagdollTuning.CAPTURE_TTL_MARGIN_MS,
                RagdollTuning.captureTtlMs(200));
        // 拿不到配置时退回固定值。
        assertEquals(RagdollTuning.CAPTURE_TTL_FALLBACK_MS, RagdollTuning.captureTtlMs(-1));
        assertEquals(RagdollTuning.CAPTURE_TTL_FALLBACK_MS, RagdollTuning.captureTtlMs(0));
        // 极短的存活时间也不能把快照在第一帧就清掉：余量已经覆盖了这一点。
        assertEquals(50L + RagdollTuning.CAPTURE_TTL_MARGIN_MS, RagdollTuning.captureTtlMs(1));
        // 存活时间越长，快照留得越久。
        assertTrue(RagdollTuning.captureTtlMs(400) > RagdollTuning.captureTtlMs(200));
    }
}
