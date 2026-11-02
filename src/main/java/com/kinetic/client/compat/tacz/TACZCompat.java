package com.kinetic.client.compat.tacz;

import com.kinetic.client.animation.molang.CtrlBinding;
import com.kinetic.client.entity.CustomHumanoidEntity;
import com.kinetic.geckolib3.core.PlayState;
import com.kinetic.geckolib3.core.builder.LoopType;
import com.kinetic.geckolib3.core.event.predicate.AnimationEvent;
import com.kinetic.geckolib3.geo.GeoRenderData;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;


public class TACZCompat {
    private static final String MOD_ID = "tacz";
    private static boolean INSTALLED = false;

    public static void init() {
        INSTALLED = ModList.get().isLoaded(MOD_ID);
        if (INSTALLED) {
            TacCompatInner.registerEvent();
        }
    }

    public static void addBinding(CtrlBinding ctrlBinding) {
        if (isInstalled()) {
            TacCtrlBinding.addInnerBinding(ctrlBinding);
        } else {
            addEmptyBinding(ctrlBinding);
        }
    }

    /**
     * 没有安装此模组时，这些 molang 应该存在，否则会报错
     */
    private static void addEmptyBinding(CtrlBinding binding) {
        binding.livingEntityVar("tac_hold_gun", ctx -> false);
        binding.livingEntityVar("tac_gun_type", ctx -> StringUtils.EMPTY);
        binding.livingEntityVar("tac_gun_id", ctx -> StringUtils.EMPTY);
        binding.livingEntityVar("tac_is_fire", ctx -> false);
        binding.livingEntityVar("tac_is_aim", ctx -> false);
        binding.livingEntityVar("tac_is_reload", ctx -> false);
        binding.livingEntityVar("tac_is_melee", ctx -> false);
        binding.livingEntityVar("tac_is_draw", ctx -> false);
        binding.livingEntityVar("tac_fire_mode", ctx -> StringUtils.EMPTY);
    }

    public static void renderOffsetHand(ItemStack offhandItem, GeoRenderData data, LivingEntity livingEntity, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        if (isInstalled() && TacCompatInner.isGun(offhandItem)) {
            poseStack.pushPose();
            TacCompatInner.renderOffhandGun(offhandItem, data, livingEntity, poseStack, buffer, packedLight);
            poseStack.popPose();
        }
    }

    @Nullable
    public static PlayState playGunMainAnimation(LivingEntity livingEntity, AnimationEvent<? extends CustomHumanoidEntity<?>> event, String animationName, LoopType loopType) {
        if (isInstalled() && TacCompatInner.isGun(livingEntity.getMainHandItem())) {
            return TacCompatInner.playGunMainAnimation(event, animationName, loopType);
        }
        return null;
    }

    @Nullable
    public static PlayState playGunHoldAnimation(ItemStack mainHandItem, AnimationEvent<? extends CustomHumanoidEntity<?>> event) {
        // 手雷不是 IGun，但同一个 hold 频道要负责它的抬雷/待投姿势，因此这里必须一起放行，
        // 否则 TacCompatInner 里的手雷分支永远不可达（蓄力时只剩 use 频道的 STOP）。
        if (isInstalled() && (TacCompatInner.isGun(mainHandItem) || TacCompatInner.isGrenade(mainHandItem))) {
            return TacCompatInner.playGunHoldAnimation(event, mainHandItem);
        }
        return null;
    }

    /**
     * 滑铲锁定期间的静止持枪姿势：只取 {@code tac:hold:<type>}，不跟瞄准进度、跑动、开火走。
     *
     * <p>{@code tac:hold:rifle} 里有一条 {@code AllBody} 轨道，会把整个人从滑铲姿态拧过去；
     * 锁定层会在所有频道跑完之后把躯干/下肢写回滑铲，所以这里可以放心用它，手臂仍然自然。
     *
     * @return 未安装 TaCZ 或主手不是枪时返回 {@code null}
     */
    @Nullable
    public static PlayState playGunStaticHoldAnimation(ItemStack mainHandItem, AnimationEvent<? extends CustomHumanoidEntity<?>> event) {
        if (isInstalled() && TacCompatInner.isGun(mainHandItem)) {
            return TacCompatInner.playGunStaticHoldAnimation(event, mainHandItem);
        }
        return null;
    }

    @Nullable
    public static PlayState playGunOnceAnimation(ItemStack mainHandItem, AnimationEvent<? extends CustomHumanoidEntity<?>> event) {
        if (isInstalled() && TacCompatInner.isGun(mainHandItem)) {
            return TacCompatInner.playGunOnceAnimation(event, mainHandItem);
        }
        return null;
    }

    public static void openFlashShellRender(LivingEntity livingEntity, ItemStack mainHandItem) {
        if (isInstalled() && TacCompatInner.isGun(mainHandItem)) {
            TacCompatInner.openFlashShellRender(livingEntity);
        }
    }

    public static void stopFlashShellRender(ItemStack mainHandItem) {
        if (isInstalled() && TacCompatInner.isGun(mainHandItem)) {
            TacCompatInner.stopFlashShellRender();
        }
    }

    @Nullable
    public static ResourceLocation getGunId(ItemStack stack) {
        if (isInstalled()) {
            return TacCompatInner.getGunId(stack);
        }
        return null;
    }

    /**
     * 资源重载时清掉按枪 id 的缓存（手枪判定、枪 id 环形记忆）。
     *
     * <p>枪包换过之后旧 id 与旧类型判定都不再成立。
     */
    public static void clearGunCaches() {
        if (isInstalled()) {
            TacCompatInner.clearCaches();
        }
    }

    /** 该物品是否是 TaCZ 枪械；未安装 TaCZ 时返回 false。 */
    public static boolean isGun(ItemStack stack) {
        return isInstalled() && TacCompatInner.isGun(stack);
    }

    /** 该物品是不是 TaCZ 手枪；未安装 TaCZ 或不是枪时返回 false。 */
    public static boolean isPistol(ItemStack stack) {
        return isInstalled() && TacCompatInner.isGun(stack) && TacCompatInner.isPistol(stack);
    }

    /**
     * 主手枪械此刻是否正在换弹；未安装 TaCZ 或没在换弹时返回 false。
     *
     * <p>匍匐侧躺要靠它把"换弹"这个动作维持住：换弹动画是一次性的，播完之后
     * {@code player.fire} 频道就回到 IDLE，只看动画名会让身体在换弹中途滚回趴姿。
     */
    public static boolean isReloading(net.minecraft.world.entity.LivingEntity entity) {
        return isInstalled() && TacCompatInner.isReloading(entity);
    }

    /** 该物品是否属于手雷标签（与模组是否安装无关，只看标签）。 */
    public static boolean isGrenade(ItemStack stack) {
        return TacCompatInner.isGrenade(stack);
    }

    public static boolean isInstalled() {
        return INSTALLED;
    }
}
