package com.kinetic.capability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 「这一帧按哪个皮肤模型名判定宽/细」的选择规则。
 *
 * <p>Quick Skin 在 `CLIENT_PLAYER_JOIN` 里才把保存的皮肤应用到玩家身上，而 KINETIC 的变体判定
 * 可能在那之前就跑过一次。只读 `AbstractClientPlayer#getModelName()` 会拿到原版皮肤（宽手臂），
 * 要等它的事件回来才纠正——玩家看到的就是"进世界后还得再切一次粗细"。
 */
class QuickSkinModelNamePreferenceTest {
    @Test
    void quickSkinValueWinsWhenItIsAResolvedModel() {
        assertEquals("slim", PlayerAnimatableCapability.preferQuickSkinModelName("slim", "default"),
                "Quick Skin 记着细手臂时，必须用它，而不是原版皮肤名");
        assertEquals("classic", PlayerAnimatableCapability.preferQuickSkinModelName("classic", "slim"),
                "Quick Skin 记着宽手臂时同理（Quick Skin 用 classic，原版用 default）");
    }

    @Test
    void fallsBackToVanillaWhenQuickSkinIsSilentUnresolvedOrAbsent() {
        assertEquals("default", PlayerAnimatableCapability.preferQuickSkinModelName(null, "default"),
                "没装 Quick Skin / 还没绑定：用原版皮肤名");
        assertEquals("default", PlayerAnimatableCapability.preferQuickSkinModelName("", "default"));
        assertEquals("default", PlayerAnimatableCapability.preferQuickSkinModelName("auto", "default"),
                "auto 表示还没解析，必须交给原版（此时 mixin 已把解析结果写进 getSkin）");
        assertEquals("slim", PlayerAnimatableCapability.preferQuickSkinModelName("AUTO", "slim"),
                "大小写不敏感");
    }
}
