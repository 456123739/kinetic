package com.kinetic.client.animation.predicate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 换弹不能被普通的挥动/使用互斥门吞掉，其他一次性动画仍保持互斥。 */
class GunFirePredicateGateTest {
    @Test
    void reloadBypassesSwingAndUseGate() {
        assertTrue(GunFirePredicate.mayPlayOneShot(true, false, true));
        assertTrue(GunFirePredicate.mayPlayOneShot(false, true, true));
        assertTrue(GunFirePredicate.mayPlayOneShot(true, true, true));
    }

    @Test
    void currentReloadAnimationSurvivesHintExpiryUntilItFinishes() {
        // 本地事件只负责启动；hint 到期后由仍在播放的 PLAY_ONCE 接续，sync 再接管权威状态。
        assertTrue(GunFirePredicate.isReloadAnimation("tac:reload:rifle"));
        assertTrue(GunFirePredicate.mayPlayOneShot(true, true,
                GunFirePredicate.isReloadAnimation("tac:reload:pistol")));
        assertFalse(GunFirePredicate.isReloadAnimation(null));
        assertFalse(GunFirePredicate.isReloadAnimation("tac:hold:fire:rifle"));
    }

    @Test
    void ordinaryFireStillObservesTheGate() {
        assertTrue(GunFirePredicate.mayPlayOneShot(false, false, false));
        assertFalse(GunFirePredicate.mayPlayOneShot(true, false, false));
        assertFalse(GunFirePredicate.mayPlayOneShot(false, true, false));
    }
}
