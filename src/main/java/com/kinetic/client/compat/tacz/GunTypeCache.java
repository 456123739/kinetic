package com.kinetic.client.compat.tacz;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * 「这把枪是不是手枪」判定的缓存。
 *
 * <p><b>键的选择</b>：{@link ResourceLocation}，即 {@code IGun#getGunId(ItemStack)} 的返回值——
 * 也就是 {@code TimelessAPI.getCommonGunIndex(...)} 实际消费的那个量。它由枪自己的 NBT
 * （{@code GunId} 标签）决定，配件/弹药等其它 NBT 不参与判定，所以同一个键必然给出同一个答案；
 * GunId 一变就是另一个键，缓存自然不命中。
 *
 * <p>之所以不按 {@code Item} 或 ItemStack 缓存、从而连 {@code getGunId} 一起省掉：
 * {@code getGunId} 是接口方法，枪包/附属模组可以覆盖它，按 Item 缓存会绕过它们的实现；
 * 按 NBT 文本缓存则要求这里复刻 TaCZ 的读取逻辑，同样会与覆盖实现分叉。因此这里只缓存
 * 「gunId → 是否手枪」这一段，键就是 API 给出的 gunId。
 *
 * <p><b>失效</b>：枪包索引会在资源重载（F3+T）时重建，因此 {@link #clear()} 挂在
 * {@code ClientSetupEvent} 里那个 ResourceManagerReloadListener 上（与 {@code GunLodRegistry} 同一个钩子）。
 *
 * <p>纯逻辑、不引用 TaCZ 类型，可单独测试。
 */
final class GunTypeCache {
    /** 兜底上限：真实枪包的手枪种类远少于此；超出时整体清空，只会让所有判定各多算一次。 */
    static final int MAX_ENTRIES = 512;

    private final Map<ResourceLocation, Boolean> verdicts = new ConcurrentHashMap<>();

    /**
     * @param gunId    非 null；{@code IGun#getGunId} 的结果
     * @param resolver 未命中时用来判定；必须返回非 null
     */
    boolean isPistol(ResourceLocation gunId, Predicate<ResourceLocation> resolver) {
        var cached = verdicts.get(gunId);
        if (cached != null) {
            return cached;
        }
        var resolved = resolver.test(gunId);
        if (verdicts.size() >= MAX_ENTRIES) {
            verdicts.clear();
        }
        verdicts.put(gunId, resolved);
        return resolved;
    }

    /** 资源重载时调用：枪包索引会重建，旧判定不再可信。 */
    void clear() {
        verdicts.clear();
    }

    /** 仅用于测试与诊断。 */
    int size() {
        return verdicts.size();
    }
}
