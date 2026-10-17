package com.kinetic.geckolib3.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AnimationRateTiers} 只做纯数学钳制，这里覆盖"配置写反"和"低刷新率"两种情况。
 */
class AnimationRateTiersTest {

    @Test
    void baseIsCappedByMaxRate() {
        assertEquals(60, AnimationRateTiers.base(144, 60));
        assertEquals(30, AnimationRateTiers.base(30, 60));
        assertEquals(144, AnimationRateTiers.base(144, 360));
    }

    @Test
    void maxRateZeroFollowsRefreshRate() {
        assertEquals(144, AnimationRateTiers.base(144, 0));
        assertEquals(30, AnimationRateTiers.base(30, 0));
        assertEquals(60, AnimationRateTiers.base(60, -1));
    }

    @Test
    void ladderNeverIncreasesWithDistanceEvenWithReversedConfig() {
        var base = AnimationRateTiers.base(144, 60);
        var near = AnimationRateTiers.clamp(20, base);
        // 配置写反：远处频率(60)比近处(20)高，隐藏频率(30)比远处还高
        var far = AnimationRateTiers.clamp(60, near);
        var hidden = AnimationRateTiers.clamp(30, far);

        assertEquals(20, near);
        assertEquals(20, far);
        assertEquals(20, hidden);
        assertTrue(base >= near && near >= far && far >= hidden,
                "the effective ladder must be non-increasing with distance");
    }

    @Test
    void nearTierIsAlsoClampedToTheRefreshRate() {
        // 30 Hz 显示器：旧代码近处档位硬编码 60，比距离 0 处的 30 还高，本身就不单调。
        var base = AnimationRateTiers.base(30, 60);
        assertEquals(30, base);
        assertEquals(30, AnimationRateTiers.clamp(60, base));
    }

    @Test
    void everyRateIsAtLeastOne() {
        assertEquals(1, AnimationRateTiers.base(0, 0));
        assertEquals(1, AnimationRateTiers.clamp(0, 0));
        assertEquals(1, AnimationRateTiers.clamp(0, 60));
    }

    @Test
    void farDistanceIsNeverNearerThanNearDistance() {
        assertEquals(40, AnimationRateTiers.farDistance(20, 40));
        assertEquals(64, AnimationRateTiers.farDistance(64, 40));
    }
}
