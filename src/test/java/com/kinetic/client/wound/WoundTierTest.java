package com.kinetic.client.wound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 受伤档位的纯逻辑。
 *
 * <p>这一层错了会直接表现为"伤口和血量对不上"：回血不掉档、掉血不涨档、或者同一血量在两档之间闪。
 * 所以断言集中在三件事上——边界确定、随血量单调、量化不抖。
 */
class WoundTierTest {

    @Test
    void fullHealthIsTierZero() {
        assertEquals(0, WoundTier.of(20.0f, 20.0f, 5));
        assertEquals(0, WoundTier.of(1.0f, 1.0f, 5));
        assertEquals(0, WoundTier.of(40.0f, 40.0f, 10));
    }

    @Test
    void deadIsTheLastTier() {
        assertEquals(5, WoundTier.of(0.0f, 20.0f, 5));
        assertEquals(5, WoundTier.of(-3.0f, 20.0f, 5));
        assertEquals(10, WoundTier.of(0.0f, 20.0f, 10));
    }

    @Test
    void unreadableMaxHealthDrawsNothing() {
        // 实体刚生成、能力还没同步时宁可什么都不画，也不要凭默认值糊一身伤口。
        assertEquals(0, WoundTier.of(10.0f, 0.0f, 5));
        assertEquals(0, WoundTier.of(10.0f, -1.0f, 5));
        assertEquals(0, WoundTier.of(10.0f, Float.NaN, 5));
    }

    @Test
    void nanHealthCountsAsDead() {
        assertEquals(5, WoundTier.of(Float.NaN, 20.0f, 5));
    }

    @Test
    void tierStepsLandOnWholeDamagePoints() {
        // 20 点血、5 档：每档 4 点。掉 1~3 点还是一档，掉满 4 点才升第二档。
        int[] expected = {0, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 5};
        for (int lost = 0; lost <= 20; lost++) {
            assertEquals(expected[lost], WoundTier.of(20.0f - lost, 20.0f, 5),
                    "掉 " + lost + " 点血时的档位");
        }
    }

    @Test
    void halfPointChipDamageDoesNotRaiseTheTier() {
        // 量化到整点：19.5 点血向上取整还是 20，所以不掉档。零星擦伤不该让玩家身上立刻开花。
        assertEquals(0, WoundTier.of(19.5f, 20.0f, 5));
        assertEquals(0, WoundTier.of(19.01f, 20.0f, 5));
        assertEquals(1, WoundTier.of(19.0f, 20.0f, 5));
    }

    @Test
    void tierNeverDecreasesAsHealthDrops() {
        for (int tiers = WoundTier.MIN_TIERS; tiers <= WoundTier.MAX_TIERS; tiers++) {
            for (float maxHealth : new float[]{1.0f, 6.0f, 20.0f, 40.0f, 15.5f, 1024.0f}) {
                int previous = -1;
                // 从满血一路扣到 0，档位只能不降。
                for (int step = 0; step <= 200; step++) {
                    float health = maxHealth * (200 - step) / 200.0f;
                    int tier = WoundTier.of(health, maxHealth, tiers);
                    assertTrue(tier >= previous,
                            "档位必须随血量下降单调不降: tiers=" + tiers + " max=" + maxHealth
                                    + " health=" + health + " 上一档=" + previous + " 本档=" + tier);
                    assertTrue(tier >= 0 && tier <= tiers, "档位必须落在 0..tiers: " + tier);
                    previous = tier;
                }
            }
        }
    }

    @Test
    void nonIntegralMaxHealthUsesCeiling() {
        // 15.5 点血按 16 点算：满血是 16 点，掉 4 点进第二档（4*5/16 = 1）。
        assertEquals(0, WoundTier.of(15.5f, 15.5f, 5));
        assertEquals(1, WoundTier.of(14.0f, 15.5f, 5));
        assertEquals(2, WoundTier.of(12.0f, 15.5f, 5));
    }

    @Test
    void tiersAreClampedToTheSupportedRange() {
        assertEquals(WoundTier.MIN_TIERS, WoundTier.clampTiers(0));
        assertEquals(WoundTier.MIN_TIERS, WoundTier.clampTiers(-5));
        assertEquals(WoundTier.MAX_TIERS, WoundTier.clampTiers(99));
        assertEquals(7, WoundTier.clampTiers(7));
        // 越界配置不能让渲染层拿到 0 档或负数档。
        assertEquals(WoundTier.MIN_TIERS, WoundTier.of(0.0f, 20.0f, 0));
    }

    @Test
    void lostPointsForMatchesTheTierBoundary() {
        // 文档与单测都依赖这条换算：它必须真的就是进入该档所需的最少掉血量。
        for (int tiers : new int[]{2, 3, 5, 8, 10}) {
            for (float maxHealth : new float[]{6.0f, 20.0f, 40.0f, 15.5f}) {
                int maxPoints = Math.max(1, (int) Math.ceil(maxHealth));
                for (int tier = 1; tier <= tiers; tier++) {
                    int boundary = WoundTier.lostPointsFor(tier, maxHealth, tiers);
                    assertTrue(boundary >= 1, "第 " + tier + " 档的边界至少要掉 1 点");
                    assertTrue(WoundTier.of(maxPoints - boundary, maxHealth, tiers) >= tier,
                            "掉 " + boundary + " 点必须至少到第 " + tier + " 档");
                    assertTrue(WoundTier.of(maxPoints - boundary + 1, maxHealth, tiers) < tier,
                            "掉 " + (boundary - 1) + " 点时还不该到第 " + tier + " 档");
                }
                assertEquals(0, WoundTier.lostPointsFor(0, maxHealth, tiers));
            }
        }
    }

    @Test
    void hugeMaxHealthDoesNotOverflow() {
        // 属性被数据包推到极大值时，(int) 截断后的差值乘档位数会溢出 int；这里只能落在合法范围内。
        int tier = WoundTier.of(1.0f, 1.0e9f, 10);
        assertTrue(tier >= 1 && tier <= 10, "档位必须仍然合法，实际 " + tier);
    }
}
