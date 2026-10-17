package com.kinetic.client.wound;

/**
 * 受伤档位：把「当前血量 / 最大血量」映射到 {@code 0..tiers} 的整数档位。
 *
 * <p><b>为什么是纯函数、不留状态</b>：档位必须每帧现算。一旦引入状态（迟滞、渐变、缓存上一次的档位），
 * 治疗、金苹果、重生之后伤口就会滞留，玩家看到的伤口和自己的血量对不上。回血即恢复是这一层的硬要求。
 *
 * <p><b>为什么先量化到整点再算</b>：直接用 {@code (1 - hp/max) * tiers} 会在边界上抖：
 * {@code 1 - 0.8 = 0.19999999999999996}，乘 5 之后落在 1.0 的哪一侧并不确定，
 * 同一血量可能一帧一档来回跳，看起来就是伤口闪。这里先把血量向上取整成整点，再用整数除法，
 * 结果是确定的：扣满一个整点才可能升一档，浮点误差不再影响档位。
 *
 * <p>量化也顺带满足「伤口不要过度」：20 点血、5 档时，掉 3 点血（不足一档的宽度）不出伤口，
 * 掉 4 点才出现第一处。零星掉血不会让玩家身上立刻开花。
 */
public final class WoundTier {
    /** 档位数量下限：低于 2 档就没有「随血量变化」可言。 */
    public static final int MIN_TIERS = 2;
    /** 档位数量上限：超过 10 档在 64×64 的皮肤布局上已经挤不下，只会互相叠。 */
    public static final int MAX_TIERS = 10;
    public static final int DEFAULT_TIERS = 5;

    private WoundTier() {
    }

    /** 档位数量收敛到可用范围；配置越界时渲染层不应拿到 0 或负数。 */
    public static int clampTiers(int tiers) {
        return Math.max(MIN_TIERS, Math.min(MAX_TIERS, tiers));
    }

    /**
     * @param health    当前血量。NaN 按「已倒下」处理；非正数按「已倒下」处理
     * @param maxHealth 最大血量。非正数表示拿不到血量（实体刚生成、能力未同步），返回 0 档——宁可不画
     * @return 0（满血）到 {@code clampTiers(tiers)}（倒下）
     */
    public static int of(float health, float maxHealth, int tiers) {
        int count = clampTiers(tiers);
        if (!(maxHealth > 0.0f)) {
            return 0;
        }
        if (Float.isNaN(health) || health <= 0.0f) {
            return count;
        }
        int maxPoints = (int) Math.ceil(maxHealth);
        if (maxPoints < 1) {
            maxPoints = 1;
        }
        int currentPoints = (int) Math.ceil(health);
        if (currentPoints >= maxPoints) {
            return 0;
        }
        if (currentPoints < 0) {
            currentPoints = 0;
        }
        // long 参与：属性被数据包推到极大值时，(int) 截断后的差值乘档位数会溢出 int。
        long lost = (long) maxPoints - currentPoints;
        long tier = lost * count / maxPoints + 1L;
        return (int) Math.min(count, tier);
    }

    /**
     * 进入某档所需的最少掉血整点数，仅用于文档与单测断言。
     *
     * <p>定义是「使 {@code of} 的结果 {@code >= tier} 的最小掉血量」，而不是「恰好等于 tier」：
     * 当档位数多于最大血量整点数时，有些档位根本落不到（例如 6 点血配 10 档，掉 1 点直接是第 2 档），
     * 用「恰好等于」的语义会在这些配置上给出没有意义的边界。
     *
     * @return 掉够这么多整点血才进入 {@code tier} 档或更高；{@code tier <= 0} 时返回 0
     */
    public static int lostPointsFor(int tier, float maxHealth, int tiers) {
        int count = clampTiers(tiers);
        int maxPoints = Math.max(1, (int) Math.ceil(maxHealth));
        int clamped = Math.max(0, Math.min(count, tier));
        if (clamped == 0) {
            return 0;
        }
        // 需要 floor(lost * count / maxPoints) >= tier - 1，即 lost * count >= (tier - 1) * maxPoints。
        long numerator = (long) (clamped - 1) * maxPoints;
        long value = (numerator + count - 1) / count;
        // 第 1 档起就至少要掉 1 点：掉 0 点是满血，属于第 0 档。
        return (int) Math.max(1L, value);
    }
}
