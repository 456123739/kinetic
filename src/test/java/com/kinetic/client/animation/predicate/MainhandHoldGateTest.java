package com.kinetic.client.animation.predicate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * hold 频道在「挥动中 / 使用中」是否暂停的判定。
 *
 * <p>回归点：手雷右键蓄力属于「使用中」，但它由 hold 频道负责（use 频道对它返回 STOP）。
 * 修复前这里一律暂停，蓄力期间 hold 控制器停在上一次的状态上，实机表现就是「姿势回到初始
 * 状态、只有投出瞬间有一下极短的投掷动画」。
 */
class MainhandHoldGateTest {

    @Test
    void idleOrHoldingKeepsTheHoldChannelRunning() {
        assertTrue(MainhandPredicate.holdChannelRuns(false, false, false),
                "既没挥动也没使用物品时，hold 频道必须继续（否则持械姿势会消失）");
        assertTrue(MainhandPredicate.holdChannelRuns(false, false, true),
                "只是拿着手雷（没有蓄力）时同样继续");
    }

    @Test
    void swingingStillPausesSoTheSwingChannelWins() {
        assertFalse(MainhandPredicate.holdChannelRuns(true, false, false));
        assertFalse(MainhandPredicate.holdChannelRuns(true, false, true),
                "投出手雷的瞬间由 swing 频道播投掷动画，hold 频道仍然让位");
        assertFalse(MainhandPredicate.holdChannelRuns(true, true, true),
                "挥动优先于使用");
    }

    @Test
    void usingANonGrenadeItemStillPauses() {
        assertFalse(MainhandPredicate.holdChannelRuns(false, true, false),
                "弓/食物/药水等「使用中」由 use 频道负责，hold 频道照旧暂停");
    }

    @Test
    void usingAGrenadeKeepsTheHoldChannelRunning() {
        assertTrue(MainhandPredicate.holdChannelRuns(false, true, true),
                "手雷蓄力必须让 hold 频道继续，否则抬雷/待投姿势不会被播放");
    }
}
