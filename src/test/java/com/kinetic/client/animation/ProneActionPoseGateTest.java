package com.kinetic.client.animation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 趴姿判定的八道门。
 *
 * <p>Minecraft 把"爬行 / 游泳 / 跑酷滑铲"都塞进 {@code Pose.SWIMMING}，所以只看姿势会把
 * 游泳、滑铲、甚至骑乘都当成趴着。每一条排除都对应过一次实机上的错误姿势：
 * 滑铲时身体被侧躺覆盖、水里侧翻、尸体侧躺、旁观者模型歪掉……
 */
class ProneActionPoseGateTest {
    private static boolean prone(boolean pose, boolean swimming, boolean onGround, boolean dead,
                                 boolean sleeping, boolean spectator, boolean riding, boolean locked) {
        return ProneAction.isProne(pose, swimming, onGround, dead, sleeping, spectator, riding, locked);
    }

    @Test
    void allConditionsMetIsProne() {
        assertTrue(prone(true, false, true, false, false, false, false, false));
    }

    @Test
    void everySingleConditionCanVetoIt() {
        assertFalse(prone(false, false, true, false, false, false, false, false), "姿势不是 SWIMMING");
        assertFalse(prone(true, true, true, false, false, false, false, false), "真的在游泳");
        assertFalse(prone(true, false, false, false, false, false, false, false), "离地");
        assertFalse(prone(true, false, true, true, false, false, false, false), "死亡中");
        assertFalse(prone(true, false, true, false, true, false, false, false), "睡觉");
        assertFalse(prone(true, false, true, false, false, true, false, false), "旁观");
        assertFalse(prone(true, false, true, false, false, false, true, false), "骑乘");
        assertFalse(prone(true, false, true, false, false, false, false, true), "滑铲锁定中");
    }

    @Test
    void slideLockIsWhatKeepsSlidingFromBeingTreatedAsProne() {
        // 跑酷滑铲：姿势是 SWIMMING、在地上、没死没睡没骑——只有 SlideLock 能把它挡下来。
        assertFalse(prone(true, false, true, false, false, false, false, true),
                "滑铲必须在趴姿判定里被排除，否则滑铲的下身会被侧躺覆盖");
        // 锁定解除后立刻恢复（滑铲结束还趴着的情况）
        assertTrue(prone(true, false, true, false, false, false, false, false));
    }
}
