package com.kinetic.client.compat.tacz;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 本地换弹事件只桥接同步空窗，并且必须绑定到同一个 TaCZ gunId。 */
class TacReloadHintTest {
    private static final ResourceLocation AK47 = new ResourceLocation("tacz", "ak47");
    private static final ResourceLocation M4A1 = new ResourceLocation("tacz", "m4a1");

    @Test
    void hintCoversTheSyncGapOnly() {
        assertTrue(TacCompatInner.isReloadHintActive(100, 108, AK47, AK47));
        assertTrue(TacCompatInner.isReloadHintActive(108, 108, AK47, AK47));
        assertFalse(TacCompatInner.isReloadHintActive(109, 108, AK47, AK47),
                "超过桥接窗口后由当前 PLAY_ONCE 与真实 ReloadState 接力");
    }

    @Test
    void switchingGunIdDropsTheHintEvenForTheSameItemType() {
        assertFalse(TacCompatInner.isReloadHintActive(100, 108, M4A1, AK47));
        assertFalse(TacCompatInner.isReloadHintActive(100, 108, null, AK47));
    }
}
