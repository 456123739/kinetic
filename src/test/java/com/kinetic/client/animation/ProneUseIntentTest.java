package com.kinetic.client.animation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "按着使用键"这条输入回退的判定。
 *
 * <p>它只回答"玩家此刻是否按着使用键、主手还是当初那件物品"，不写任何玩法状态；
 * 因此过期、换物品、松手、tick 归零这四种情况都必须立刻失效，否则侧躺会挂死在身上。
 */
class ProneUseIntentTest {

    @Test
    void holdingTheSameItemWithinTheWindowCounts() {
        assertTrue(ProneUseIntent.stillHolding(true, true, 100, 90));
        assertTrue(ProneUseIntent.stillHolding(true, true, 90, 90), "按下的那一 tick 就要算数");
        assertTrue(ProneUseIntent.stillHolding(true, true, 90 + ProneUseIntent.MAX_HOLD_TICKS, 90),
                "窗口边界仍有效");
    }

    @Test
    void releasingTheKeyEndsItImmediately() {
        assertFalse(ProneUseIntent.stillHolding(false, true, 95, 90));
    }

    @Test
    void switchingItemsOrEmptyingTheHandEndsIt() {
        assertFalse(ProneUseIntent.stillHolding(true, false, 95, 90));
    }

    @Test
    void staleOrRewoundTicksExpire() {
        assertFalse(ProneUseIntent.stillHolding(true, true, 90 + ProneUseIntent.MAX_HOLD_TICKS + 1, 90),
                "超时必须过期，姿势不能挂死");
        assertFalse(ProneUseIntent.stillHolding(true, true, 5, 90), "tickCount 归零（重生/换维度）作废旧记录");
    }
}
