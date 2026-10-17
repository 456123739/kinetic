package com.kinetic.capability;

import com.kinetic.model.catalog.builtin.BuiltinModelIndex;
import com.kinetic.model.domain.Hash256;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 「内置玩家模型（Steve/Alex）跟随皮肤宽/细」的判定与其记忆。
 *
 * <p>本类只做纯逻辑：不引用 Minecraft、也不引用 Quick Skin，因此可以离线单测。调用方提供当前已应用
 * 的模型哈希与目录查询能力，本类负责「输入没变就不重算」的记忆——与旧版逐帧实现完全一致，只是被调用的
 * 时机由「每个渲染帧」变成「玩家创建 / 模型就绪 / Quick Skin 报告变化」。
 *
 * <p>判定逻辑本身没有变化：宽/细由 {@link BuiltinModelIndex#playerPathForSkin(String)} 决定，
 * 当前模型不是内置玩家模型时不做任何事（返回 {@code null}，但仍记录输入，避免重复扫描目录）。
 */
public final class BuiltinSkinVariantDecision {
    private String lastSkinModelName;
    private Hash256 lastAppliedHash;

    /**
     * 计算是否需要把当前模型切换到与皮肤匹配的内置玩家模型。
     *
     * @param skinModelName        当前皮肤模型名（{@code default}/{@code slim}），空或 {@code null} 时保持现状
     * @param applied              当前已应用的模型哈希，{@code null} 时保持现状
     * @param isBuiltinPlayerModel 判断某个哈希是否为内置玩家模型
     * @param resolvePath          按模型路径查询哈希
     * @return 需要切换到的模型哈希；不需要切换时返回 {@code null}
     */
    @Nullable
    public Hash256 decide(@Nullable String skinModelName,
                          @Nullable Hash256 applied,
                          Predicate<Hash256> isBuiltinPlayerModel,
                          Function<String, Optional<Hash256>> resolvePath) {
        if (skinModelName == null || skinModelName.isEmpty() || applied == null) {
            // 拿不到皮肤模型名或当前模型时保持现状，不用「未知」去覆盖当前选择。
            return null;
        }
        // 皮肤模型名与当前模型都没变：上次的判定结果依然成立，无需再次扫描目录。
        if (skinModelName.equals(lastSkinModelName) && applied.equals(lastAppliedHash)) {
            return null;
        }
        // 不是内置玩家模型时判定结果就是「不处理」，同样记录下来，避免重复扫描。
        var matched = isBuiltinPlayerModel.test(applied)
                ? resolvePath.apply(BuiltinModelIndex.playerPathForSkin(skinModelName).value()).orElse(null)
                : null;
        var switched = matched != null && !matched.equals(applied) ? matched : null;
        lastSkinModelName = skinModelName;
        // 记忆里记录的是「本次判定之后实际生效的模型」：旧实现每帧都会再调用一次，从而把切换后的模型
        // 补记进去；改为事件驱动后不再有下一帧，必须在本次就记准，否则外部把模型改回旧值时会漏判。
        lastAppliedHash = switched != null ? switched : applied;
        return switched;
    }
}
