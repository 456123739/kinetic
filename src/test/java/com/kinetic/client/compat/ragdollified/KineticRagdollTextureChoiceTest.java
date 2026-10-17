package com.kinetic.client.compat.ragdollified;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 布娃娃尸体贴图的来源优先级。
 *
 * <p>回归点：内置 Steve/Alex 模型的「能力贴图」是 KINETIC 自带的资源贴图
 * （{@code gsl.png} / {@code tartaric_acid.png}），不是玩家皮肤。死亡瞬间的快照若直接存
 * 能力贴图，客户端玩家实体被移除后尸体就会穿上 KINETIC 资源里的皮肤，而不是玩家自己的皮肤。
 */
class KineticRagdollTextureChoiceTest {
    private static final ResourceLocation SKIN_CHAIN =
            new ResourceLocation("quickskin", "player/abc");
    private static final ResourceLocation PLAYER_SKIN =
            new ResourceLocation("minecraft", "skins/abc");
    private static final ResourceLocation BUILTIN =
            new ResourceLocation("kinetic", "builtin/misc/2_steve/textures/tartaric_acid");

    @Test
    void skinChainOverrideAlwaysWins() {
        assertEquals(SKIN_CHAIN, KineticRagdollRenderer.chooseTexture(SKIN_CHAIN, true, PLAYER_SKIN, BUILTIN));
        assertEquals(SKIN_CHAIN, KineticRagdollRenderer.chooseTexture(SKIN_CHAIN, false, PLAYER_SKIN, BUILTIN));
    }

    @Test
    void builtinModelWithoutSkinChainFallsBackToThePlayerSkin() {
        assertEquals(PLAYER_SKIN, KineticRagdollRenderer.chooseTexture(null, true, PLAYER_SKIN, BUILTIN),
                "内置玩家模型绝不能用 KINETIC 资源贴图当兜底");
    }

    @Test
    void customModelKeepsItsOwnCapabilityTexture() {
        assertEquals(BUILTIN, KineticRagdollRenderer.chooseTexture(null, false, PLAYER_SKIN, BUILTIN),
                "作品模型自带贴图，不该被玩家皮肤顶掉");
    }

    @Test
    void missingPlayerSkinStillFallsBackToTheCapabilityTexture() {
        assertEquals(BUILTIN, KineticRagdollRenderer.chooseTexture(null, true, null, BUILTIN));
        assertNull(KineticRagdollRenderer.chooseTexture(null, true, null, null));
    }

    @Test
    void builtinPlayerModelsAreRecognisedByModelId() {
        assertTrue(KineticRagdollRenderer.isBuiltinPlayerModel("misc/2_steve"));
        assertTrue(KineticRagdollRenderer.isBuiltinPlayerModel("misc/1_alex"));
        assertFalse(KineticRagdollRenderer.isBuiltinPlayerModel("custom/some_gun_model"));
        assertFalse(KineticRagdollRenderer.isBuiltinPlayerModel(null));
    }
}
