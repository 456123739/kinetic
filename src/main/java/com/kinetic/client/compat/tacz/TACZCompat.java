package com.kinetic.client.compat.tacz;

import com.kinetic.client.animation.molang.CtrlBinding;
import com.kinetic.client.compat.tacz.lod.GunLodHandle;
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
     * 取这把枪自建的 LOD 句柄（简化几何 + 贴图 + 缩放）。
     *
     * <p>首次调用只会触发一次异步构建并返回 {@code null}，调用方本帧应照常渲染完整模型；
     * 构建失败（枪包缺模型/贴图）之后恒为 {@code null}，调用方永远回退到原路径。
     * 未安装 TaCZ 时恒为 {@code null}。
     */
    @Nullable
    public static GunLodHandle getGunLod(ItemStack stack) {
        if (isInstalled() && TacCompatInner.isGun(stack)) {
            return TacCompatInner.getGunLod(stack);
        }
        return null;
    }

    /**
     * 资源重载时丢弃已构建的 LOD（贴图与模型都会换掉），并重启后台预热。
     *
     * <p>未安装 TaCZ 时前者是空操作，后者仍然执行（它自己会重新枚举，枚举为空就不做事）。
     */
    public static void clearGunLodCache() {
        if (isInstalled()) {
            TacCompatInner.clearGunLodCache();
        }
        com.kinetic.client.compat.tacz.lod.GunLodPrewarm.restart();
    }

    /**
     * 全部已加载的枪械 id；未安装 TaCZ 或索引还没建立时返回空表。
     *
     * <p>给后台预热器枚举用。TaCZ 类型只出现在 {@code TacCompatInner} 里，本方法保持类加载隔离。
     */
    public static java.util.List<ResourceLocation> allGunIds() {
        if (isInstalled()) {
            return TacCompatInner.allGunIds();
        }
        return java.util.List.of();
    }

    /**
     * 后台预热一把枪的 LOD：把烘焙产物写进磁盘缓存，<b>不读回原生模型</b>，因此预热多少把
     * 都不会增加常驻原生内存。未安装 TaCZ 时恒为 false。
     *
     * @return 这把枪现在是否已经预热好（含本来就热）
     */
    public static boolean prewarmGunLod(ResourceLocation gunId) {
        return isInstalled() && TacCompatInner.prewarmGunLod(gunId);
    }

    /** 是否有玩家手里的枪正在等按需构建——后台预热据此让路。未安装 TaCZ 时恒为 false。 */
    public static boolean hasPendingGunLod() {
        return isInstalled() && TacCompatInner.hasPendingGunLod();
    }

    /** 该物品是不是 TaCZ 手枪；未安装 TaCZ 或不是枪时返回 false。 */
    public static boolean isPistol(ItemStack stack) {
        return isInstalled() && TacCompatInner.isGun(stack) && TacCompatInner.isPistol(stack);
    }

    /** 该物品是否属于手雷标签（与模组是否安装无关，只看标签）。 */
    public static boolean isGrenade(ItemStack stack) {
        return TacCompatInner.isGrenade(stack);
    }

    public static boolean isInstalled() {
        return INSTALLED;
    }
}
