package com.kinetic.client.renderer.layer;

import com.kinetic.client.compat.slashblade.SlashBladeCompat;
import com.kinetic.client.compat.slashblade.SlashBladeRender;
import com.kinetic.client.compat.swarfare.SWarfareCompat;
import com.kinetic.client.compat.tacz.TACZCompat;
import com.kinetic.client.compat.tacz.lod.GunLodDistance;
import com.kinetic.client.compat.tacz.lod.GunLodHandle;
import com.kinetic.client.entity.CustomPlayerEntity;
import com.kinetic.client.model.locator.PlayerLocator;
import com.kinetic.config.ClientConfig;
import com.kinetic.geckolib3.geo.GeoLayerRenderer;
import com.kinetic.geckolib3.geo.GeoRenderData;
import com.kinetic.geckolib3.geo.render.built.GeoLocator;
import com.kinetic.natives.render.NativeRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

public class CustomPlayerItemInHandLayer extends GeoLayerRenderer<CustomPlayerEntity> {
    private final ItemInHandRenderer itemInHandRenderer;

    public CustomPlayerItemInHandLayer(ItemInHandRenderer itemInHandRenderer) {
        this.itemInHandRenderer = itemInHandRenderer;
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, CustomPlayerEntity animatable, GeoRenderData renderData, int packedLight, int overlay) {
        var entityLivingBaseIn = animatable.getEntity();
        var mainHandItem = entityLivingBaseIn.getMainHandItem();
        if (!mainHandItem.isEmpty() && renderData.modelState.locatorGroupSize(PlayerLocator.get().rightHand) > 0) {
            if (SlashBladeCompat.isSlashBladeItem(mainHandItem)) {
                SlashBladeRender.renderMainhandSlashBlade(entityLivingBaseIn, renderData, poseStack, buffer, packedLight, mainHandItem);
            } else if (!this.renderGunLod(entityLivingBaseIn, mainHandItem, PlayerLocator.get().rightHand,
                                          renderData, poseStack, buffer, packedLight, overlay,
                                          CustomPlayerItemInHandLayer::mainHandTransform)) {
                TACZCompat.openFlashShellRender(entityLivingBaseIn, mainHandItem);
                this.renderArmWithItem(entityLivingBaseIn, mainHandItem, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                                       HumanoidArm.RIGHT, renderData, poseStack, buffer, packedLight);
                TACZCompat.stopFlashShellRender(mainHandItem);
            }
        }

        var offhandItem = entityLivingBaseIn.getOffhandItem();
        if (!offhandItem.isEmpty() && renderData.modelState.locatorGroupSize(PlayerLocator.get().leftHand) > 0) {
            if (SlashBladeCompat.isSlashBladeItem(offhandItem)) {
                SlashBladeRender.renderOffhandSlashBlade(renderData, poseStack, buffer, packedLight, offhandItem);
            } else {
                // 卓越前线副手枪械不用这个渲染
                if (!SWarfareCompat.isGun(offhandItem)) {
                    this.renderArmWithItem(entityLivingBaseIn, mainHandItem,
                                           ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                                           HumanoidArm.RIGHT, renderData, poseStack, buffer, packedLight);
                }
            }
            // TACZ 副手枪械渲染（远处改用自建简化模型）
            var pistol = TACZCompat.isPistol(offhandItem);
            if (!this.renderGunLod(entityLivingBaseIn, offhandItem,
                                   pistol ? PlayerLocator.get().pistol : PlayerLocator.get().rifle,
                                   renderData, poseStack, buffer, packedLight, overlay,
                                   pistol ? CustomPlayerItemInHandLayer::offhandPistolTransform
                                          : CustomPlayerItemInHandLayer::offhandRifleTransform)) {
                TACZCompat.renderOffsetHand(offhandItem, renderData, entityLivingBaseIn, poseStack, buffer, packedLight);
            }
            // 卓越前线副手枪械渲染
            SWarfareCompat.renderOffsetHand(offhandItem, renderData, entityLivingBaseIn, poseStack, buffer, packedLight);
        }
    }

    /** 主手：与默认手部物品完全一致的手部变换。 */
    private static void mainHandTransform(PoseStack pose) {
        pose.translate(0, -0.0625, -0.1);
        pose.mulPose(Axis.XP.rotationDegrees(-90.0F));
    }

    /** 副手手枪：与 {@code TacCompatInner.renderOffhandGun} 的 locator 变换一致。 */
    private static void offhandPistolTransform(PoseStack pose) {
        pose.translate(0, -0.125, 0);
        pose.scale(0.65f, 0.65f, 0.65f);
        pose.mulPose(Axis.YP.rotationDegrees(-90.0F));
        pose.mulPose(Axis.ZP.rotationDegrees(90.0F));
    }

    /** 副手长枪：与 {@code TacCompatInner.renderOffhandGun} 的 locator 变换一致。 */
    private static void offhandRifleTransform(PoseStack pose) {
        pose.scale(0.65f, 0.65f, 0.65f);
        pose.mulPose(Axis.YP.rotationDegrees(-180.0F));
    }

    /**
     * 其他玩家手持的 TaCZ 枪械：在 LOD 距离外用 KINETIC 自建的简化模型替代 TaCZ 的完整模型。
     *
     * <p>返回 {@code true} 表示已经完成渲染，调用方<b>不得</b>再走完整模型路径；返回
     * {@code false} 时调用方必须原样回退，保证"没有 LOD"时的行为与改动前完全一致。
     */
    private boolean renderGunLod(LivingEntity entity, ItemStack stack, GeoLocator locator,
                                 GeoRenderData data, PoseStack poseStack, MultiBufferSource buffer,
                                 int packedLight, int packedOverlay,
                                 Consumer<PoseStack> transform) {
        var handle = lodHandleFor(entity, stack);
        if (handle == null || !handle.prepare()) {
            return false;
        }
        var multiplier = (float) (double) ClientConfig.OTHER_GUN_ITEM_LOD_SCALE.get();
        var scale = handle.scale();
        var sx = scale.x * multiplier;
        var sy = scale.y * multiplier;
        var sz = scale.z * multiplier;
        if (!isUsable(sx) || !isUsable(sy) || !isUsable(sz)) {
            return false;
        }
        // 模型坐标系里的"手"节点偏移：TaCZ 的第三人称摆放就是把 thirdperson_hand 搬到手上。
        // 逐轴缩放必须和它配套，否则不等比的枪包会被压变形。
        var anchor = handle.anchor();
        var ax = anchor.x * sx / 16.0f;
        var ay = anchor.y * sy / 16.0f;
        var az = anchor.z * sz / 16.0f;
        var offsetX = (float) (double) ClientConfig.OTHER_GUN_ITEM_LOD_OFFSET_X.get();
        var offsetY = (float) (double) ClientConfig.OTHER_GUN_ITEM_LOD_OFFSET_Y.get();
        var offsetZ = (float) (double) ClientConfig.OTHER_GUN_ITEM_LOD_OFFSET_Z.get();
        var renderType = handle.renderType();
        data.modelState.visitLocatorGroup(locator, poseStack, pose -> {
            transform.accept(pose);
            pose.translate(ax + offsetX, ay + offsetY, az + offsetZ);
            pose.scale(sx, sy, sz);
            NativeRenderer.render(buffer.getBuffer(renderType), pose.last(),
                    handle.nativeState(), handle.vertexCount(),
                    packedLight, packedOverlay, 0xFFFFFFFF, data.ctx.nativeType());
        });
        return true;
    }

    private static boolean isUsable(float value) {
        return value > 0.0f && Float.isFinite(value);
    }

    /**
     * 该物品此刻是否应该用 LOD 渲染。默认（距离配置为 0）其他玩家的枪一直用 LOD。
     *
     * <p>自己（含第三人称下的相机实体）永不降级，所以手里的枪始终是完整模型。
     * 距离用相机位置做平方比较，不构造 {@code Vec3}。
     */
    @Nullable
    private static GunLodHandle lodHandleFor(LivingEntity entity, ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        var minecraft = Minecraft.getInstance();
        if (entity == minecraft.player || entity == minecraft.getCameraEntity()) {
            return null;
        }
        var limit = ClientConfig.OTHER_GUN_ITEM_LOD_DISTANCE.get();
        if (limit > 0) {
            var camera = minecraft.gameRenderer.getMainCamera();
            if (!camera.isInitialized()) {
                return null;
            }
            var cameraPos = camera.getPosition();
            if (!GunLodDistance.useItemLod(limit, cameraPos.x, cameraPos.y, cameraPos.z,
                    entity.getX(), entity.getY(), entity.getZ())) {
                return null;
            }
        }
        return TACZCompat.getGunLod(stack);
    }

    protected void renderArmWithItem(LivingEntity livingEntity, ItemStack itemStack, ItemDisplayContext displayContext, HumanoidArm arm, GeoRenderData data, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        if (!itemStack.isEmpty()) {
            boolean isLeftHand = arm == HumanoidArm.LEFT;
            var locator = isLeftHand ? PlayerLocator.get().leftHand : PlayerLocator.get().rightHand;

            data.modelState.visitLocatorGroup(locator, poseStack, locatorPos -> {
                // 渲染默认手部物品
                locatorPos.translate(0, -0.0625, -0.1);
                locatorPos.mulPose(Axis.XP.rotationDegrees(-90.0F));
                // 卓越前线的枪械需要缩放一下，不然太小了；手雷不是枪，不套用该缩放。
                if (SWarfareCompat.isGun(itemStack) && !TACZCompat.isGrenade(itemStack)
                        && !SWarfareCompat.isGrenade(itemStack)) {
                    locatorPos.translate(0.1, 0, 0);
                    locatorPos.scale(1.25f, 1.25f, 1.25f);
                }
                this.itemInHandRenderer.renderItem(livingEntity, itemStack, displayContext, isLeftHand, locatorPos, bufferSource, packedLight);
            });
        }
    }
}
