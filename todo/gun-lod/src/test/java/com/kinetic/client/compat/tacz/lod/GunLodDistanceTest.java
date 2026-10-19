package com.kinetic.client.compat.tacz.lod;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 距离判定：边界、禁用、以及"不开方"的数值正确性。 */
class GunLodDistanceTest {
    @Test
    void boundaryIsExclusive() {
        // 恰好等于上限不算超出（用 > 而不是 >=）。
        assertFalse(GunLodDistance.isBeyond(32, 0, 0, 0, 32, 0, 0));
        assertTrue(GunLodDistance.isBeyond(32, 0, 0, 0, 32.0001, 0, 0));
        assertFalse(GunLodDistance.isBeyond(32, 0, 0, 0, 31.9999, 0, 0));
    }

    @Test
    void usesThreeDimensionalDistance() {
        assertTrue(GunLodDistance.isBeyond(10, 0, 0, 0, 6, 6, 6), "对角方向必须按三维距离算");
        assertFalse(GunLodDistance.isBeyond(10, 0, 0, 0, 5, 5, 5));
        assertTrue(GunLodDistance.isBeyond(10, 0, 0, 0, 0, 11, 0), "垂直方向同样计入");
    }

    @Test
    void disabledWhenLimitIsNotPositive() {
        assertFalse(GunLodDistance.isBeyond(0, 0, 0, 0, 1000, 1000, 1000));
        assertFalse(GunLodDistance.isBeyond(-1, 0, 0, 0, 1000, 1000, 1000));
    }

    @Test
    void relativeToCameraNotToOrigin() {
        // 相机本身在远处时，判定必须相对相机，而不是相对世界原点。
        assertFalse(GunLodDistance.isBeyond(32, 1000, 64, -500, 1020, 64, -500));
        assertTrue(GunLodDistance.isBeyond(32, 1000, 64, -500, 1040, 64, -500));
    }

    @Test
    void largeLimitDoesNotOverflow() {
        assertFalse(GunLodDistance.isBeyond(256, 0, 0, 0, 200, 0, 0));
        assertTrue(GunLodDistance.isBeyond(256, 0, 0, 0, 300, 0, 0));
    }

    /**
     * 物品 LOD 的语义与动画 LOD 相反：{@code 0} 表示"其他玩家的枪一直用简化模型"，
     * 而不是"关闭该 LOD"。这是默认配置，必须成立。
     */
    @Test
    void itemLodTreatsZeroAsAlwaysOn() {
        assertTrue(GunLodDistance.useItemLod(0, 0, 0, 0, 1000, 1000, 1000));
        assertTrue(GunLodDistance.useItemLod(0, 0, 0, 0, 0, 0, 0), "贴脸也要用 LOD");
        assertTrue(GunLodDistance.useItemLod(-1, 0, 0, 0, 1000, 1000, 1000));
    }

    @Test
    void itemLodWithPositiveLimitBehavesLikeDistanceCheck() {
        assertFalse(GunLodDistance.useItemLod(32, 0, 0, 0, 31, 0, 0), "近距离用完整模型");
        assertFalse(GunLodDistance.useItemLod(32, 0, 0, 0, 32, 0, 0), "边界含在完整模型一侧");
        assertTrue(GunLodDistance.useItemLod(32, 0, 0, 0, 33, 0, 0), "超出后才用简化模型");
    }
}
