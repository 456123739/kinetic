package com.kinetic.client.skin;

import com.kinetic.Kinetic;
import com.kinetic.model.catalog.builtin.BuiltinModelIndex;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * 读不到玩家皮肤时使用的兜底皮肤，随模组自带。
 *
 * <p><b>为什么不用原版资源</b>：{@code textures/entity/player/wide/steve.png} 与原版 slim 贴图是
 * <b>原版</b>的资源，会被资源包替换，也不保证仍是 Steve/Alex 的默认外观。兜底皮肤是模组自己要
 * 保证的东西，所以随包携带。
 *
 * <p><b>两个变体与模型一一对应</b>：粗手臂（{@code misc/2_steve}）用 {@code textures/default/steve.png}，
 * 细手臂（{@code misc/1_alex}）用 {@code textures/default/alex.png}。两张都是 64×64 的原版皮肤
 * 布局，与内置模型声明的贴图尺寸一致。
 *
 * <p><b>同一个兜底要覆盖两条路径</b>：活人渲染（{@link com.kinetic.client.event.VanillaPlayerRenderEvent}）
 * 与死亡布娃娃（{@code KineticRagdollRenderer}）。两条路径必须用同一套判定，否则同一个离线玩家
 * 会出现"活人穿默认皮肤、尸体穿原版皮肤"。
 */
public final class DefaultPlayerSkins {
    private static final ResourceLocation WIDE =
            new ResourceLocation(Kinetic.MOD_ID, "textures/default/steve.png");
    private static final ResourceLocation SLIM =
            new ResourceLocation(Kinetic.MOD_ID, "textures/default/alex.png");

    private DefaultPlayerSkins() {
    }

    /** 按模型变体取兜底皮肤；非细手臂一律按粗手臂处理。 */
    public static ResourceLocation forModelId(@Nullable String modelId) {
        return BuiltinModelIndex.isSlimPlayerModel(modelId) ? SLIM : WIDE;
    }

    /**
     * 玩家当前<b>确实可用</b>的皮肤；读不到时返回 {@code null}。
     *
     * <p>"读不到"包括三种情况：皮肤位置为空、仍是 {@code DefaultPlayerSkin} 按 UUID 推断的默认皮肤
     * （说明真实皮肤还没下载或根本没有）、以及离线账号。调用方拿到 {@code null} 就应当使用
     * {@link #forModelId} 或模型自带贴图，而不是把这份"占位皮肤"当成玩家皮肤。
     *
     * <p>皮肤类模组（Quick Skin 等）挂在 {@code AbstractClientPlayer#getSkinTextureLocation} 上，
     * 因此这里读到的就是它们选中的皮肤。
     */
    @Nullable
    public static ResourceLocation loadedSkinOf(@Nullable AbstractClientPlayer player) {
        if (player == null) {
            return null;
        }
        ResourceLocation location = player.getSkinTextureLocation();
        if (location == null) {
            return null;
        }
        return location.equals(DefaultPlayerSkin.getDefaultSkin(player.getUUID())) ? null : location;
    }
}
