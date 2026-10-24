package com.kinetic.client.compat.tacz;

import com.kinetic.client.animation.condition.ConditionTAC;
import com.kinetic.client.compat.parcool.ParCoolCompat;
import com.kinetic.client.entity.CustomHumanoidEntity;
import com.kinetic.client.model.locator.PlayerLocator;
import com.kinetic.init.ModItemTags;
import com.kinetic.geckolib3.core.PlayState;
import com.kinetic.geckolib3.core.builder.LoopType;
import com.kinetic.geckolib3.core.event.predicate.AnimationEvent;
import com.kinetic.geckolib3.geo.GeoRenderData;
import com.kinetic.geckolib3.model.AnimatableEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.item.GunTabType;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.client.model.functional.MuzzleFlashRender;
import com.tacz.guns.client.model.functional.ShellRender;
import com.tacz.guns.resource.index.CommonGunIndex;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

class TacCompatInner {
    /**
     * 手枪判定缓存。{@code CustomPlayerItemInHandLayer} 每个渲染帧、每个玩家都会问一次，
     * 原先每次都要查一遍枪包索引（{@code TimelessAPI.getCommonGunIndex}）。
     */
    private static final GunTypeCache GUN_TYPE_CACHE = new GunTypeCache();

    static boolean isGun(ItemStack itemStack) {
        return itemStack.getItem() instanceof IGun;
    }

    static void registerEvent() {
        MinecraftForge.EVENT_BUS.register(new TacEvent());
    }

    /** 手雷类物品（标签由 data/kinetic/tags/items/grenade.json 维护）。 */
    static boolean isGrenade(ItemStack itemStack) {
        return !itemStack.isEmpty() && itemStack.is(ModItemTags.GRENADE);
    }

    /**
     * 近战武器按原版剑处理：不走走火控枪械的持械/开火/近战频道，
     * 由 sword 分类提供挥砍（swing:sword）与持械姿势（hold_mainhand:sword）。
     */
    static boolean isMeleeWeapon(ItemStack itemStack) {
        return !itemStack.isEmpty() && itemStack.is(ModItemTags.SWORDS);
    }

    static void renderOffhandGun(ItemStack heldItem, GeoRenderData data, LivingEntity player, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        IGun gun = IGun.getIGunOrNull(heldItem);
        if (gun == null) {
            return;
        }

        TimelessAPI.getCommonGunIndex(gun.getGunId(heldItem)).ifPresent(index -> {
            String weaponType = index.getType();
            ItemRenderer renderer = Minecraft.getInstance().getItemRenderer();
            if (isType(weaponType, GunTabType.PISTOL)) {
                data.modelState.visitLocatorGroup(PlayerLocator.get().pistol, poseStack, locatorPose -> {
                    locatorPose.translate(0, -0.125, 0);
                    locatorPose.scale(0.65f, 0.65f, 0.65f);
                    locatorPose.mulPose(Axis.YP.rotationDegrees(-90.0F));
                    locatorPose.mulPose(Axis.ZP.rotationDegrees(90.0F));
                    renderer.renderStatic(heldItem, ItemDisplayContext.FIXED, packedLight, OverlayTexture.NO_OVERLAY, locatorPose, buffer, player.level(), player.getId());
                });
            }
            if (!isType(weaponType, GunTabType.PISTOL)) {
                data.modelState.visitLocatorGroup(PlayerLocator.get().rifle, poseStack, locatorPose -> {
                    locatorPose.scale(0.65f, 0.65f, 0.65f);
                    locatorPose.mulPose(Axis.YP.rotationDegrees(-180.0F));
                    renderer.renderStatic(heldItem, ItemDisplayContext.FIXED, packedLight, OverlayTexture.NO_OVERLAY, locatorPose, buffer, player.level(), player.getId());
                });
            }
        });
    }

    /**
     * 手雷的持有 / 蓄力姿势。
     *
     * <p>{@code tac:mainhand:grenade} 的前 0.75 秒是拔插销并把雷抬到待投位置，之后停在待投姿势，
     * 因此用 HOLD_ON_LAST_FRAME 播一次并保持：既覆盖"持有 → 蓄力"两个阶段，又不会每隔一个循环
     * 重播一次拔插销动作。投出由 swing 频道的投掷动画负责。
     */
    static PlayState playGrenadeAnimation(AnimationEvent<? extends AnimatableEntity<? extends LivingEntity>> event, InteractionHand hand) {
        String name = hand == InteractionHand.MAIN_HAND ? "tac:mainhand:grenade" : "tac:offhand:grenade";
        return playAnimation(event, name, LoopType.HOLD_ON_LAST_FRAME);
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
    static PlayState playGunMainAnimation(AnimationEvent<? extends AnimatableEntity<? extends LivingEntity>> event, String animationName, LoopType loopType) {
                // 跑步/行走/待机/潜行由 KINETIC 的运动动画驱动（腿、躯干、头）。武器联动只能把持械姿势
        // 放在 hold 层（上半身），不能在这里整体替换基础动画——否则腿部跑动会被换成静态持械姿势，
        // 躯干也会僵直，滑铲/趴下同样会被打断。返回 null 表示"不接管"，由 main 控制器播原动画。
        if (isBaseLocomotion(animationName)) {
            return null;
        }
        String tacName = "tac:" + animationName;
        if (event.getAnimatableEntity().getAnimation(tacName) != null) {
            return playAnimation(event, tacName, loopType);
        }
        return playAnimation(event, animationName, loopType);
    }

    /**
     * tac:hold:pistol
     * tac:aim:pistol
     * tac:reload:pistol
     * tac:aim_shoot:pistol
     * tac:hold_shoot:pistol
     * tac:run:pistol
     */
    static PlayState playGunHoldAnimation(AnimationEvent<? extends CustomHumanoidEntity<?>> event, ItemStack heldItem) {
        // 近战武器按原版剑处理（hold_mainhand:sword），返回 null 表示"本联动不接管"。
        if (isMeleeWeapon(heldItem)) {
            return null;
        }
        // 手雷分三段，全部使用模型自带动画：
        //   持有 —— 不接管，交给模型按 throwable_potion 分类播 hold_mainhand:throwable_potion；
        //   蓄力 —— 右键蓄力期间播 tac:mainhand:grenade（拔插销并停在待投姿势）；
        //   投出 —— 由 swing 频道播 swing:throwable_potion。
        if (isGrenade(heldItem)) {
            if (event.getAnimatableEntity().getEntity().isUsingItem()) {
                return playGrenadeAnimation(event, InteractionHand.MAIN_HAND);
            }
            return null;
        }
        IGun gun = IGun.getIGunOrNull(heldItem);
        if (gun == null) {
            return PlayState.STOP;
        }
        Optional<CommonGunIndex> indexOptional = TimelessAPI.getCommonGunIndex(gun.getGunId(heldItem));
        if (indexOptional.isEmpty()) {
            return PlayState.STOP;
        }
        CommonGunIndex gunIndex = indexOptional.get();
        String weaponType = gunIndex.getType();
        LivingEntity livingEntity = event.getAnimatableEntity().getEntity();
        IGunOperator operator = IGunOperator.fromLivingEntity(livingEntity);

        if (!livingEntity.isSwimming() && livingEntity.getPose() == Pose.SWIMMING) {
            // 这里对应 “爬行” 姿态：Minecraft 的 Pose.SWIMMING 同时被游泳与爬行/滑铲复用。
            // Parcool 滑铲也把玩家置为这个姿态，如果直接返回 tac:climb:*，持枪滑铲时会错误地
            // 显示 TACZ 爬行动作。因此按服务端动画优先级裁决：跑酷滑铲优先时不接管该姿态。
            if (livingEntity instanceof Player player && ParCoolCompat.isSliding(player)) {
                // 跑酷滑铲优先：腿部交给跑酷滑铲动画，上半身继续持枪，形成自然的滑铲持枪。
                // tac:hold:* / tac:run:* 只写上半身，因此不会覆盖滑铲的下肢动作。
                return Math.abs(event.getLimbSwingAmount()) > 0.05
                        ? getGunTypeAnimation(event, weaponType, "tac:run:")
                        : getGunTypeAnimation(event, weaponType, "tac:hold:");
            }
            if (Math.abs(event.getLimbSwingAmount()) > 0.05) {
                return getGunTypeAnimation(event, weaponType, "tac:climb:");
            } else {
                return getGunTypeAnimation(event, weaponType, "tac:climbing:");
            }
        }

        float aimProgress = operator.getSynAimingProgress();
        if (aimProgress > 0) {
            return getGunTypeAnimation(event, weaponType, "tac:aim:");
        } else {
            if (livingEntity.onGround() && livingEntity.isSprinting()) {
                return getGunTypeAnimation(event, weaponType, "tac:run:");
            }
            return getGunTypeAnimation(event, weaponType, "tac:hold:");
        }
    }

    /**
     * 滑铲锁定期间的静止持枪姿势：固定取 {@code tac:hold:<type>}，不看瞄准/跑动/开火。
     *
     * <p>只负责手臂和头；躯干/下肢由 {@code AnimationProcessor} 的滑铲回写覆盖，所以
     * {@code tac:hold:rifle} 里的 {@code AllBody} 轨道不会把滑铲姿势拧掉。
     */
    @NotNull
    static PlayState playGunStaticHoldAnimation(AnimationEvent<? extends CustomHumanoidEntity<?>> event, ItemStack heldItem) {
        IGun gun = IGun.getIGunOrNull(heldItem);
        if (gun == null) {
            return PlayState.STOP;
        }
        Optional<CommonGunIndex> indexOptional = TimelessAPI.getCommonGunIndex(gun.getGunId(heldItem));
        if (indexOptional.isEmpty()) {
            return PlayState.STOP;
        }
        return getGunTypeAnimation(event, indexOptional.get().getType(), "tac:hold:");
    }

    /**
     * 这些动画可能是带有后摇的动画，故需要单独分一个频道来播放，从而才能超过时长进行播放
     */
    static PlayState playGunOnceAnimation(AnimationEvent<? extends CustomHumanoidEntity<?>> event, ItemStack heldItem) {
        // 手雷与近战武器不使用枪械的开火/换弹/近战频道。
        if (isGrenade(heldItem) || isMeleeWeapon(heldItem)) {
            return null;
        }
        IGun gun = IGun.getIGunOrNull(heldItem);
        if (gun == null) {
            return PlayState.STOP;
        }
        Optional<CommonGunIndex> indexOptional = TimelessAPI.getCommonGunIndex(gun.getGunId(heldItem));
        if (indexOptional.isEmpty()) {
            return PlayState.STOP;
        }

        CommonGunIndex gunIndex = indexOptional.get();
        String weaponType = gunIndex.getType();
        LivingEntity livingEntity = event.getAnimatableEntity().getEntity();
        IGunOperator operator = IGunOperator.fromLivingEntity(livingEntity);
        long fireTick = operator.getSynShootCoolDown();

        if (livingEntity instanceof IClientPlayerGunOperator clientOperator) {
            fireTick = Math.max(fireTick, clientOperator.getClientShootCoolDown());
        }

        if (event.getAnimatableEntity().isTacGunAnimationNeedReload()) {
            playLoopAnimation(event, "empty");
        }
        event.getAnimatableEntity().setTacGunAnimationNeedReload(false);

        float reloadProgress = operator.getSynReloadState().getCountDown();
        if (reloadProgress > 0) {
            return getGunTypeAnimation(event, weaponType, "tac:reload:", LoopType.PLAY_ONCE);
        }

        long synMeleeCoolDown = operator.getSynMeleeCoolDown();
        if (synMeleeCoolDown > 0) {
            return getGunTypeAnimation(event, weaponType, "tac:melee:", LoopType.PLAY_ONCE);
        }

        if (fireTick > 0) {
            float aimProgress = operator.getSynAimingProgress();
            // 只要处于趴/爬姿态就必须用匍匐开火动作：之前额外要求 limbSwing <= 0.05，
            // 于是"爬行中开火"会落到站姿的 tac:hold:fire，头被拧向后下方（往地上打）。
            boolean isClimbing = !livingEntity.isSwimming() && livingEntity.getPose() == Pose.SWIMMING;

            // 滑铲时开火必须留在滑铲姿态：tac:climbing:fire:* 是整身趴地动作（含 Root 与双腿），
            // 会把跑酷滑铲顶掉、露出半身埋进地里的爬行姿势。这里与 hold 频道用同一个判断，
            // 让下肢继续由滑铲负责，开火只驱动上半身。
            if (isClimbing && !(livingEntity instanceof Player sliding && ParCoolCompat.isSliding(sliding))) {
                return getGunTypeAnimation(event, weaponType, "tac:climbing:fire:", LoopType.PLAY_ONCE);
            }
            if (aimProgress > 0) {
                return getGunTypeAnimation(event, weaponType, "tac:aim:fire:", LoopType.PLAY_ONCE);
            } else {
                return getGunTypeAnimation(event, weaponType, "tac:hold:fire:", LoopType.PLAY_ONCE);
            }
        }
        return PlayState.CONTINUE;
    }

    static void openFlashShellRender(LivingEntity livingEntity) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (livingEntity.equals(player)) {
            MuzzleFlashRender.isSelf = true;
            ShellRender.isSelf = true;
        }
    }

    static void stopFlashShellRender() {
        MuzzleFlashRender.isSelf = false;
        ShellRender.isSelf = false;
    }

    /**
     * 渲染线程上的枪 id 单槽环形记忆。
     *
     * <p>{@code IGun.getGunId} 每次调用都要从 NBT 里取一次字符串再 {@code ResourceLocation.tryParse}：
     * 一条枪的同一帧里会被问 2~3 次（副手判定、动画频道、手枪 locator），而 {@code ResourceLocation}
     * 每次都是新实例。除了分配压力，新实例还会让任何按身份比较的集合失效。
     *
     * <p>只按 {@link ItemStack} <b>实例身份</b>命中：枪的 NBT 一变就是新的 stack，天然失效。
     * 固定 8 槽、环形覆盖，无分配、无清理时机依赖，只在客户端渲染线程使用。
     */
    private static final ItemStack[] GUN_ID_STACKS = new ItemStack[8];
    private static final ResourceLocation[] GUN_ID_VALUES = new ResourceLocation[8];
    private static int gunIdCursor;

    @Nullable
    static ResourceLocation getGunId(ItemStack itemInHand) {
        if (itemInHand == null || itemInHand.isEmpty()) {
            return null;
        }
        for (var i = 0; i < GUN_ID_STACKS.length; i++) {
            if (GUN_ID_STACKS[i] == itemInHand) {
                return GUN_ID_VALUES[i];
            }
        }
        IGun iGun = IGun.getIGunOrNull(itemInHand);
        if (iGun == null) {
            return null;
        }
        var gunId = iGun.getGunId(itemInHand);
        var slot = gunIdCursor++ & (GUN_ID_STACKS.length - 1);
        GUN_ID_STACKS[slot] = itemInHand;
        GUN_ID_VALUES[slot] = gunId;
        return gunId;
    }

    /** 资源重载时丢掉环形记忆：枪包换了之后旧 id 不再成立。 */
    static void clearGunIdMemo() {
        java.util.Arrays.fill(GUN_ID_STACKS, null);
        java.util.Arrays.fill(GUN_ID_VALUES, null);
        gunIdCursor = 0;
    }

    /** 资源重载时清掉按枪 id 的判定缓存与环形记忆：枪包换了之后旧结果不再成立。 */
    static void clearCaches() {
        GUN_TYPE_CACHE.clear();
        clearGunIdMemo();
    }

    /** 该枪是不是手枪（决定副手挂在哪个 locator 上）。判定按 gunId 缓存，见 {@link GunTypeCache}。 */
    static boolean isPistol(ItemStack heldItem) {
        IGun gun = IGun.getIGunOrNull(heldItem);
        if (gun == null) {
            return false;
        }
        var gunId = gun.getGunId(heldItem);
        if (gunId == null) {
            // 缓存以 gunId 为键；TaCZ 自身保证非 null，这里只做防御。
            return false;
        }
        return GUN_TYPE_CACHE.isPistol(gunId, id -> TimelessAPI.getCommonGunIndex(id)
                .map(index -> isType(index.getType(), GunTabType.PISTOL))
                .orElse(false));
    }

    @NotNull
    private static PlayState getGunTypeAnimation(AnimationEvent<? extends CustomHumanoidEntity<?>> event, String weaponType, String prefix) {
        return getGunTypeAnimation(event, weaponType, prefix, LoopType.LOOP);
    }

    @NotNull
    private static PlayState getGunTypeAnimation(AnimationEvent<? extends CustomHumanoidEntity<?>> event, String weaponType, String prefix, LoopType loopType) {
        ConditionTAC conditionTAC = event.getAnimatableEntity().getConditionManager().getTAC();
        if (conditionTAC != null) {
            ItemStack stack = event.getAnimatableEntity().getEntity().getMainHandItem();
            String name = conditionTAC.doTest(stack, prefix);
            if (StringUtils.isNoneBlank(name)) {
                return playAnimation(event, name, loopType);
            }
        }
        if (isType(weaponType, GunTabType.PISTOL)) {
            return playAnimation(event, prefix + "pistol", loopType);
        }
        if (isType(weaponType, GunTabType.RPG)) {
            return playAnimation(event, prefix + "rpg", loopType);
        }
        return playAnimation(event, prefix + "rifle", loopType);
    }

    @NotNull
    private static PlayState playLoopAnimation(AnimationEvent<?> event, String animationName) {
        return playAnimation(event, animationName, LoopType.LOOP);
    }

    @NotNull
    private static PlayState playAnimation(AnimationEvent<?> event, String animationName, LoopType loopType) {
        event.getCodedController().setAnimation(animationName, loopType);
        return PlayState.CONTINUE;
    }

    private static boolean isType(String type, GunTabType tabType) {
        // TaCZ 的 type 是小写字符串，GunTabType 的枚举名是大写；用 equalsIgnoreCase 既等价于原来的
        // toLowerCase 比较，又不会每次调用都新建一个 String。
        return type.equalsIgnoreCase(tabType.name());
    }
}
