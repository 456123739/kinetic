package com.kinetic.client.wound;

import com.kinetic.geckolib3.geo.CustomTranslucentRenderType;
import com.kinetic.geckolib3.geo.animated.GeoModelState;
import com.kinetic.natives.render.NativeRenderer;
import com.kinetic.natives.render.RenderContextType;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * 把伤口遮罩叠到已经画好的模型上。
 *
 * <p><b>为什么是「再画一遍整个模型」而不是「合成一张新贴图」</b>：合成贴图要为每个
 * (皮肤, 档位) 组合生成并上传一张新贴图，而皮肤是按玩家来的——16 个玩家就是 16 张起步，
 * 每换一档还要再来一轮。这里复用的是同一份已经烘焙好的原生顶点数据（{@link GeoModelState}），
 * 只是换一张 64×64 的共享遮罩再提交一遍，遮罩总数与玩家数无关。玩家模型本来就只有两三千个顶点，
 * 多提交一遍的代价远小于一次贴图生成 + GL 上传。
 *
 * <p><b>为什么要显式刷批</b>：伤口和身体落在<b>完全相同</b>的深度上，靠深度测试通过，
 * 所以「谁后画谁赢」。同族 RenderType 的入队顺序确实能保证身体在前，但
 * {@code renderLayersFirst} 的模型会先跑叠加层、那时身体还没入队。这里在提交伤口之前显式
 * {@code endBatch} 一次身体那一批，顺序就与模型配置无关了——也正因为身体已经画完，
 * 叠加层才能用「混合 + 写深度 + 不排序」的 {@link WoundRenderType}，不必为排序付代价。
 *
 * <p><b>为什么分两遍</b>：一遍画基础层遮罩、一遍画第二层遮罩。第二层那一遍用深度相等测试，
 * 只有第二层皮肤真的不透明（真的写了深度）的地方才会出现，因此外层皮肤存在时血贴在外层、
 * 不存在时才贴在基础层，不会有一层血浮在半空。两遍都复用同一份原生顶点数据，只是遮罩与
 * 深度档不同。
 */
public final class WoundRenderer {
    private WoundRenderer() {
    }

    /**
     * 在身体之上叠一层伤口；档位为 0、模型无效或遮罩取不到时什么都不做。
     *
     * @param bodyRenderType 身体刚刚用的渲染类型，用来把身体那一批刷出去，并判断身体有没有写深度
     * @param tintArgb       血色（ARGB）。遮罩是灰度的，这里作为顶点色与遮罩相乘
     */
    public static void render(MultiBufferSource buffer, PoseStack poseStack,
                              GeoModelState modelState, RenderContextType contextType,
                              @Nullable RenderType bodyRenderType,
                              int packedLight, int packedOverlay, int tier, int tintArgb) {
        if (tier <= 0 || modelState == null || !modelState.isValid()) {
            return;
        }
        int vertexCount = modelState.getVertexCount();
        if (vertexCount <= 0) {
            return;
        }
        int tiers = WoundFeature.tiers();
        int density = WoundFeature.density();
        ResourceLocation base = WoundMasks.locationFor(tier, tiers, density, WoundMaskPainter.Layer.BASE);
        ResourceLocation overlay = WoundMasks.locationFor(tier, tiers, density, WoundMaskPainter.Layer.OVERLAY);
        if (base == null && overlay == null) {
            return;
        }
        if (bodyRenderType != null && buffer instanceof MultiBufferSource.BufferSource source) {
            source.endBatch(bodyRenderType);
        }
        // 身体走透明渲染类型时不写深度，相等测试会把所有像素都判掉；那种配置下退回 LEQUAL。
        boolean equalDepth = writesDepth(bodyRenderType);
        if (base != null) {
            draw(buffer, poseStack, modelState, contextType, base, equalDepth,
                    packedLight, packedOverlay, vertexCount, tintArgb);
        }
        if (overlay != null) {
            draw(buffer, poseStack, modelState, contextType, overlay, equalDepth,
                    packedLight, packedOverlay, vertexCount, tintArgb);
        }
    }

    private static void draw(MultiBufferSource buffer, PoseStack poseStack, GeoModelState modelState,
                             RenderContextType contextType, ResourceLocation mask, boolean equalDepth,
                             int packedLight, int packedOverlay, int vertexCount, int tintArgb) {
        NativeRenderer.render(
                buffer.getBuffer(WoundRenderType.of(mask, equalDepth)),
                poseStack.last(), modelState.getNativeState(), vertexCount,
                packedLight, packedOverlay, tintArgb, contextType);
    }

    /** 该渲染类型是否会把深度写进缓冲。只有身体走透明路径时才是否。 */
    private static boolean writesDepth(@Nullable RenderType bodyRenderType) {
        return !(bodyRenderType instanceof CustomTranslucentRenderType);
    }
}
