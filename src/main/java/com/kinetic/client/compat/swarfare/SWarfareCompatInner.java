package com.kinetic.client.compat.swarfare;

import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.event.ClientEventHandler;
import com.atsuishio.superbwarfare.item.LungeMine;
import com.atsuishio.superbwarfare.item.gun.GunItem;
import com.kinetic.client.compat.parcool.ParCoolCompat;
import com.kinetic.client.entity.CustomHumanoidEntity;
import com.kinetic.client.model.locator.PlayerLocator;
import com.kinetic.geckolib3.core.PlayState;
import com.kinetic.geckolib3.core.builder.LoopType;
import com.kinetic.geckolib3.core.event.predicate.AnimationEvent;
import com.kinetic.geckolib3.geo.GeoRenderData;
import com.kinetic.init.ModItemTags;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

@SuppressWarnings("removal")
public class SWarfareCompatInner {
    private static final TagKey<Item> PISTOL = TagKey.create(Registries.ITEM, new ResourceLocation("superbwarfare:animated/pistol"));
    private static final TagKey<Item> RPG = TagKey.create(Registries.ITEM, new ResourceLocation("superbwarfare:animated/rpg"));

    static boolean isGun(ItemStack stack) {
        return stack.getItem() instanceof GunItem;
    }

    /** 手雷类物品（标签由 data/kinetic/tags/items/grenade.json 维护）。 */
    static boolean isGrenade(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ModItemTags.GRENADE);
    }

    /** 近战武器按原版剑处理，不走走火控枪械姿势。 */
    static boolean isMeleeWeapon(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ModItemTags.SWORDS);
    }

    static boolean shouldHidePlayerRender(Player player) {
        if (player.getVehicle() instanceof VehicleEntity vehicle) {
            return vehicle.hidePassenger(player);
        }
        return false;
    }

    static void renderOffhandGun(ItemStack heldItem, GeoRenderData data, LivingEntity player, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        ItemRenderer renderer = Minecraft.getInstance().getItemRenderer();
        if (heldItem.is(PISTOL)) {
            data.modelState.visitLocatorGroup(PlayerLocator.get().pistol, poseStack, locatorPose -> {
                locatorPose.translate(0, -0.125, 0);
                locatorPose.scale(0.65f, 0.65f, 0.65f);
                locatorPose.mulPose(Axis.YP.rotationDegrees(90));
                locatorPose.mulPose(Axis.ZP.rotationDegrees(-90.0F));
                renderer.renderStatic(heldItem, ItemDisplayContext.FIXED, packedLight, OverlayTexture.NO_OVERLAY, locatorPose, buffer, player.level(), player.getId());
            });
        }
        if (!heldItem.is(PISTOL)) {
            data.modelState.visitLocatorGroup(PlayerLocator.get().rifle, poseStack, locatorPose -> {
                locatorPose.scale(0.65f, 0.65f, 0.65f);
                locatorPose.mulPose(Axis.YP.rotationDegrees(-180.0F));
                renderer.renderStatic(heldItem, ItemDisplayContext.FIXED, packedLight, OverlayTexture.NO_OVERLAY, locatorPose, buffer, player.level(), player.getId());
            });
        }
    }

    @Nullable
    static PlayState playLungeMineAnimation(AnimationEvent<? extends CustomHumanoidEntity<? extends LivingEntity>> event) {
        LivingEntity livingEntity = event.getAnimatableEntity().getEntity();
        if (!Objects.equals(Minecraft.getInstance().player, livingEntity)) {
            return null;
        }
        if (!(livingEntity.getMainHandItem().getItem() instanceof LungeMine)) {
            return null;
        }
        if (ClientEventHandler.lungeSprint > 0) {
            return playAnimation(event, "superbwarfare:lunge_mine_sprint");
        } else if (ClientEventHandler.lungeDraw > 0) {
            return playAnimation(event, "superbwarfare:lunge_mine_draw");
        } else if (ClientEventHandler.lungeAttack > 0) {
            return playAnimation(event, "superbwarfare:lunge_mine_fire");
        } else if (livingEntity.isSprinting() && livingEntity.onGround() && ClientEventHandler.lungeDraw == 0) {
            return playAnimation(event, "superbwarfare:lunge_mine_run");
        } else {
            return playAnimation(event, "superbwarfare:lunge_mine_idle");
        }
    }

    /**
     * tac:idle
     * tac:run
     * tac:walk
     */

    /** 基础移动动画属于 KINETIC 自己的运动层：武器姿态只能覆盖上半身，不能整体替换它们。 */
    private static boolean isBaseLocomotion(String animationName) {
        return "idle".equals(animationName) || "walk".equals(animationName)
                || "run".equals(animationName) || "sneak".equals(animationName)
                || "sneaking".equals(animationName);
    }
    static PlayState playGunMainAnimation(AnimationEvent<? extends CustomHumanoidEntity<? extends LivingEntity>> event, String animationName, LoopType loopType) {
                // 跑步/行走/待机/潜行由 KINETIC 的运动动画驱动（腿、躯干、头）。武器联动只能把持械姿势
        // 放在 hold 层（上半身），不能在这里整体替换基础动画——否则腿部跑动会被换成静态持械姿势，
        // 躯干也会僵直，滑铲/趴下同样会被打断。返回 null 表示"不接管"，由 main 控制器播原动画。
        if (isBaseLocomotion(animationName)) {
            return null;
        }
        String tacName = "tac:" + animationName;
        var playerAnimation = event.getAnimatableEntity().getAnimation(tacName);
        if (playerAnimation != null) {
            return playAnimation(event, tacName, loopType);
        }
        return playAnimation(event, animationName, loopType);
    }

    /** 滑铲锁定期间的静止持枪姿势：固定取 {@code tac:hold:<type>}。 */
    @NotNull
    static PlayState playGunStaticHoldAnimation(AnimationEvent<? extends CustomHumanoidEntity<? extends LivingEntity>> event,
                                                ItemStack heldItem) {
        if (!(heldItem.getItem() instanceof GunItem)) {
            return PlayState.STOP;
        }
        return getGunTypeAnimation(event, heldItem, "tac:hold:", LoopType.LOOP);
    }

    @NotNull
    private static PlayState getGunTypeAnimation(AnimationEvent<? extends CustomHumanoidEntity<? extends LivingEntity>> event, ItemStack gun, String prefix) {
        return getGunTypeAnimation(event, gun, prefix, LoopType.LOOP);
    }

    /**
     * tac:hold:pistol
     * tac:aim:pistol
     * tac:reload:pistol
     * tac:aim_shoot:pistol
     * tac:hold_shoot:pistol
     * tac:run:pistol
     */
    static PlayState playGunHoldAnimation(AnimationEvent<? extends CustomHumanoidEntity<? extends LivingEntity>> event, ItemStack heldItem) {
        // 先检查刺雷
        PlayState playState = playLungeMineAnimation(event);
        if (playState != null) {
            return playState;
        }
        // 近战武器按原版剑处理。
        if (isMeleeWeapon(heldItem)) {
            return null;
        }
        // 手雷：持有交给模型自带的 hold_mainhand:throwable_potion；蓄力期间播模型自带的抬雷/待投姿势。
        if (isGrenade(heldItem)) {
            if (event.getAnimatableEntity().getEntity().isUsingItem()) {
                return playAnimation(event, "tac:mainhand:grenade", LoopType.HOLD_ON_LAST_FRAME);
            }
            return null;
        }
        // 再检查枪械
        if (!(heldItem.getItem() instanceof GunItem)) {
            return null;
        }
        LivingEntity livingEntity = event.getAnimatableEntity().getEntity();

        if (!livingEntity.isSwimming() && livingEntity.getPose() == Pose.SWIMMING) {
            // 跑酷滑铲优先时，腿部交给跑酷滑铲动画，上半身继续持枪，形成自然的滑铲持枪。
            if (livingEntity instanceof Player player && ParCoolCompat.isSliding(player)) {
                return Math.abs(event.getLimbSwingAmount()) > 0.05
                        ? getGunTypeAnimation(event, heldItem, "tac:run:")
                        : getGunTypeAnimation(event, heldItem, "tac:hold:");
            }
            if (Math.abs(event.getLimbSwingAmount()) > 0.05) {
                return getGunTypeAnimation(event, heldItem, "tac:climb:");
            } else {
                return getGunTypeAnimation(event, heldItem, "tac:climbing:");
            }
        }

        // zoomTime 是玩家客户端数据，需要额外判断是否是当前玩家
        double aimProgress = ClientEventHandler.zoomTime;
        if (!Objects.equals(Minecraft.getInstance().player, livingEntity)) {
            aimProgress = 0;
        }
        if (aimProgress > 0.3) {
            return getGunTypeAnimation(event, heldItem, "tac:aim:");
        } else {
            if (livingEntity.onGround() && livingEntity.isSprinting()) {
                return getGunTypeAnimation(event, heldItem, "tac:run:");
            }
            return getGunTypeAnimation(event, heldItem, "tac:hold:");
        }
    }

    /**
     * 这些动画可能是带有后摇的动画，故需要单独分一个频道来播放，从而才能超过时长进行播放
     */
    static PlayState playGunOnceAnimation(AnimationEvent<? extends CustomHumanoidEntity<? extends LivingEntity>> event, ItemStack heldItem) {
        // 手雷与近战武器不使用枪械的开火/换弹/近战频道。
        if (isGrenade(heldItem) || isMeleeWeapon(heldItem)) {
            return null;
        }
        if (!(heldItem.getItem() instanceof GunItem gunItem)) {
            return PlayState.STOP;
        }
        LivingEntity livingEntity = event.getAnimatableEntity().getEntity();
        GunData gunData = GunData.from(heldItem);
        if (!Objects.equals(Minecraft.getInstance().player, livingEntity)) {
            return PlayState.STOP;
        }

        // 重置动画
        if (event.getCodedController() != null && event.getCodedController().isAnimFinished()) {
            event.getCodedController().indicateReload();
        }

        if (gunData.reloading() && gunData.reload.time() > 40) {
            return getGunTypeAnimation(event, heldItem, "tac:reload:", LoopType.PLAY_ONCE);
        }

        int meleeTime = ClientEventHandler.gunMelee;
        if (meleeTime > 5) {
            return getGunTypeAnimation(event, heldItem, "tac:melee:", LoopType.PLAY_ONCE);
        }

        double fireTick = ClientEventHandler.fireRotTimer;
        if (0 < fireTick && fireTick < 1) {
            double aimProgress = ClientEventHandler.zoomTime;
            // 只要处于趴/爬姿态就必须用匍匐开火动作：之前额外要求 limbSwing <= 0.05，
            // 于是"爬行中开火"会落到站姿的 tac:hold:fire，头被拧向后下方（往地上打）。
            boolean isClimbing = !livingEntity.isSwimming() && livingEntity.getPose() == Pose.SWIMMING;

            // 滑铲时开火必须留在滑铲姿态：tac:climbing:fire:* 是整身趴地动作，会把滑铲顶掉。
            if (isClimbing && !(livingEntity instanceof Player sliding && ParCoolCompat.isSliding(sliding))) {
                return getGunTypeAnimation(event, heldItem, "tac:climbing:fire:", LoopType.PLAY_ONCE);
            }
            if (aimProgress > 0.3) {
                return getGunTypeAnimation(event, heldItem, "tac:aim:fire:", LoopType.PLAY_ONCE);
            } else {
                return getGunTypeAnimation(event, heldItem, "tac:hold:fire:", LoopType.PLAY_ONCE);
            }
        }
        return PlayState.CONTINUE;
    }

    @NotNull
    private static PlayState getGunTypeAnimation(AnimationEvent<? extends CustomHumanoidEntity<? extends LivingEntity>> event,
                                                 ItemStack gun, String prefix, LoopType loopType) {
        var gunCondition = event.getAnimatableEntity().getConditionManager().getTAC();
        if (gunCondition != null) {
            ItemStack stack = event.getAnimatableEntity().getEntity().getMainHandItem();
            String name = gunCondition.doTest(stack, prefix);
            if (StringUtils.isNoneBlank(name)) {
                return playAnimation(event, name, loopType);
            }
        }
        if (gun.is(PISTOL)) {
            return playAnimation(event, prefix + "pistol", loopType);
        }
        if (gun.is(RPG)) {
            return playAnimation(event, prefix + "rpg", loopType);
        }
        return playAnimation(event, prefix + "rifle", loopType);
    }

    @NotNull
    private static PlayState playAnimation(AnimationEvent<?> event, String animationName, LoopType loopType) {
        event.getCodedController().setAnimation(animationName, loopType);
        return PlayState.CONTINUE;
    }

    @NotNull
    private static PlayState playAnimation(AnimationEvent<?> event, String animationName) {
        event.getCodedController().setAnimation(animationName);
        return PlayState.CONTINUE;
    }
}
