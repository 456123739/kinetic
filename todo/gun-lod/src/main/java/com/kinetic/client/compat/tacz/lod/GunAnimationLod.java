package com.kinetic.client.compat.tacz.lod;

import org.jetbrains.annotations.Nullable;

/**
 * 枪械<b>动画</b> LOD：超过距离后，只允许"持枪/移动"这类基本姿态，屏蔽换弹、拉栓、开火、
 * 攀爬、近战等状态机动作。
 *
 * <p>与物品 LOD（{@link GunLodDecimator} 那一路）是<b>两件独立的事</b>：物品 LOD 决定画什么模型，
 * 动画 LOD 决定跑不跑 TaCZ 的逐实体动画状态机。二者由两个独立配置项控制。
 *
 * <p>本类不引用任何 TaCZ 类型，纯字符串判定，可离线单测。
 */
public final class GunAnimationLod {
    /** 远处一律屏蔽的前缀：换弹、攀爬、近战。 */
    private static final String[] SUPPRESSED_PREFIXES = {"tac:reload:", "tac:climb", "tac:melee:"};

    /** 出现即屏蔽：开火相关（{@code tac:aim:fire:*}、{@code tac:hold:fire:*}、{@code tac:climbing:fire:*} 等）。 */
    private static final String SUPPRESSED_MARKER = "fire";

    private GunAnimationLod() {
    }

    /**
     * 超出动画 LOD 距离时，该枪械动画是否仍允许播放。
     *
     * @param animationName 动画名，可能为 null
     * @param beyondLodDistance 该玩家是否已超出动画 LOD 距离
     * @return true 表示允许播放；false 表示应屏蔽（保留上一帧的基本姿态）
     */
    public static boolean isAllowed(@Nullable String animationName, boolean beyondLodDistance) {
        if (!beyondLodDistance) {
            return true;
        }
        if (animationName == null || animationName.isEmpty()) {
            return true;
        }
        if (animationName.contains(SUPPRESSED_MARKER)) {
            return false;
        }
        for (String prefix : SUPPRESSED_PREFIXES) {
            if (animationName.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }
}
