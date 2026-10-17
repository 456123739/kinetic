package com.kinetic.client.compat.tacz.lod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;

/**
 * 预热计划：把"TaCZ 报上来的全部枪 id"折算成"这一次真正要扫哪些枪"。
 *
 * <p>折算规则只有一条，但它是整个"整包复用"的核心：**某个命名空间的枪包指纹与上次记录的一致时，
 * 它的枪一把都不进队列**——不读模型、不读贴图、不算哈希，直接算作已完成。
 *
 * <p>刻意抽成不依赖 Minecraft 运行时的纯函数（除了 {@link ResourceLocation} 这个 id 类型），
 * 这样"哪些该跳过、跳过后进度怎么算"可以直接单测，不需要启动客户端，也不需要真实的枪包。
 */
final class GunLodPrewarmPlan {
    /** 队列里的一把枪：预热时要按命名空间归并，所以顺带记住它属于哪个枪包。 */
    record Slot(ResourceLocation gunId, String namespace) {
    }

    /**
     * @param slots           需要真正扫描的枪
     * @param skipped         因为整包复用而跳过的枪数（它们已经算作完成）
     * @param namespaceTotals 每个命名空间<b>需要扫描</b>的枪数（跳过的包不计入）
     * @param fingerprints    每个命名空间的枪包指纹；{@code null} 值表示"无法证明未变"
     */
    record Result(List<Slot> slots, int skipped,
                  Map<String, Integer> namespaceTotals,
                  Map<String, String> fingerprints) {
    }

    private GunLodPrewarmPlan() {
    }

    /**
     * @param gunIds        全部枪 id
     * @param fingerprintOf 命名空间 → 枪包指纹（可能返回 {@code null}）
     * @param index         已记录"整包预热过"的枪包索引
     */
    static Result plan(List<ResourceLocation> gunIds,
                       Function<String, String> fingerprintOf,
                       GunLodPackIndex index) {
        var slots = new ArrayList<Slot>(gunIds.size());
        var totals = new LinkedHashMap<String, Integer>();
        var fingerprints = new LinkedHashMap<String, String>();
        var skipped = 0;
        for (var gunId : gunIds) {
            var namespace = gunId.getNamespace();
            // 指纹只算一次；拿不到就是 null，表示"无法证明未变"。
            if (!fingerprints.containsKey(namespace)) {
                fingerprints.put(namespace, fingerprintOf.apply(namespace));
            }
            if (index.isComplete(namespace, fingerprints.get(namespace))) {
                skipped++;
                continue;
            }
            totals.merge(namespace, 1, Integer::sum);
            slots.add(new Slot(gunId, namespace));
        }
        // 不能用 Map.copyOf：指纹可能是 null（表示"无法证明未变"），而 Map.copyOf 拒绝 null 值。
        return new Result(List.copyOf(slots), skipped,
                java.util.Collections.unmodifiableMap(new LinkedHashMap<>(totals)),
                java.util.Collections.unmodifiableMap(new LinkedHashMap<>(fingerprints)));
    }
}
