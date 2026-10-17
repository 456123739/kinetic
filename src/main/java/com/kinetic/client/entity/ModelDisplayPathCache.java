package com.kinetic.client.entity;

import com.kinetic.model.domain.Hash256;
import org.jetbrains.annotations.Nullable;

/**
 * {@code getModelId()} 目录展示路径的缓存。
 *
 * <p>{@code CustomPlayerRenderer} 每个渲染帧、每个玩家都会构造一次
 * {@code SpecialPlayerRenderEvent}，其中的 modelId 来自 {@code CustomEntity#getModelId()}。
 * 而后者会走一遍目录查询：{@code ClientCatalogManager#displayPath} 每次都会
 * {@code find()} 出一个新的 {@code ClientCatalogEntry} 和一到两个 {@code Optional}。
 *
 * <p>展示路径只由两个东西决定：
 * <ul>
 *   <li>模型 hash —— 由 {@code EntityModelBinding#modelHash()} 提供，只在模型切换时改变；</li>
 *   <li>目录快照 —— {@code ClientModelService#catalog()} 返回的 {@code ClientCatalogSnapshot}，
 *       只在资源重载 / 会话发布时整体替换。</li>
 * </ul>
 * 因此按「hash 相等 + 快照引用相同」判定命中即可：快照是不可变的，同一快照内同一 hash
 * 的展示路径必然不变，而快照一旦被替换，引用比较必然失配、缓存自动失效。
 *
 * <p>本类不依赖 Minecraft，可单独测试。
 */
final class ModelDisplayPathCache {
    @Nullable
    private Hash256 modelHash;
    @Nullable
    private Object catalog;
    @Nullable
    private String path;

    /**
     * 命中时返回缓存的展示路径，否则返回 {@code null}。
     * （展示路径本身永不为 {@code null}，所以 {@code null} 只表示未命中。）
     */
    @Nullable
    String find(Hash256 modelHash, Object catalog) {
        if (path == null || this.catalog != catalog) {
            return null;
        }
        return modelHash.equals(this.modelHash) ? path : null;
    }

    void put(Hash256 modelHash, Object catalog, String path) {
        this.modelHash = modelHash;
        this.catalog = catalog;
        this.path = path;
    }

    void clear() {
        modelHash = null;
        catalog = null;
        path = null;
    }
}
