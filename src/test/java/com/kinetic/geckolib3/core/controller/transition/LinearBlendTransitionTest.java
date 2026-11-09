package com.kinetic.geckolib3.core.controller.transition;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 线性过渡的边界行为。
 *
 * <p>`player.prone` 频道用长度 0 的过渡：侧躺的平滑完全由 `kinetic.prone_roll` 提供，
 * 频道再叠一份淡入只会在"已经趴平"之后又拖一段。长度 0 必须表示"当帧就完成"，
 * 而不是 0/0 产生 NaN —— 后者会把骨骼矩阵污染成 NaN 并让整个模型消失。
 */
class LinearBlendTransitionTest {
    @Test
    void zeroLengthCompletesImmediatelyWithoutDividingByZero() {
        LinearBlendTransition transition = new LinearBlendTransition(0f);
        assertEquals(1f, transition.get(0f), "长度 0 时第 0 帧就应当完成");
        assertEquals(1f, transition.get(1234f), "长度 0 时任何时刻都已完成，且不能是 NaN/Infinity");
        assertTrue(Float.isFinite(transition.get(37f)), "不能返回 NaN");
    }

    @Test
    void positiveLengthStillInterpolatesLinearly() {
        // 0.5 tick 长度 * 20 = 10 tick 的过渡
        LinearBlendTransition transition = new LinearBlendTransition(0.5f);
        assertEquals(10f, transition.length(), 1e-4);
        assertEquals(0f, transition.get(0f), 1e-4);
        assertEquals(0.5f, transition.get(5f), 1e-4);
        assertEquals(1f, transition.get(10f), 1e-4);
    }
}
