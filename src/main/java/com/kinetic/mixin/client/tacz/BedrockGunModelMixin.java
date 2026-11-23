package com.kinetic.mixin.client.tacz;

import com.kinetic.client.compat.tacz.mesh.TaczGunMeshHook;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 静态烘焙的注入点：TaCZ 枪械模型的最终渲染重载。
 *
 * <p>只在这个 11 参数重载上注入——TaCZ 1.2.0 里其余重载最终都会调到它，所以在 HEAD 取消一次就能覆盖
 * 全部调用方式，不会出现"某条路径还在走原渲染"的半吊子状态。
 *
 * <p>{@code remap = false}：这是 TaCZ 自己的方法，不是原版方法，不能按 SRG 重映射。
 */
@Mixin(value = com.tacz.guns.client.model.BedrockGunModel.class, remap = false)
public class BedrockGunModelMixin {
    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/item/ItemStack;"
            + "Lnet/minecraft/world/item/ItemDisplayContext;Lnet/minecraft/client/renderer/RenderType;"
            + "IIFFFFLnet/minecraft/client/renderer/MultiBufferSource;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void kinetic$staticGunMesh(PoseStack pose, ItemStack stack, ItemDisplayContext context,
                                       RenderType renderType, int light, int overlay, float red, float green,
                                       float blue, float alpha, MultiBufferSource buffers, CallbackInfo callback) {
        if (TaczGunMeshHook.tryRender((com.tacz.guns.client.model.BedrockGunModel) (Object) this,
                pose, renderType, light)) {
            callback.cancel();
        }
    }
}
