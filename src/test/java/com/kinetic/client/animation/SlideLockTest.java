package com.kinetic.client.animation;

import com.kinetic.geckolib3.core.molang.util.StringPool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 滑铲锁定状态机的逐 tick 行为。
 *
 * <p>没有游戏客户端时，这是"开火不会打断滑铲锁定"能拿到的最直接证据：{@link SlideLock#advance}
 * 的输入里<b>没有</b>开火、挥动、使用、瞄准这些量——TaCZ 也不可能从别的路径改到它。测试逐 tick
 * 推进真实的状态机，覆盖"跑酷滑铲停止后仍要继续锁定"的窗口。
 */
class SlideLockTest {

    private static SlideLock.State newState() {
        return new SlideLock.State();
    }

    /** 一次滑铲 + 持续开火：只要跑酷还在演滑铲，锁定必须一直成立。 */
    @Test
    void continuousSlideStaysLockedForItsWholeDuration() {
        SlideLock.State state = newState();
        for (int tick = 0; tick < 200; tick++) {
            assertTrue(advanceSliding(state, tick),
                    "第 " + tick + " tick 滑铲仍在进行，锁定不能掉");
        }
    }

    /**
     * 滑铲动作结束后（跑酷已经切到 CrawlAnimator，拿不到动画名）玩家仍在高速滑行：
     * 锁定必须再续 {@link SlideLock#HOLD_TICKS} 个 tick，覆盖连发。
     */
    @Test
    void lockSurvivesTheHoldWindowAfterParCoolStopsReportingTheSlide() {
        SlideLock.State state = newState();
        int lastSlideTick = 29;
        for (int tick = 0; tick <= lastSlideTick; tick++) {
            assertTrue(advanceSliding(state, tick));
        }
        for (int tick = lastSlideTick + 1; tick <= lastSlideTick + SlideLock.HOLD_TICKS; tick++) {
            assertTrue(advance(state, tick, false, false, true, true,
                            false, false, false, true, true, 0.5),
                    "跑酷停报第 " + (tick - lastSlideTick) + " tick，仍在高速滑行：锁定不能掉");
        }
        assertFalse(advance(state, lastSlideTick + SlideLock.HOLD_TICKS + 1, false, false,
                        true, true, false, false, false, true, true, 0.5),
                "超过保持窗口后必须释放，否则玩家会一直滑");
    }

    /** 站起来了（Pose 不再是 SWIMMING）：立刻释放。 */
    @Test
    void standingUpReleasesImmediately() {
        SlideLock.State state = newState();
        assertTrue(advanceSliding(state, 0));
        assertFalse(advance(state, 1, false, false, true, true,
                        false, false, false, true, false, 0.5),
                "已经站起来就不能再演滑铲");
    }

    /** 跑酷切到别的动作（墙跑、翻滚……）：立刻释放。 */
    @Test
    void anotherParCoolActionReleasesImmediately() {
        SlideLock.State state = newState();
        assertTrue(advanceSliding(state, 0));
        assertFalse(advance(state, 1, false, true, true, true,
                false, false, false, true, true, 0.5));
    }

    /** 掉到爬行速度并持续一段时间：释放，让真正的爬行姿势出来。 */
    @Test
    void crawlingSpeedReleasesAfterTheSlowWindow() {
        SlideLock.State state = newState();
        assertTrue(advanceSliding(state, 0));
        int firstSlow = 1;
        for (int i = 0; i < SlideLock.SLOW_RELEASE_TICKS - 1; i++) {
            assertTrue(advance(state, firstSlow + i, false, false, true, true,
                    false, false, false, true, true, 0.005));
        }
        assertFalse(advance(state, firstSlow + SlideLock.SLOW_RELEASE_TICKS - 1, false, false,
                        true, true, false, false, false, true, true, 0.005),
                "低速持续满窗口后必须释放");
    }

    /** 游泳、死亡、上车、离地：都立即释放。 */
    @Test
    void deathSwimmingRidingAndFallingReleaseTheLock() {
        assertReleasedAfterSlide(state -> advance(state, 1, true, false, true, true,
                true, false, false, true, true, 0.5), "死亡");
        assertReleasedAfterSlide(state -> advance(state, 1, true, false, true, true,
                false, true, false, true, true, 0.5), "游泳");
        assertReleasedAfterSlide(state -> advance(state, 1, true, false, true, true,
                false, false, true, true, true, 0.5), "骑乘");
        // 跑酷不再报滑铲、人还在空中：允许 3 tick 的余量（被台阶/斜坡顶起时不该立刻断）
        SlideLock.State airborne = newState();
        assertTrue(advanceSliding(airborne, 0));
        for (int i = 1; i <= SlideLock.MAX_AIRBORNE_TICKS; i++) {
            assertTrue(advance(airborne, i, false, false, true, true,
                    false, false, false, false, true, 0.5));
        }
        assertFalse(advance(airborne, 1 + SlideLock.MAX_AIRBORNE_TICKS, false, false,
                true, true, false, false, false, false, true, 0.5));
    }

    /** 没持枪、或模型没有滑铲动画：根本不该进入锁定。 */
    @Test
    void lockRequiresAGunAndASlideAnimationInTheModel() {
        assertFalse(advance(newState(), 0, true, false, false, true,
                false, false, false, true, true, 0.5), "空手滑铲不锁");
        assertFalse(advance(newState(), 0, true, false, true, false,
                false, false, false, true, true, 0.5), "模型没有 parcool:sliding 时不锁");
    }

    /** 释放之后可以重新进入锁定（连着滑两次）。 */
    @Test
    void aSecondSlideLocksAgain() {
        SlideLock.State state = newState();
        assertTrue(advanceSliding(state, 0));
        assertFalse(advance(state, 1, false, false, true, true,
                false, false, false, true, false, 0.5));
        assertTrue(advanceSliding(state, 2), "新的一次滑铲必须重新锁上");
    }

    /**
     * 锁定骨骼集合必须覆盖"整身/躯干/腿"，并且不能包含手臂与头——
     * 手臂归 TaCZ 的静止持枪，头要能跟随视线。
     */
    @Test
    void lockedBonesCoverTheBodyButNotTheArms() {
        for (String bone : new String[]{"Root", "AllBody", "UpBody", "UpperBody", "DownBody",
                "LeftLeg", "LeftLowerLeg", "LeftFoot", "RightLeg", "RightLowerLeg", "RightFoot"}) {
            assertTrue(SlideLock.isLockedBone(StringPool.computeIfAbsent(bone)), bone + " 必须被锁定");
        }
        for (String bone : new String[]{"RightArm", "RightForeArm", "RightHand", "LeftArm",
                "LeftForeArm", "LeftHand", "Head", "AllHead", "RightHandLocator"}) {
            assertFalse(SlideLock.isLockedBone(StringPool.computeIfAbsent(bone)), bone + " 必须交给持枪姿势");
        }
        // AllBody 是 tac:hold:rifle 里会把整个人拧过去的那条轨道，必须在锁定集合里。
        assertTrue(SlideLock.isLockedBone(StringPool.computeIfAbsent("AllBody")));
    }

    private static boolean advanceSliding(SlideLock.State state, int tick) {
        return advance(state, tick, true, false, true, true,
                false, false, false, true, true, 0.5);
    }

    private static boolean advance(SlideLock.State state, int now, boolean slidingNow,
                                   boolean otherParcoolAction, boolean holdingFirearm,
                                   boolean modelHasSlide, boolean dead, boolean swimming,
                                   boolean riding, boolean onGround, boolean slidingPose,
                                   double speedSqr) {
        return SlideLock.advance(state, now, slidingNow, otherParcoolAction, holdingFirearm,
                modelHasSlide, dead, swimming, riding, onGround, slidingPose, speedSqr);
    }

    private static void assertReleasedAfterSlide(java.util.function.Predicate<SlideLock.State> release,
                                                 String what) {
        SlideLock.State state = newState();
        assertTrue(advanceSliding(state, 0));
        assertFalse(release.test(state), what + " 必须立即释放锁定");
    }
}
