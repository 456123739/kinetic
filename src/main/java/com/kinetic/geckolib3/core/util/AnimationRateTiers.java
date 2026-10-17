package com.kinetic.geckolib3.core.util;

/**
 * 动画求值频率阶梯的纯函数部分：把用户配置钳制成「随距离非递增、且不超过上限」的合法阶梯。
 *
 * <p>配置文件里可以写出任意组合（例如远处频率比近处还高、远处距离比近处还近），
 * 这里统一钳制，调用方拿到的永远是合法阶梯。钳制规则：
 * <ul>
 *   <li>{@code base = min(refreshRate, maxRate)}；{@code maxRate <= 0} 表示不设上限，直接用刷新率；</li>
 *   <li>{@code near = min(nearRate, base)}；</li>
 *   <li>{@code far = min(farRate, near)}；</li>
 *   <li>{@code hidden = min(hiddenRate, far)}；</li>
 *   <li>每个频率至少为 1（{@code 1/limit} 是 RateLimiter 的间隔，0 会让间隔变成无穷大而永不求值）；</li>
 *   <li>{@code farDistance = max(farDistance, nearDistance)}，否则近处档位为空。</li>
 * </ul>
 * 于是恒有 {@code base >= near >= far >= hidden}，距离越远求值只会更稀疏。
 */
public final class AnimationRateTiers {
    private AnimationRateTiers() {
    }

    /** 近处基准频率：刷新率，但不超过 {@code maxRate}（{@code maxRate <= 0} = 不限）。 */
    public static int base(int refreshRate, int maxRate) {
        var capped = maxRate > 0 ? Math.min(refreshRate, maxRate) : refreshRate;
        return Math.max(1, capped);
    }

    /** 把一个档位钳进 {@code [1, ceiling]}，保证阶梯不会随距离升高。 */
    public static int clamp(int configured, int ceiling) {
        return Math.max(1, Math.min(configured, ceiling));
    }

    /** 远处距离不得小于近处距离，否则近处档位会被完全跳过。 */
    public static int farDistance(int configured, int nearDistance) {
        return Math.max(configured, nearDistance);
    }
}
