package com.kinetic.client.compat.tacz.lod;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * 进程级的枪械 LOD 缓存入口。
 *
 * <p>单独一个类，是为了把"引用 TaCZ 的构建器"这件事隔离在真正安装 TaCZ 时才会被触达的路径上：
 * 本类只会被 {@code TacCompatInner.getGunLod}（其调用方已经用 {@code TACZCompat.isInstalled()}
 * 把关）触碰，未安装 TaCZ 时它的 {@code <clinit>} 不会执行。
 *
 * <p>缓存按枪械 id 存放，枪包重载后由 {@link #clear()} 丢弃。
 */
public final class GunLodRegistry {
    /**
     * 已就绪句柄的缓存上限。
     *
     * <p>懒加载：<b>启动时不读任何枪</b>，只有某个玩家真的拿着某把枪进入视野时才构建它。
     * 这里限制的是"同时留在内存里的句柄数"——每个句柄持有一份烘焙好的原生模型
     * （eos 最大那把枪 1767 个方块，约 1~2 MB 原生内存）。超出后按插入顺序淘汰最旧的，
     * 并释放它的原生状态；下次再遇到会重新构建。
     *
     * <p>取 16：一局里同屏出现超过 16 种不同枪械的情况很少，而 16 × 约 2 MB 是可接受的上限。
     */
    private static final int CAPACITY = 16;

    /** 渲染线程在 {@link #hasPending()} 里无锁读它，所以必须是 volatile。 */
    private static volatile GunLodCache<GunLodHandle> cache;

    private GunLodRegistry() {
    }

    /**
     * 取该枪的 LOD 句柄。首次调用触发一次异步构建并返回 {@code null}，之后 O(1) 命中。
     * 永不阻塞、永不抛出。
     */
    @Nullable
    public static GunLodHandle get(@Nullable ResourceLocation gunId) {
        if (gunId == null) {
            return null;
        }
        return cache().get(gunId);
    }

    /**
     * 资源重载时丢弃所有已构建的 LOD（贴图与模型都会换掉）。
     *
     * <p>用 {@link GunLodCache#discard()} 而不是只把引用置空：重载发生时 worker 上通常还有正在
     * 构建的枪，它们会在重载之后才发布结果；只置空的话那些句柄永远没人释放，每把漏一份原生
     * 烘焙模型。{@code discard()} 让"重载之后才完成的构建"当场释放自己。
     */
    public static synchronized void clear() {
        var previous = cache;
        cache = null;
        if (previous != null) {
            previous.discard();
        }
    }

    /** 供诊断使用。 */
    public static synchronized int readyCount() {
        return cache == null ? 0 : cache.readyCount();
    }

    /** 是否有按需构建还没完成——后台预热据此让路。无锁（缓存实例本身是 volatile 快照读）。 */
    public static boolean hasPending() {
        var existing = cache;
        return existing != null && existing.hasPending();
    }

    /**
     * 取（必要时创建）进程级缓存。
     *
     * <p>先做一次无锁的 volatile 读再进锁：这条路径每个渲染帧、每个玩家、每只手都会走一次，
     * 而缓存创建之后永远不会变，所以稳态下不该付出一次管程进入的开销。{@code cache} 是
     * volatile，双重检查在这里是安全的（实例一旦发布就是不可变的入口）。
     */
    private static GunLodCache<GunLodHandle> cache() {
        var existing = cache;
        if (existing != null) {
            return existing;
        }
        synchronized (GunLodRegistry.class) {
            existing = cache;
            if (existing == null) {
                existing = new GunLodCache<>(TaczGunLodBuilder::build,
                        com.kinetic.util.ThreadTools::submit, CAPACITY, GunLodHandle::dispose);
                cache = existing;
            }
            return existing;
        }
    }
}
