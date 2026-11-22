package com.kinetic.model.storage;

/**
 * 派生缓存落盘时的耐久性取舍。
 *
 * <p>baked cache（几何、动画）是**可再生成的派生数据**：写入走「临时文件 → 校验 → 原子改名」，
 * 所以最终文件名下要么不存在、要么是完整且已通过本轮校验的内容。掉电最多让临时文件半截，
 * 它在 {@code finally} 里被删掉，下次当作缓存缺失重建。
 *
 * <p>既然如此，这些写入不需要 {@code fsync}。实测在本仓库所在文件系统上，
 * 256 KB 文件 {@code write + force(true)} 是 4.3~5.0 ms/个，而不 force 是 0.3~1.9 ms/个；
 * 冷启动要为每个 target 写一份几何 + 每个动画集写一份动画，模型越多这笔开销越明显。
 *
 * <p>需要强持久性的数据（远端缓存、用户导出）不走这里，仍由各自的写入路径 fsync。
 */
public final class CacheDurability {
    /** 派生缓存写入后是否 fsync 到磁盘。保持 false；改成 true 只是更慢，不会更正确。 */
    public static final boolean FORCE_DERIVED_CACHE_WRITES = false;

    private CacheDurability() {
    }
}
