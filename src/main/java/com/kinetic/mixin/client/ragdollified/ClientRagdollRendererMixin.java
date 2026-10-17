package com.kinetic.mixin.client.ragdollified;

import com.kinetic.client.compat.ragdollified.KineticRagdollRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.raiiiden.ragdollified.RagdollPart;
import com.raiiiden.ragdollified.RagdollTransform;
import com.raiiiden.ragdollified.client.ClientRagdoll;
import com.raiiiden.ragdollified.client.ClientRagdollRenderer;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 让出 Ragdollified 玩家布娃娃的「原版人形身体」，改由 KINETIC 模型绘制。
 *
 * <p>只取消最基础的身体部件（{@code renderHumanoidPartPhysics} 的 7 参数重载，玩家身体路径专用）；
 * 护甲走 {@code renderHumanoidPartPhysicsTinted}、饰品与血迹各有独立路径，都不受影响。
 * 断肢（{@code ClientDetachedLimb}）走 8 参数重载，同样保持原版表现。
 *
 * <p><b>侵入范围与降级</b>：只在本帧确实由 KINETIC 接管了该布娃娃时取消，且所有注入点
 * {@code require = 0}。Ragdollified 缺失时本 Mixin 不装载；版本变化导致注入点失配时，
 * KINETIC 侧因为拿不到接管状态而根本不绘制自己的模型，于是完整回退到 Ragdollified 的原版人形，
 * 不会出现两套模型同时绘制。{@code remap = false}：目标是模组自身成员，类名不参与重混淆。
 */
@Mixin(value = ClientRagdollRenderer.class, remap = false)
public class ClientRagdollRendererMixin {
    @Inject(method = "renderPlayerRagdoll(Lcom/raiiiden/ragdollified/client/ClientRagdoll;"
            + "Lcom/raiiiden/ragdollified/client/ClientRagdoll$TransformSnapshot;"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;"
            + "IFD)V", at = @At("HEAD"), require = 0)
    private static void kinetic$beginPlayerRagdoll(ClientRagdoll ragdoll, ClientRagdoll.TransformSnapshot snapshot,
                                              PoseStack poseStack, MultiBufferSource buffer, int light,
                                              float partialTick, double distSq, CallbackInfo callback) {
        KineticRagdollRenderer.beginPlayerRagdoll(ragdoll);
    }

    @Inject(method = "renderPlayerRagdoll(Lcom/raiiiden/ragdollified/client/ClientRagdoll;"
            + "Lcom/raiiiden/ragdollified/client/ClientRagdoll$TransformSnapshot;"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;"
            + "IFD)V", at = @At("RETURN"), require = 0)
    private static void kinetic$endPlayerRagdoll(ClientRagdoll ragdoll, ClientRagdoll.TransformSnapshot snapshot,
                                            PoseStack poseStack, MultiBufferSource buffer, int light,
                                            float partialTick, double distSq, CallbackInfo callback) {
        KineticRagdollRenderer.endPlayerRagdoll();
    }

    @Inject(method = "renderHumanoidPartPhysics(Lcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/client/model/geom/ModelPart;"
            + "Lcom/raiiiden/ragdollified/RagdollTransform;Lcom/raiiiden/ragdollified/RagdollTransform;"
            + "ILcom/raiiiden/ragdollified/RagdollPart;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private static void kinetic$skipHumanoidBody(PoseStack poseStack, VertexConsumer vertexConsumer, ModelPart part,
                                            RagdollTransform transform, RagdollTransform torso, int light,
                                            RagdollPart ragdollPart, CallbackInfo callback) {
        if (KineticRagdollRenderer.shouldSkipHumanoidBody()) {
            callback.cancel();
        }
    }
}
