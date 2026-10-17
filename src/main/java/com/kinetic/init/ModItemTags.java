package com.kinetic.init;

import com.kinetic.Kinetic;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

public class ModItemTags {
    public static final TagKey<Item> AXES = tag("axes");
    public static final TagKey<Item> HOES = tag("hoes");
    public static final TagKey<Item> PICKAXES = tag("pickaxes");
    public static final TagKey<Item> SHOVELS = tag("shovels");
    public static final TagKey<Item> SWORDS = tag("swords");
    public static final TagKey<Item> THROWABLE_POTION = tag("throwable_potion");
    public static final TagKey<Item> BOWS = tag("bows");
    public static final TagKey<Item> CROSSBOWS = tag("crossbows");
    public static final TagKey<Item> FISHING_RODS = tag("fishing_rods");
    public static final TagKey<Item> SHIELDS = tag("shields");
    public static final TagKey<Item> TRIDENTS = tag("tridents");
    public static final TagKey<Item> SLASH_BLADE = tag("slashblade");
    /** 手雷类物品：蓄力时用专用投掷姿势，而不是拉弓。 */
    public static final TagKey<Item> GRENADE = tag("grenade");
    /** 医疗/注射类消耗品：用「打药」姿势，而不是通用进食动画。 */
    public static final TagKey<Item> MEDICAL = tag("medical");

    private static TagKey<Item> tag(String name) {
        return ItemTags.create(new ResourceLocation(Kinetic.MOD_ID, name));
    }
}
