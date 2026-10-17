package com.kinetic.client.animation.condition;

import com.kinetic.client.compat.slashblade.SlashBladeCompat;
import com.kinetic.client.compat.touhoulittlemaid.client.TlmClientCompat;
import com.kinetic.init.ModItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.*;

public class InnerClassify {
    private static final String EMPTY = "";

    public static String doClassifyTest(String extraPre, LivingEntity livingEntity, InteractionHand hand) {
        ItemStack itemInHand = livingEntity.getItemInHand(hand);
        String classify = getClassify(itemInHand);
        if (!classify.equals(EMPTY)) {
            return extraPre + classify;
        }
        return EMPTY;
    }

    public static String getClassify(ItemStack itemInHand) {
        Item item = itemInHand.getItem();
        // 优先判断拔刀剑
        if (SlashBladeCompat.isSlashBladeItem(itemInHand)) {
            return "slashblade";
        }
        // 医疗/注射类消耗品：先于进食判定返回专用分类，使其使用 use_mainhand:medical 而不是 use_mainhand:eat。
        // 该分类由模型内的同名动画驱动；模型没有该动画时会回落到 UseAnim 对应的通用动画。
        if (itemInHand.is(ModItemTags.MEDICAL)) {
            return "medical";
        }
        if (item instanceof SwordItem || itemInHand.is(ModItemTags.SWORDS)) {
            return "sword";
        }
        if (TlmClientCompat.isGohei(item)) {
            return "gohei";
        }
        if (item instanceof AxeItem || itemInHand.is(ModItemTags.AXES)) {
            return "axe";
        }
        if (item instanceof PickaxeItem || itemInHand.is(ModItemTags.PICKAXES)) {
            return "pickaxe";
        }
        if (item instanceof ShovelItem || itemInHand.is(ModItemTags.SHOVELS)) {
            return "shovel";
        }
        if (item instanceof HoeItem || itemInHand.is(ModItemTags.HOES)) {
            return "hoe";
        }
        if (item instanceof ShieldItem || itemInHand.is(ModItemTags.SHIELDS)) {
            return "shield";
        }
        if (item instanceof CrossbowItem || itemInHand.is(ModItemTags.CROSSBOWS)) {
            return "crossbow";
        }
        if (item instanceof BowItem || itemInHand.is(ModItemTags.BOWS)) {
            return "bow";
        }
        if (item instanceof FishingRodItem || itemInHand.is(ModItemTags.FISHING_RODS)) {
            return "fishing_rod";
        }
        // 对，就这个名字
        if (item instanceof TridentItem || itemInHand.is(ModItemTags.TRIDENTS)) {
            return "spear";
        }
        if (item instanceof ThrowablePotionItem || itemInHand.is(ModItemTags.THROWABLE_POTION)) {
            return "throwable_potion";
        }
        return EMPTY;
    }
}
