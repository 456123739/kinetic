package com.kinetic.client.compat.curios;

import com.kinetic.geckolib3.core.molang.binding.ContextBinding;
import com.kinetic.molang.runtime.Function;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraftforge.fml.loading.LoadingModList;
import top.theillusivec4.curios.api.CuriosApi;

import java.util.List;

import static com.kinetic.client.compat.curios.CuriosCompatInner.searchCuriosHandler;

/**
 * Curios 查询的客户端门面。
 *
 * <p><b>线程约束</b>：动画求值走 {@code AnimatableEntity#update -> tickAnimation -> predicate}，
 * 与渲染同线程，所以正常路径上这里的访问是安全的；但 native extract 与模型烘焙在 worker 上跑，
 * 历史上这个入口被跨线程调用过。{@code CuriosApi} 返回的 {@code ItemStackHandler} 不是线程安全的
 * （worker 上读会撞上半更新的列表），因此非渲染线程直接放弃判定——条件为 false，而不是崩掉游戏。
 * 需要跨线程读装备状态时，应当改为在主线程缓存快照，而不是放开这道门。
 */
public class CuriosCompat {
    private static final String MOD_ID = "curios";
    private static boolean INSTALLED;

    public static void init() {
        INSTALLED = LoadingModList.get().getModFileById(MOD_ID) != null;
    }

    public static boolean isInstalled() {
        return INSTALLED;
    }

    /**
     * 当前是否在渲染线程（= 客户端主线程）。
     *
     * <p>离线单测没有 GL 上下文，{@link RenderSystem} 不可用，这时放行以保持原有行为。
     */
    private static boolean onRenderThread() {
        try {
            return RenderSystem.isOnRenderThread();
        } catch (Throwable unavailable) {
            return true;
        }
    }

    public static boolean hasAnyItemEquipped(LivingEntity entity, String slotType, ReferenceOpenHashSet<Item> items) {
        return INSTALLED && onRenderThread() && CuriosApi.getCuriosInventory(entity)
                .map(handler -> handler)
                .flatMap(inventory -> inventory.getStacksHandler(slotType))
                .map(curiosHandler -> searchCuriosHandler(curiosHandler, itemStack -> items.isEmpty() || items.contains(itemStack.getItem())) != null)
                .orElse(false);
    }

    public static boolean hasAnyItemEquippedWithAnyTag(LivingEntity entity, String slotType, List<TagKey<Item>> tags) {
        return INSTALLED && onRenderThread() && CuriosApi.getCuriosInventory(entity)
                .map(handler -> handler)
                .flatMap(inventory -> inventory.getStacksHandler(slotType))
                .map(curiosHandler -> searchCuriosHandler(curiosHandler, itemStack -> {
                    for (var tagKey : tags) {
                        if (itemStack.is(tagKey)) {
                            return true;
                        }
                    }
                    return false;
                }) != null)
                .orElse(false);
    }

    public static boolean hasAnyItemEquippedWithAllTag(LivingEntity entity, String slotType, List<TagKey<Item>> tags) {
        return INSTALLED && onRenderThread() && CuriosApi.getCuriosInventory(entity)
                .map(handler -> handler)
                .flatMap(inventory -> inventory.getStacksHandler(slotType))
                .map(curiosHandler -> searchCuriosHandler(curiosHandler, itemStack -> {
                    for (var tagKey : tags) {
                        if (!itemStack.is(tagKey)) {
                            return false;
                        }
                    }
                    return true;
                }) != null)
                .orElse(false);
    }

    public static void addMolangBinding(ContextBinding binding) {
        if (INSTALLED) {
            CuriosCompatInner.addMolangBinding(binding);
        } else {
            addMolangBindingPlaceholder(binding);
        }
    }

    private static void addMolangBindingPlaceholder(ContextBinding binding) {
        binding.function("has_any_curios", Function.PLACE_HOLDER);
        binding.function("has_any_curios_with_all_tags", Function.PLACE_HOLDER);
        binding.function("has_any_curios_with_any_tag", Function.PLACE_HOLDER);
        binding.livingEntityVar("dump_curios", ctx -> {
            ctx.debugPrint("Curios not installed.");
            return null;
        });
    }
}
