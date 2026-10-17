package com.kinetic.capability;

import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内置玩家模型「宽/细手臂」判定失效集合。
 *
 * <p>旧实现由 {@code PlayerAnimatableCapability#checkModelUpdate()} 在每个渲染帧对每个被跟踪玩家
 * 轮询皮肤模型名。现在改为：只有在本集合里登记过的玩家，才会在下一次使用时重新判定一次。
 *
 * <p>登记来源（见 {@link PlayerAnimatableCapability} 与 Quick Skin 兼容层）：
 * <ul>
 *   <li>玩家能力首次创建（每个玩家一次）；</li>
 *   <li>该玩家的模型渲染目标加载/切换（模型真正可用时的那一次判定）；</li>
 *   <li>Quick Skin 报告该玩家皮肤/外观变化时。</li>
 * </ul>
 *
 * <p><b>线程安全</b>：Quick Skin 的事件在客户端线程发布（{@code InternalEventBus.post} 由
 * {@code PlayerAppearanceService#applyLook} 调用，而它的所有入口都在客户端线程上），但事件总线本身
 * 允许任意线程发布，因此这里用并发集合，不假设调用线程。
 *
 * <p><b>有界</b>：正常路径由 {@code EntityLeaveLevelEvent} 调用 {@link #forget(UUID)} 清理；
 * {@link #MAX_TRACKED_PLAYERS} 只是兜底，避免任何遗漏的离场路径让集合无限增长。
 */
public final class SkinVariantInvalidation {
    /** 兜底上限：真实客户端的被跟踪玩家数远低于此值。 */
    public static final int MAX_TRACKED_PLAYERS = 4096;

    /** 生产环境共享实例；兼容层与能力都通过它登记/消费。 */
    public static final SkinVariantInvalidation INSTANCE = new SkinVariantInvalidation();

    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();

    /**
     * 登记一个玩家，使其次次使用时重新判定一次内置玩家模型变体。
     *
     * <p>超过上限时整体清空：这只可能让所有玩家各多判定一次，不会丢失正确性。
     */
    public void markDirty(@Nullable UUID playerId) {
        if (playerId == null) {
            return;
        }
        if (dirty.size() >= MAX_TRACKED_PLAYERS) {
            dirty.clear();
        }
        dirty.add(playerId);
    }

    /** 批量登记（模型/资源整体失效时使用）。 */
    public void markDirtyAll(Collection<UUID> playerIds) {
        if (playerIds == null) {
            return;
        }
        for (UUID playerId : playerIds) {
            markDirty(playerId);
        }
    }

    /**
     * 消费该玩家的失效标记。
     *
     * @return 是否应当立即重新判定一次
     */
    public boolean consumeDirty(@Nullable UUID playerId) {
        if (playerId == null || dirty.isEmpty()) {
            return false;
        }
        return dirty.remove(playerId);
    }

    /** 玩家离开世界时调用，确保不会留下按玩家增长的条目。 */
    public void forget(@Nullable UUID playerId) {
        if (playerId != null) {
            dirty.remove(playerId);
        }
    }

    /** 当前待判定的玩家数量，仅用于测试与诊断。 */
    public int size() {
        return dirty.size();
    }

    /** 清空全部待判定玩家，仅用于测试与整体重置。 */
    public void clear() {
        dirty.clear();
    }
}
