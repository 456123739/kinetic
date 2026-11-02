package com.kinetic.client.animation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 匍匐侧躺状态机的纯逻辑：进入/退出时长、缓动、动作短暂中断的容忍、动作种类锁定。
 *
 * <p>这些是"看不见的"部分——实机上只表现为"翻得顺不顺、回位会不会抖"，
 * 所以离线把曲线钉死。
 */
class ProneActionTest {
    private static ProneAction.State state() {
        return new ProneAction.State();
    }

    @Test
    void rollRampsInWithEasingAndReachesOne() {
        ProneAction.State s = state();
        float t = 0.0f;
        float last = 0.0f;
        float seconds = 0.0f;
        // 60fps：每帧 1/60 秒，而 renderTicks 以 tick 计（每秒 20 个），所以每帧 +1/3 tick
        while (seconds < 1.0f) {
            t += 1.0f / 3.0f;
            float roll = ProneAction.advance(s, t, true, true, ProneAction.Kind.MEDICAL);
            assertTrue(roll >= last - 1e-6, "roll 不能回退");
            last = roll;
            seconds += 1.0f / 60.0f;
            if (roll >= 0.999f) {
                break;
            }
        }
        assertEquals(1.0f, last, 1e-3, "持续动作时最终必须是 1");
        // 0.30s 的滚入：约 18 帧
        assertTrue(seconds < 0.45f, "滚入不能超过 0.45s，实际 " + seconds);
        assertTrue(seconds > 0.12f, "滚入不能快于 0.12s，实际 " + seconds);
    }

    @Test
    void rollOutTakesSlightlyLongerThanRollIn() {
        ProneAction.State s = state();
        s.progress = 1.0f;
        s.lastRenderTicks = 0.0f;
        float t = 0.0f;
        float seconds = 0.0f;
        while (seconds < 2.0f) {
            t += 1.0f / 3.0f;
            float roll = ProneAction.advance(s, t, false, true, ProneAction.Kind.NONE);
            seconds += 1.0f / 60.0f;
            if (roll <= 0.0f) {
                break;
            }
        }
        // 先吃 0.12s 的容忍，再走 0.34s 的滚出
        assertTrue(seconds > 0.35f, "滚出总时长不应短于 0.35s，实际 " + seconds);
        assertTrue(seconds < 0.75f, "滚出总时长不应长于 0.75s，实际 " + seconds);
    }

    @Test
    void shortDropoutsDoNotStartTheRollOut() {
        ProneAction.State s = state();
        float t = 0.0f;
        for (int i = 0; i < 40; i++) {
            t += 1.0f / 3.0f;
            ProneAction.advance(s, t, true, true, ProneAction.Kind.RELOAD);
        }
        float before = ProneAction.ease(s.progress);
        assertEquals(1.0f, before, 1e-3);
        // 动作信号丢 2 帧（约 0.033s），随后恢复：roll 不该往下走
        t += 1.0f / 3.0f;
        float a = ProneAction.advance(s, t, false, true, ProneAction.Kind.NONE);
        t += 1.0f / 3.0f;
        float b = ProneAction.advance(s, t, false, true, ProneAction.Kind.NONE);
        assertTrue(a >= before - 1e-6 && b >= before - 1e-6, "短暂丢失动作信号不允许开始回滚");
        t += 1.0f / 3.0f;
        ProneAction.advance(s, t, true, true, ProneAction.Kind.RELOAD);
        assertEquals(ProneAction.Kind.RELOAD, s.kind, "恢复后仍是同一个动作");
    }

    @Test
    void kindStaysLatchedUntilRollReturnsToZero() {
        ProneAction.State s = state();
        float t = 0.0f;
        for (int i = 0; i < 40; i++) {
            t += 1.0f / 3.0f;
            ProneAction.advance(s, t, true, true, ProneAction.Kind.MEDICAL);
        }
        assertEquals(ProneAction.Kind.MEDICAL, s.kind);
        // 动作结束：kind 必须留着，直到 roll 真的回到 0（否则回滚会换一段动画）
        t += 1.0f / 3.0f;
        ProneAction.advance(s, t, false, true, ProneAction.Kind.NONE);
        assertEquals(ProneAction.Kind.MEDICAL, s.kind, "回滚期间必须沿用同一个动作种类");
        for (int i = 0; i < 60; i++) {
            t += 1.0f / 3.0f;
            ProneAction.advance(s, t, false, true, ProneAction.Kind.NONE);
        }
        assertEquals(0.0f, ProneAction.ease(s.progress), 1e-6);
        assertEquals(ProneAction.Kind.NONE, s.kind, "回到趴姿后种类必须清空");
    }

    @Test
    void easingIsSmoothAtBothEnds() {
        assertEquals(0.0f, ProneAction.ease(0.0f), 1e-6);
        assertEquals(1.0f, ProneAction.ease(1.0f), 1e-6);
        assertEquals(0.5f, ProneAction.ease(0.5f), 1e-6);
        // 两端一阶导为 0：开头与结尾的增量必须远小于中段
        float head = ProneAction.ease(0.05f) - ProneAction.ease(0.0f);
        float mid = ProneAction.ease(0.55f) - ProneAction.ease(0.5f);
        float tail = ProneAction.ease(1.0f) - ProneAction.ease(0.95f);
        assertTrue(head < mid * 0.5f, "起步必须缓入");
        assertTrue(tail < mid * 0.5f, "收尾必须缓出");
    }

    @Test
    void standingWhileTheActionRunsNeverRolls() {
        ProneAction.State s = state();
        float t = 0.0f;
        for (int i = 0; i < 120; i++) {
            t += 1.0f / 3.0f;
            assertEquals(0.0f, ProneAction.advance(s, t, true, false, ProneAction.Kind.RELOAD), 1e-6,
                    "站着换弹/打药时不允许侧躺");
        }
        assertEquals(ProneAction.Kind.NONE, s.kind, "站着时不该锁住动作种类");
    }

    @Test
    void becomingProneMidActionRollsInAndContinuesTheAction() {
        ProneAction.State s = state();
        float t = 0.0f;
        // 先站着做一段动作（换弹中）
        for (int i = 0; i < 60; i++) {
            t += 1.0f / 3.0f;
            ProneAction.advance(s, t, true, false, ProneAction.Kind.RELOAD);
        }
        assertEquals(0.0f, ProneAction.ease(s.progress), 1e-6, "站着时 roll 必须是 0");
        // 动作仍在进行，玩家趴下：应当当场开始侧躺，并保持同一个动作种类
        float roll = 0.0f;
        float seconds = 0.0f;
        while (seconds < 1.0f) {
            t += 1.0f / 3.0f;
            seconds += 1.0f / 60.0f;
            roll = ProneAction.advance(s, t, true, true, ProneAction.Kind.RELOAD);
            if (roll >= 0.999f) {
                break;
            }
        }
        assertEquals(1.0f, roll, 1e-3, "趴下后必须滚到 1");
        assertEquals(ProneAction.Kind.RELOAD, s.kind, "动作种类必须沿用（动画才不会换一段）");
        assertTrue(seconds < 0.35f, "滚入应在一个翻身之内完成，实际 " + seconds);
    }

    @Test
    void standingUpMidActionSnapsOutFasterThanTheNormalExit() {
        // 还趴着：按 EXIT 慢慢回位
        ProneAction.State stay = state();
        stay.progress = 1.0f;
        float t = 0.0f;
        float secondsProne = 0.0f;
        while (secondsProne < 2.0f) {
            t += 1.0f / 3.0f;
            secondsProne += 1.0f / 60.0f;
            if (ProneAction.advance(stay, t, false, true, ProneAction.Kind.NONE) <= 0.0f) {
                break;
            }
        }
        // 站起来了：按 SNAP 快速收
        ProneAction.State up = state();
        up.progress = 1.0f;
        t = 0.0f;
        float secondsStanding = 0.0f;
        while (secondsStanding < 2.0f) {
            t += 1.0f / 3.0f;
            secondsStanding += 1.0f / 60.0f;
            if (ProneAction.advance(up, t, false, false, ProneAction.Kind.NONE) <= 0.0f) {
                break;
            }
        }
        assertTrue(secondsStanding < secondsProne * 0.6f,
                "站起来时必须收得更快（站姿 " + secondsStanding + "s vs 趴姿 " + secondsProne + "s）");
    }

    @Test
    void actionRestartDuringRollOutDoesNotDropToZero() {
        ProneAction.State s = state();
        float t = 0.0f;
        for (int i = 0; i < 40; i++) {
            t += 1.0f / 3.0f;
            ProneAction.advance(s, t, true, true, ProneAction.Kind.MEDICAL);
        }
        // 动作结束，开始回位（先吃掉 0.12s 的容忍，再走 0.34s 的滚出）
        for (int i = 0; i < 14; i++) {
            t += 1.0f / 3.0f;
            ProneAction.advance(s, t, false, true, ProneAction.Kind.NONE);
        }
        float mid = ProneAction.ease(s.progress);
        assertTrue(mid < 0.95f && mid > 0.2f, "应该正在回位，实际 " + mid);
        // 又接上一个动作：不允许先归零再重来
        t += 1.0f / 3.0f;
        float resumed = ProneAction.advance(s, t, true, true, ProneAction.Kind.MEDICAL);
        assertTrue(resumed >= mid - 1e-6, "重新开始动作时 roll 不能先掉下去再升上来");
    }

    @Test
    void hugeFrameGapIsClamped() {
        ProneAction.State s = state();
        s.lastRenderTicks = 0.0f;
        float roll = ProneAction.advance(s, 10_000.0f, true, true, ProneAction.Kind.MEDICAL);
        assertTrue(roll <= 1.0f, "单帧推进必须被夹住，不能一步到底");
    }
}
