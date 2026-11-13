package com.kinetic.geckolib3.core.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 动画"按进度接力"的时间换算。
 *
 * <p>场景：趴下挥砍播到 60% 时玩家站起来，站姿挥砍要从 60% 接着播。实现方式是给新动画
 * 一个起点偏移，让它开口就落在目标 tick。这里钉住的就是那条换算——
 * 写错符号的后果是"站起来的一瞬间动作从头重播"，而那正是这次要修的现象。
 */
class AnimationSeekTest {

    @Test
    void seekOffsetMovesTheStartPointBackByTheSeekAmount() {
        // 第 100 tick 要落在动画的第 6 tick → 起点在 94
        assertEquals(94.0F, AnimationPlayer.offsetForSeek(100.0F, 6.0F), 1.0E-4F);
    }

    @Test
    void seekToZeroKeepsTheNaturalStart() {
        assertEquals(100.0F, AnimationPlayer.offsetForSeek(100.0F, 0.0F), 1.0E-4F);
    }

    @Test
    void fractionalTicksAreCarriedThroughExactly() {
        // 渲染 tick 是带小数插值的，0.375s→0.45s 的比例映射会落在非整数上
        assertEquals(97.75F, AnimationPlayer.offsetForSeek(100.0F, 2.25F), 1.0E-4F);
    }
}
