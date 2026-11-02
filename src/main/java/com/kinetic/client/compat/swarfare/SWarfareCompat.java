package com.kinetic.client.compat.swarfare;

import com.kinetic.client.entity.CustomHumanoidEntity;
import com.kinetic.geckolib3.core.PlayState;
import com.kinetic.geckolib3.core.builder.LoopType;
import com.kinetic.geckolib3.core.event.predicate.AnimationEvent;
import com.kinetic.geckolib3.geo.GeoRenderData;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.loading.LoadingModList;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.jetbrains.annotations.Nullable;

public class SWarfareCompat {
    private static final String MOD_ID = "superbwarfare";
    private static boolean INSTALLED = false;

    public static void init() {
        ModFileInfo modFileById = LoadingModList.get().getModFileById(MOD_ID);
        if (modFileById != null) {
            DefaultArtifactVersion modVersion = new DefaultArtifactVersion(modFileById.versionString());
            INSTALLED = modVersion.compareTo(new DefaultArtifactVersion("0.8.7.1")) >= 0;
        }
        if (INSTALLED) {
            MinecraftForge.EVENT_BUS.register(new ReplacePlayerArmRender());
        }
    }

    /** 该物品是否属于手雷标签（与模组是否安装无关，只看标签）。 */
    public static boolean isGrenade(ItemStack stack) {
        return SWarfareCompatInner.isGrenade(stack);
    }

    public static boolean isGun(ItemStack stack) {
        if (INSTALLED) {
            return SWarfareCompatInner.isGun(stack);
        }
        return false;
    }

    public static boolean shouldHidePlayerRender(Player player) {
        if (INSTALLED) {
            return SWarfareCompatInner.shouldHidePlayerRender(player);
        }
        return false;
    }

    public static void renderOffsetHand(ItemStack offhandItem, GeoRenderData data, LivingEntity livingEntity, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        if (INSTALLED && SWarfareCompatInner.isGun(offhandItem)) {
            poseStack.pushPose();
            SWarfareCompatInner.renderOffhandGun(offhandItem, data, livingEntity, poseStack, buffer, packedLight);
            poseStack.popPose();
        }
    }

    @Nullable
    public static PlayState playGunMainAnimation(LivingEntity livingEntity,
                                                 AnimationEvent<? extends CustomHumanoidEntity<? extends LivingEntity>> event,
                                                 String animationName, LoopType loopType) {
        if (INSTALLED && SWarfareCompatInner.isGun(livingEntity.getMainHandItem())) {
            return SWarfareCompatInner.playGunMainAnimation(event, animationName, loopType);
        }
        return null;
    }

    @Nullable
    public static PlayState playGunHoldAnimation(ItemStack mainHandItem,
                                                 AnimationEvent<? extends CustomHumanoidEntity<? extends LivingEntity>> event) {
        if (INSTALLED) {
            return SWarfareCompatInner.playGunHoldAnimation(event, mainHandItem);
        }
        return null;
    }

    /** 滑铲锁定期间的静止持枪姿势（卓越前线与 TaCZ 共用同一套 tac:* 动画名）。 */
    @Nullable
    public static PlayState playGunStaticHoldAnimation(ItemStack mainHandItem,
                                                       AnimationEvent<? extends CustomHumanoidEntity<? extends LivingEntity>> event) {
        if (INSTALLED && SWarfareCompatInner.isGun(mainHandItem)) {
            return SWarfareCompatInner.playGunStaticHoldAnimation(event, mainHandItem);
        }
        return null;
    }

    @Nullable
    public static PlayState playGunOnceAnimation(ItemStack mainHandItem, AnimationEvent<? extends CustomHumanoidEntity<? extends LivingEntity>> event) {
        if (INSTALLED && SWarfareCompatInner.isGun(mainHandItem)) {
            return SWarfareCompatInner.playGunOnceAnimation(event, mainHandItem);
        }
        return null;
    }

    @Nullable
    public static ResourceLocation getGunId(ItemStack stack) {
        if (INSTALLED) {
            return ForgeRegistries.ITEMS.getKey(stack.getItem());
        }
        return null;
    }

    /**
     * 主手枪械此刻是否正在换弹；未安装或没在换弹时返回 false。
     *
     * <p>与 {@code SWarfareCompatInner.playGunOnceAnimation} 用同一个信号
     * （{@code gunData.reloading() && reload.time() > 40}），供匍匐侧躺维持换弹姿态。
     */
    public static boolean isReloading(net.minecraft.world.entity.LivingEntity entity) {
        if (!INSTALLED || entity == null) {
            return false;
        }
        try {
            return SWarfareCompatInner.isReloading(entity);
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    public static boolean isInstalled() {
        return INSTALLED;
    }
}
