package com.kinetic.client.animation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 投掷脱手只接受真实消耗/主手挥动；取消蓄力和创造模式不消耗时不伪造动作。 */
class ProneThrowableReleaseTest {
    @Test
    void consumedStackOrEmptiedHandStartsOneThrow() {
        assertTrue(ProneAction.consumedOnRelease(1, 0, true));
        assertTrue(ProneAction.consumedOnRelease(1, 0, false),
                "最后一颗雷脱手后主手为空，也必须能进入投掷");
    }

    @Test
    void cancelOrNonConsumedCreativeChargeDoesNotThrow() {
        assertFalse(ProneAction.consumedOnRelease(1, 1, true));
        assertFalse(ProneAction.consumedOnRelease(3, 3, true));
    }
}
