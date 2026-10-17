package com.kinetic.client.renderer;

import com.kinetic.capability.PlayerAnimatableCapability;
import com.kinetic.client.entity.CustomFirstPersonArmEntity;
import com.kinetic.client.wound.WoundFeature;
import com.kinetic.client.wound.WoundRenderer;
import com.kinetic.client.wound.WoundTier;
import com.kinetic.event.api.SpecialPlayerRenderEvent;
import com.kinetic.geckolib3.core.util.Color;
import com.kinetic.geckolib3.geo.CustomTranslucentRenderType;
import com.kinetic.natives.render.NativeRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraftforge.common.MinecraftForge;

public class CustomFirstPersonArmRenderer {
    private CustomFirstPersonArmEntity armEntity = null;

    @SuppressWarnings("all")
    public void render(LocalPlayer player, PlayerAnimatableCapability cap, HumanoidArm arm,
                       PoseStack poseStack, MultiBufferSource bufferSource,
                       int packedLight, float partialTick) {
        if (armEntity == null || armEntity.getEntity() != player) {
            armEntity = new CustomFirstPersonArmEntity(player, cap);
        }

        armEntity.checkModelUpdate();
        var data = armEntity.update(partialTick);
        if (data == null || !data.modelState.isValid()) {
            return;
        }

        var renderEvent = new SpecialPlayerRenderEvent(player, cap, cap.getModelId());
        if (MinecraftForge.EVENT_BUS.post(renderEvent)) {
            return;
        }

        var textureLocation = renderEvent.getTextureLocationOverride() == null ? cap.getTextureLocation() : renderEvent.getTextureLocationOverride();
        var renderType = CustomTranslucentRenderType.create(textureLocation);
        var vertexConsumer = bufferSource.getBuffer(renderType);

        poseStack.pushPose();
        if (arm == HumanoidArm.LEFT) {
            poseStack.translate(0.25, 1.8, 0);
        } else {
            poseStack.translate(-0.25, 1.8, 0);
        }
        poseStack.scale(-1, -1, 1);

        NativeRenderer.render(vertexConsumer, poseStack.last(), data.modelState.getNativeState(), data.modelState.getVertexCount(),
                packedLight, OverlayTexture.NO_OVERLAY, Color.WHITE.getColor(), data.ctx.nativeType());

        // 自己的手臂也要带伤：第一人称下玩家看到的就是这条手臂。
        // 手臂模型用的是同一套原版皮肤 UV，所以同一张遮罩直接对得上。
        if (WoundFeature.enabled() && WoundFeature.allowsModel(cap.getModelId())) {
            int tier = WoundTier.of(player.getHealth(), player.getMaxHealth(), WoundFeature.tiers());
            WoundRenderer.render(bufferSource, poseStack, data.modelState, data.ctx.nativeType(),
                    renderType, packedLight, OverlayTexture.NO_OVERLAY, tier,
                    WoundFeature.configuredTint());
        }
        poseStack.popPose();
    }
}
