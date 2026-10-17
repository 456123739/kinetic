package com.kinetic.geckolib3.geo;

import com.kinetic.config.ClientConfig;
import com.kinetic.geckolib3.core.util.Color;
import com.kinetic.geckolib3.model.AnimatableEntity;
import com.kinetic.natives.render.NativeRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

public interface IGeoRenderer<T extends AnimatableEntity<?>> {
    default void preRender(GeoRenderData data, T animatable, PoseStack poseStack,
                           MultiBufferSource bufferSource, int packedLight, int packedOverlay, Color color) {
        poseStack.scale(data.widthScale, data.heightScale, data.widthScale);
    }

    default void render(GeoRenderData data, T animatable,
                        RenderType type, PoseStack poseStack, MultiBufferSource bufferSource,
                        int packedLight, int packedOverlay, Color color) {
        var buffer = bufferSource.getBuffer(type);
        var modelState = data.modelState;
        NativeRenderer.render(
                buffer, poseStack.last(), modelState.getNativeState(), modelState.getVertexCount(),
                packedLight, packedOverlay, color.getColor(), data.ctx.nativeType());
    }

    default void postRender(GeoRenderData data, T animatable, PoseStack poseStack,
                           MultiBufferSource bufferSource, int packedLight, int packedOverlay, Color color) {
        animatable.countRender();
    }

    @Nullable
    default RenderType getRenderType(ResourceLocation texture, boolean visible, boolean glowing, boolean translucent) {
        if (visible) {
            return entityRenderType(texture, translucent);
        }
        return glowing ? RenderType.outline(texture) : null;
    }

    /**
     * 可见模型用的渲染类型。
     *
     * <p>抽成静态是因为叠加层（例如伤口遮罩）也必须用<b>同一个</b>类型来画：
     * 两者落在同一个深度上，谁后画谁赢，只有同族类型才能保证刷批顺序可控。
     */
    static RenderType entityRenderType(ResourceLocation texture, boolean translucent) {
        return entityRenderType(texture, translucent, ClientConfig.CULL_BACK_FACES.get());
    }

    /**
     * 同上，但由调用方指定是否裁剪背面。
     *
     * <p>唯一的用法是布娃娃：当本地相机就在这具尸体的脑袋里时，必须强制裁剪，否则会看见自己头骨的
     * 内壁（提供方的绘制契约里也是这么做的，见 {@code ClientRagdollCamera#isHeadHidden}）。
     */
    static RenderType entityRenderType(ResourceLocation texture, boolean translucent, boolean cullBackFaces) {
        // 透明渲染类型会强制每次 batch flush 排序全部 quad，并在 GPU 上以混合、不写深度的方式绘制整个
        // 模型。原生烘焙把 alpha 裁剪面（kCutout）也放进了透明分区，所以几乎任何模型都会落到这条路径。
        // 默认改回原版玩家模型使用的 alpha 裁剪类型，见 ClientConfig.FORCE_CUTOUT_RENDERER。
        if (translucent && !ClientConfig.FORCE_CUTOUT_RENDERER.get()) {
            return CustomTranslucentRenderType.create(texture);
        }
        // 基岩模型由封闭方盒拼成，背面永远看不见；裁剪掉它只省片元，不改画面。
        // 个别包会有刻意做成单面的几何（见 ClientConfig.CULL_BACK_FACES），所以留了开关。
        return cullBackFaces
                ? RenderType.entityCutout(texture)
                : RenderType.entityCutoutNoCull(texture);
    }
}
