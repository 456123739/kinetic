package com.kinetic.client.wound;

import com.kinetic.client.entity.CustomPlayerEntity;
import com.kinetic.geckolib3.geo.GeoLayerRenderer;
import com.kinetic.geckolib3.geo.GeoRenderData;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;

/**
 * 玩家模型上的伤口叠加层。
 *
 * <p>注册在 {@code CustomPlayerRenderer} 的<b>第一个</b>位置：身体刚入队就轮到它，
 * 于是它能在提交伤口之前把身体那一批刷出去，后面的手持物/帽子层照常按深度覆盖伤口。
 *
 * <p>门控按「便宜到贵」排序，远处和满血的玩家在算模型身份之前就被排除：
 * <ol>
 *   <li>特性开关 / 与 Visual Health 共存；</li>
 *   <li>档位为 0（满血）——这是绝大多数玩家的绝大多数时间；</li>
 *   <li>超出绘制距离；</li>
 *   <li>隐身（隐身玩家身上浮着伤口会直接暴露位置）；</li>
 *   <li>模型身份（最贵的一步：要查目录）。</li>
 * </ol>
 */
public final class WoundLayer extends GeoLayerRenderer<CustomPlayerEntity> {
    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, CustomPlayerEntity animatable,
                       GeoRenderData renderData, int packedLight, int overlay) {
        if (!WoundFeature.enabled()) {
            return;
        }
        var player = animatable.getEntity();
        int tier = WoundTier.of(player.getHealth(), player.getMaxHealth(), WoundFeature.tiers());
        if (tier <= 0) {
            return;
        }
        var minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        Entity camera = minecraft.getCameraEntity() != null ? minecraft.getCameraEntity() : minecraft.player;
        if (player.distanceToSqr(camera) > WoundFeature.renderDistanceSq()) {
            return;
        }
        if (player.isInvisibleTo(minecraft.player)) {
            return;
        }
        if (!WoundFeature.allowsModel(animatable.getModelId())) {
            return;
        }
        WoundRenderer.render(buffer, poseStack, renderData.modelState, renderData.ctx.nativeType(),
                renderData.bodyRenderType, packedLight, overlay, tier, WoundFeature.configuredTint());
    }
}
