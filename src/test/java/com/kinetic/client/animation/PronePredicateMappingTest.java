package com.kinetic.client.animation;

import com.kinetic.client.animation.predicate.PronePredicate;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 动作种类 → 动画名的回退规则。
 *
 * <p>自建模型往往只实现了通用姿势；这时侧滚仍要生效（头和腿照样摆正），
 * 只是手臂留给原动作动画。反过来，模型没有通用姿势时必须整帧让位，
 * 不能凭空播一个不存在的动画（会让控制器空转、玩家僵住）。
 */
class PronePredicateMappingTest {
    private static final Set<String> FULL = Set.of(
            "prone:action:idle", "prone:action:medical", "prone:action:reload", "prone:action:grenade");

    @Test
    void swingAnimationsMapToTheActionThatOwnsTheirRoll() {
        assertEquals(ProneAction.Kind.SWING, PronePredicate.kindForSwingAnimation("prone:swing"));
        assertEquals(ProneAction.Kind.SWING, PronePredicate.kindForSwingAnimation("prone:swing:alt"));
        assertEquals(ProneAction.Kind.THROW, PronePredicate.kindForSwingAnimation("prone:throw"));
        assertEquals(ProneAction.Kind.NONE, PronePredicate.kindForSwingAnimation("swing_hand"));
        assertNull(PronePredicate.animationNameFor(ProneAction.Kind.SWING, FULL::contains));
        assertNull(PronePredicate.animationNameFor(ProneAction.Kind.THROW, FULL::contains));
    }

    @Test
    void eachKindUsesItsOwnAnimationWhenPresent() {
        assertEquals("prone:action:medical", PronePredicate.animationNameFor(ProneAction.Kind.MEDICAL, FULL::contains));
        assertEquals("prone:action:reload", PronePredicate.animationNameFor(ProneAction.Kind.RELOAD, FULL::contains));
        assertEquals("prone:action:grenade", PronePredicate.animationNameFor(ProneAction.Kind.GRENADE, FULL::contains));
        assertEquals("prone:action:idle", PronePredicate.animationNameFor(ProneAction.Kind.OTHER, FULL::contains));
    }

    @Test
    void missingSpecificAnimationFallsBackToIdle() {
        Set<String> onlyIdle = Set.of("prone:action:idle");
        assertEquals("prone:action:idle", PronePredicate.animationNameFor(ProneAction.Kind.MEDICAL, onlyIdle::contains));
        // 回滚阶段（kind 已经不在，但 roll 还在）同样要能播
        assertEquals("prone:action:idle", PronePredicate.animationNameFor(ProneAction.Kind.NONE, onlyIdle::contains));
    }

    @Test
    void throwOwnedBySwingChannelIsNotPlayedTwice() {
        Set<String> withThrow = Set.of("prone:action:idle", "prone:throw");
        // swing 频道已经在播：匍匐频道让位，否则两套时间线抢同一个手臂
        assertNull(PronePredicate.animationFor(ProneAction.Kind.THROW, "prone:throw", withThrow::contains));
        // swing 频道没接手（很多投掷根本不置 swinging）：匍匐频道自己播，趴下扔雷才会侧躺
        assertEquals("prone:throw",
                PronePredicate.animationFor(ProneAction.Kind.THROW, null, withThrow::contains));
        assertEquals("prone:throw",
                PronePredicate.animationFor(ProneAction.Kind.THROW, "swing_hand", withThrow::contains));
    }

    @Test
    void throwFallsBackToIdleWhenModelHasNoThrowPose() {
        Set<String> onlyIdle = Set.of("prone:action:idle");
        assertEquals("prone:action:idle",
                PronePredicate.animationFor(ProneAction.Kind.THROW, null, onlyIdle::contains));
        assertNull(PronePredicate.animationFor(ProneAction.Kind.THROW, null, name -> false));
    }

    @Test
    void onlyANewThrowInputRestartsTheOneShot() {
        assertTrue(PronePredicate.shouldRestartThrow(ProneAction.Kind.THROW, "prone:throw", null));
        assertTrue(PronePredicate.shouldRestartThrow(ProneAction.Kind.THROW, "prone:throw", "prone:action:idle"));
        assertFalse(PronePredicate.shouldRestartThrow(ProneAction.Kind.THROW, "prone:throw", "prone:throw"),
                "随挥中不能每帧重播");
        assertFalse(PronePredicate.shouldRestartThrow(ProneAction.Kind.NONE, "prone:throw", null),
                "roll 回位期不能重播");
        assertFalse(PronePredicate.shouldRestartThrow(ProneAction.Kind.THROW, null, "prone:throw"),
                "swing 频道拥有投掷时匍匐频道不重启自己的动画");
    }

    @Test
    void modelWithoutAnyProneAnimationIsLeftAlone() {
        assertNull(PronePredicate.animationNameFor(ProneAction.Kind.MEDICAL, name -> false));
        assertNull(PronePredicate.animationNameFor(ProneAction.Kind.NONE, name -> false));
    }
}
