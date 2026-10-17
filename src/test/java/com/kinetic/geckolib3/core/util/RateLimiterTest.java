package com.kinetic.geckolib3.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RateLimiter} 是动画求值频率的执行者。这里锁定两条性质：
 * 每帧最多放行一次；长期平均求值率跟随当前 limit。
 */
class RateLimiterTest {

    /** 144 Hz 渲染帧、每帧只 request 一次，模拟真实的 seekTime 推进。 */
    private static int fireCount(int limit, int frames, float secondsPerFrame) {
        var limiter = new RateLimiter();
        limiter.reset();
        limiter.setLimit(limit);
        var time = 0f;
        var fires = 0;
        for (var frame = 0; frame < frames; frame++) {
            if (limiter.request(time)) {
                fires++;
            }
            time += secondsPerFrame;
        }
        return fires;
    }

    @Test
    void steadyStateRateMatchesLimit() {
        var frames = 144 * 10;
        var secondsPerFrame = 1f / 144f;
        for (var limit : new int[]{10, 20, 60, 144}) {
            var fires = fireCount(limit, frames, secondsPerFrame);
            var expected = limit * 10;
            assertTrue(Math.abs(fires - expected) <= 2,
                    "limit=" + limit + " fires=" + fires + " expected~" + expected);
        }
    }

    @Test
    void rateIncreaseCannotReplayMissedEvaluations() {
        var limiter = new RateLimiter();
        limiter.reset();
        limiter.setLimit(10);
        var time = 0f;
        var secondsPerFrame = 1f / 144f;
        // 先以 10 Hz 跑 1 秒
        for (var frame = 0; frame < 144; frame++) {
            limiter.request(time);
            time += secondsPerFrame;
        }
        // 立刻升到 60 Hz：同一帧内只调用一次 request，所以不可能补发。
        limiter.setLimit(60);
        var immediate = 0;
        for (var i = 0; i < 10; i++) {
            if (limiter.request(time)) {
                immediate++;
            }
        }
        assertTrue(immediate <= 1, "a limit change must not burst: fired " + immediate + " times");
    }

    @Test
    void alternatingTiersStayAtMostOneEvaluationPerFrame() {
        var limiter = new RateLimiter();
        limiter.reset();
        var secondsPerFrame = 1f / 144f;
        var time = 0f;
        var maxPerFrame = 0;
        var limit = 10;
        for (var frame = 0; frame < 144 * 5; frame++) {
            // 模拟实体在档位边界来回抖动：10 / 60 / 20 每 7 帧换一次
            limit = switch ((frame / 7) % 3) {
                case 0 -> 10;
                case 1 -> 60;
                default -> 20;
            };
            limiter.setLimit(limit);
            var perFrame = limiter.request(time) ? 1 : 0;
            maxPerFrame = Math.max(maxPerFrame, perFrame);
            time += secondsPerFrame;
        }
        assertEquals(1, maxPerFrame, "no frame may evaluate more than once");
    }
}
