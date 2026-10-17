package com.kinetic.client.compat.tacz.lod;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 后台预热的节奏策略。
 *
 * <p>这两条规则是"不卡"的全部来源，写错了就会直接变成玩家能感知的掉帧：
 *
 * <ul>
 *   <li><b>让路</b>：只要有玩家手里的枪在等按需构建，预热就必须整帧不动。</li>
 *   <li><b>限速</b>：进入世界后同时最多一把，且两把之间至少隔
 *       {@link GunLodPrewarm#THROTTLED_INTERVAL_TICKS} tick。</li>
 * </ul>
 *
 * <p>未进入世界时没有游戏在跑，算力是白给的，所以那一档应当吃满池子。
 */
class GunLodPrewarmPolicyTest {

    @Test
    void idleModeSaturatesThePoolThenStops() {
        // 未进世界：只要没吃满并发度就继续发。
        for (int inFlight = 0; inFlight < GunLodPrewarm.IDLE_CONCURRENCY; inFlight++) {
            assertTrue(GunLodPrewarm.Policy.maySubmit(false, false, 0L, 0L, inFlight),
                    "并发度未满时必须继续发，inFlight=" + inFlight);
        }
        assertFalse(GunLodPrewarm.Policy.maySubmit(false, false, 0L, 0L,
                GunLodPrewarm.IDLE_CONCURRENCY), "吃满并发度后必须停手");
    }

    @Test
    void idleModeIgnoresThePendingFlagAndTheInterval() {
        // 未进世界时没有"正在用的枪"，pending 不该让路；间隔也不该生效。
        assertTrue(GunLodPrewarm.Policy.maySubmit(false, true, 5L, 5L, 0));
    }

    @Test
    void inWorldYieldsToOnDemandBuilds() {
        var tick = GunLodPrewarm.THROTTLED_INTERVAL_TICKS * 10L;
        // 间隔早就过了、也没有在途任务，但有一把按需构建在等 → 必须让路。
        assertFalse(GunLodPrewarm.Policy.maySubmit(true, true, tick, 0L, 0));
    }

    @Test
    void inWorldRunsAtMostOneAtATime() {
        var tick = GunLodPrewarm.THROTTLED_INTERVAL_TICKS * 10L;
        assertFalse(GunLodPrewarm.Policy.maySubmit(true, false, tick, 0L, 1),
                "已经有在途任务时不能发第二把");
    }

    @Test
    void inWorldRespectsTheInterval() {
        var last = 100L;
        assertFalse(GunLodPrewarm.Policy.maySubmit(true, false,
                last + GunLodPrewarm.THROTTLED_INTERVAL_TICKS - 1, last, 0),
                "间隔没到就不能发");
        assertTrue(GunLodPrewarm.Policy.maySubmit(true, false,
                        last + GunLodPrewarm.THROTTLED_INTERVAL_TICKS, last, 0),
                "间隔一到就该发");
    }

    @Test
    void inWorldIsFarSlowerThanIdle() {
        // 进入世界后每秒最多一把；这条断言把这个"贵得多的档位"钉住，
        // 免得以后有人把间隔调成 1 而没人发现。
        assertTrue(GunLodPrewarm.THROTTLED_INTERVAL_TICKS >= 20,
                "进世界后每 tick 发一把等于单核 70%，间隔必须足够大");
    }

    @Test
    void aPackIsRecordedOnlyWhenEveryGunSucceeded() {
        // 这是"中断不会留下不完整数据"的关键规则。
        assertTrue(GunLodPrewarm.Policy.mayRecordPack(10, 10, 0, "dir:abc"));
    }

    @Test
    void aPartiallySweptPackIsNeverRecorded() {
        // 被强杀时最常见的情形：只扫了一半。绝不能记成"已预热"。
        assertFalse(GunLodPrewarm.Policy.mayRecordPack(5, 10, 0, "dir:abc"));
        assertFalse(GunLodPrewarm.Policy.mayRecordPack(0, 10, 0, "dir:abc"));
    }

    @Test
    void aPackWithAnyFailureIsNeverRecorded() {
        assertFalse(GunLodPrewarm.Policy.mayRecordPack(10, 10, 1, "dir:abc"),
                "有一把失败就整包不记，下次重扫，失败的那把才有机会重试");
    }

    @Test
    void aPackWithoutAFingerprintIsNeverRecorded() {
        assertFalse(GunLodPrewarm.Policy.mayRecordPack(10, 10, 0, null),
                "拿不到枪包身份就不能记，否则下次会拿 null 去比");
    }

    @Test
    void anEmptyPackIsNeverRecorded() {
        // total = 0 说明这个包这次没有枪要扫（可能整包都被跳过了），不该写记录。
        assertFalse(GunLodPrewarm.Policy.mayRecordPack(0, 0, 0, "dir:abc"));
    }
}
