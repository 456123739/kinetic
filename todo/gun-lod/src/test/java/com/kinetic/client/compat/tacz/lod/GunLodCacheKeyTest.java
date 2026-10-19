package com.kinetic.client.compat.tacz.lod;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 磁盘缓存键的稳定性与敏感性。
 *
 * <p>这组断言直接对应"改了代码但画面没变"和"改了配置却拿到旧模型"这两类真实故障：
 * 键必须对枪、贴图、减面参数都敏感，且同一输入必须永远得到同一个值（否则每次启动都重建）。
 */
class GunLodCacheKeyTest {
    private static final ResourceLocation AK47 = new ResourceLocation("tacz", "ak47");
    private static final ResourceLocation M4A1 = new ResourceLocation("tacz", "m4a1");
    private static final byte[] MODEL_A = {1, 2, 3, 4, 5};
    private static final byte[] MODEL_B = {1, 2, 3, 4, 6};

    @Test
    void containerIdIsStableForTheSameGun() {
        assertEquals(GunLodCacheKey.containerId(AK47, MODEL_A),
                GunLodCacheKey.containerId(AK47, MODEL_A));
    }

    @Test
    void containerIdDiffersBetweenGunsAndNamespaces() {
        assertNotEquals(GunLodCacheKey.containerId(AK47, MODEL_A),
                GunLodCacheKey.containerId(M4A1, MODEL_A));
        assertNotEquals(GunLodCacheKey.containerId(AK47, MODEL_A),
                GunLodCacheKey.containerId(new ResourceLocation("other", "ak47"), MODEL_A));
    }

    @Test
    void containerIdFollowsTheModelContent() {
        // 枪包就地更新模型时枪 id、模型路径、贴图都不变，只有模型内容变——键必须跟着变，
        // 否则更新枪包后 LOD 会继续用旧几何，与 TaCZ 的完整模型对不上。
        assertNotEquals(GunLodCacheKey.containerId(AK47, MODEL_A),
                GunLodCacheKey.containerId(AK47, MODEL_B));
    }

    @Test
    void containerIdDoesNotCollideAcrossLengthBoundaries() {
        // 长度前缀保证 "ab"+"c" 与 "a"+"bc" 这类拼接不会撞在一起。
        assertNotEquals(
                GunLodCacheKey.containerId(new ResourceLocation("a", "b"), new byte[]{1, 2}),
                GunLodCacheKey.containerId(new ResourceLocation("a", "bc"), new byte[]{2}));
    }

    @Test
    void resourceNameFollowsTheDecimationParameters() {
        var base = GunLodCacheKey.resourceName(3.0f, false);
        assertEquals(base, GunLodCacheKey.resourceName(3.0f, false));
        // 体量阈值改变几何，必须换键。
        assertNotEquals(base, GunLodCacheKey.resourceName(4.0f, false));
        // 跳配件与否改变几何，必须换键。
        assertNotEquals(base, GunLodCacheKey.resourceName(3.0f, true));
    }

    @Test
    void resourceNameCarriesTheRuleRevision() {
        // 减面规则本身改动时没有任何输入会变，只能靠版本号让旧缓存失效。
        assertTrue(GunLodCacheKey.resourceName(3.0f, false)
                .contains("/r" + GunLodDecimator.RULE_REVISION + "/"));
        assertTrue(GunLodDecimator.RULE_REVISION >= 1);
    }

    @Test
    void resourceNameDoesNotCollideAcrossPlausibleParameterPairs() {
        // "3.0 + skip" 与 "3.01 + noskip" 这类相邻配置必须落在不同的键上。
        assertNotEquals(GunLodCacheKey.resourceName(3.0f, true),
                GunLodCacheKey.resourceName(3.01f, false));
        assertFalse(GunLodCacheKey.resourceName(3.0f, true)
                .equals(GunLodCacheKey.resourceName(30.0f, true)));
    }
}
