package com.kinetic.client.compat.tacz.lod;

import com.kinetic.util.ThreadTools;
import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * 逐枪 LOD 句柄缓存。
 *
 * <p>渲染路径每帧都会问"这把枪的 LOD 好了没"，所以：
 * <ul>
 *   <li>命中已就绪的条目时只做一次哈希查表，<b>不分配</b>、不加锁、不做资源查找；</li>
 *   <li>构建只发生一次（首次遇到该枪），放在 worker 池上，<b>渲染线程绝不等待</b>；</li>
 *   <li>构建失败（枪包缺模型、解析失败等）记入 {@code unavailable}，之后不再重试，避免每帧
 *       重复付出失败代价。</li>
 * </ul>
 *
 * <p><b>线程模型。</b>渲染线程只读，worker 线程只写。写入方（在 {@code synchronized} 内）构造一份
 * <b>新的不可变快照</b>并原子替换 {@link #snapshot}，读方只读那个 volatile 引用。因此渲染线程
 * 不需要加锁，也不会看到半更新的 {@code fastutil} 容器（{@code Object2ReferenceOpenHashMap}
 * 在 rehash 时被并发读会读到错乱的数据）。
 *
 * <p>本类不引用任何 TaCZ 类型：构建逻辑由调用方以 {@link Builder} 注入，因此可以离线单测。
 */
public final class GunLodCache<T> {
    /** 由调用方提供的实际构建逻辑；返回 null 表示这把枪没有可用的 LOD。 */
    public interface Builder<T> {
        @Nullable
        T build(ResourceLocation gunId);
    }

    /**
     * 一次快照。三个容器在发布之后都不再修改，所以读方可以安全地无锁访问。
     */
    private record Snapshot<T>(LinkedHashMap<ResourceLocation, T> ready,
                               ObjectOpenHashSet<ResourceLocation> pending,
                               ObjectOpenHashSet<ResourceLocation> unavailable) {
    }

    private final Executor executor;
    private final Builder<T> builder;
    /** 已就绪条目上限；{@code <= 0} 表示不限（测试用）。 */
    private final int capacity;
    /** 淘汰回调：用来释放被淘汰句柄持有的原生资源。 */
    private final Consumer<T> onEvict;
    private volatile Snapshot<T> snapshot = new Snapshot<>(
            new LinkedHashMap<>(), new ObjectOpenHashSet<>(), new ObjectOpenHashSet<>());

    /**
     * 每个已就绪条目最后一次被渲染路径取用的时间戳。
     *
     * <p>只在渲染线程读写（{@link #get} 与 {@link #sweepIdle} 都只在渲染线程调用），因此不需要加锁。
     */
    private final Reference2LongOpenHashMap<ResourceLocation> lastUse = new Reference2LongOpenHashMap<>();

    /** 被 {@link #discard()} 作废后置位；之后完成构建的条目会被立刻释放而不是留在表里。 */
    private volatile boolean discarded;

    public GunLodCache(Builder<T> builder) {
        this(builder, ThreadTools::submit);
    }

    /** 测试用：注入同步或受控的执行器（不限容量）。 */
    public GunLodCache(Builder<T> builder, Executor executor) {
        this(builder, executor, 0, null);
    }

    /**
     * @param capacity 已就绪条目上限；超出后按插入顺序淘汰最旧的，{@code <= 0} 表示不限
     * @param onEvict  淘汰回调，用来 {@code release()} 掉原生资源；可为 null
     */
    public GunLodCache(Builder<T> builder, Executor executor, int capacity, @Nullable Consumer<T> onEvict) {
        this.builder = builder;
        this.executor = executor;
        this.capacity = capacity;
        this.onEvict = onEvict;
    }

    /**
     * 取该枪的 LOD 句柄。首次调用会触发一次异步构建并返回 {@code null}（下一帧起可用）；
     * 之后命中即 O(1) 返回。永不阻塞、永不抛出。
     */
    @Nullable
    public T get(@Nullable ResourceLocation gunId) {
        if (gunId == null || discarded) {
            return null;
        }
        var current = snapshot;
        // 每次调用都检查一次容量，而不是只在命中时检查：只在命中时检查的话，一把"被构建好、
        // 只画过一帧就再也没人问"的枪（玩家走出视野、或者距离判定在取 LOD 之前就返回了 null）
        // 永远不会触发淘汰，容量上限就变成了软上限。淘汰只在渲染线程做——原生状态不能被
        // 正在渲染的句柄并发释放。
        if (capacity > 0 && current.ready().size() > capacity) {
            evictOldest(gunId);
            current = snapshot;
        }
        var cached = current.ready().get(gunId);
        if (cached != null) {
            // 记下"这一刻还有人在画它"，空闲回收据此判断这把枪还有没有理由常驻。
            // fastutil 的 map 不是线程安全的，而渲染线程之外也可能有人（测试、诊断）并发问；
            // 临界区只有一次写，代价可忽略。
            synchronized (lastUse) {
                lastUse.put(gunId, System.nanoTime());
            }
            return cached;
        }
        if (current.unavailable().contains(gunId) || current.pending().contains(gunId)) {
            return null;
        }
        // 先占位，避免同一把枪被并发排队多次。
        if (!markPending(gunId)) {
            return null;
        }
        try {
            executor.execute(() -> buildOffThread(gunId));
        } catch (Throwable failure) {
            // 线程池拒绝等异常不能影响渲染。
            clearPending(gunId);
        }
        return null;
    }

    /** 是否已经构建过（无论成功还是失败）——供诊断使用。 */
    public boolean isResolved(@Nullable ResourceLocation gunId) {
        if (gunId == null) {
            return false;
        }
        var current = snapshot;
        return current.ready().containsKey(gunId) || current.unavailable().contains(gunId);
    }

    /** 已就绪的条目数——供诊断使用。 */
    public int readyCount() {
        return snapshot.ready().size();
    }

    /**
     * 是否有"渲染路径按需排队、还没构建完"的枪。
     *
     * <p>后台预热用它来决定让路：只要有玩家手里的枪在等构建，预热就不该再占 worker。
     * 无锁、不分配。
     */
    public boolean hasPending() {
        return !snapshot.pending().isEmpty();
    }

    private synchronized boolean markPending(ResourceLocation gunId) {
        var current = snapshot;
        if (current.ready().containsKey(gunId) || current.unavailable().contains(gunId)
                || current.pending().contains(gunId)) {
            return false;
        }
        var pending = new ObjectOpenHashSet<>(current.pending());
        pending.add(gunId);
        snapshot = new Snapshot<>(current.ready(), pending, current.unavailable());
        return true;
    }

    private synchronized void clearPending(ResourceLocation gunId) {
        var current = snapshot;
        if (!current.pending().contains(gunId)) {
            return;
        }
        var pending = new ObjectOpenHashSet<>(current.pending());
        pending.remove(gunId);
        snapshot = new Snapshot<>(current.ready(), pending, current.unavailable());
    }

    private void buildOffThread(ResourceLocation gunId) {
        T built = null;
        try {
            built = builder.build(gunId);
        } catch (Throwable ignored) {
            // 构建失败等价于"这把枪没有 LOD"。
        }
        publish(gunId, built);
    }

    private void publish(ResourceLocation gunId, @Nullable T built) {
        T discardedValue = null;
        synchronized (this) {
            if (discarded) {
                // 资源重载已经把这个缓存作废。构建是上一代发起的，结果不会有人再用，
                // 也不会有人替它释放，所以必须在这里就地释放——否则每把在重载瞬间构建完的枪
                // 都会漏掉一份原生烘焙模型（约 200~300 KB）。
                discardedValue = built;
            } else {
                var current = snapshot;
                var pending = new ObjectOpenHashSet<>(current.pending());
                pending.remove(gunId);
                var ready = current.ready();
                var unavailable = current.unavailable();
                if (built != null) {
                    ready = new LinkedHashMap<>(current.ready());
                    ready.put(gunId, built);
                } else {
                    unavailable = new ObjectOpenHashSet<>(current.unavailable());
                    unavailable.add(gunId);
                }
                snapshot = new Snapshot<>(ready, pending, unavailable);
            }
        }
        // 释放放到锁外：onEvict 会做原生释放，不该让渲染线程卡在锁上。
        release(discardedValue);
    }

    /**
     * 作废整个缓存：丢掉全部条目、释放它们，并让之后完成构建的条目<b>立刻被释放</b>而不是留在表里。
     *
     * <p>资源重载时调用。第二个动作是必须的：重载发生时 worker 上通常还有正在构建的枪，
     * 它们在重载之后才 publish，如果没有这条规则，这些句柄就再也不会被任何人释放。
     */
    public void discard() {
        List<T> released;
        synchronized (this) {
            discarded = true;
            released = new ArrayList<>(snapshot.ready().values());
            snapshot = new Snapshot<>(new LinkedHashMap<>(), new ObjectOpenHashSet<>(),
                    new ObjectOpenHashSet<>());
        }
        for (var value : released) {
            release(value);
        }
    }

    private void release(@Nullable T value) {
        if (value == null || onEvict == null) {
            return;
        }
        try {
            onEvict.accept(value);
        } catch (Throwable ignored) {
            // 释放失败不影响渲染。
        }
    }

    /**
     * 淘汰最旧的已就绪条目（不淘汰 {@code keep}），并把<b>每一个</b>被淘汰的条目交给
     * {@code onEvict} 释放。
     *
     * <p>"最旧"按插入顺序判定：{@code ready} 是 {@link LinkedHashMap}，{@code publish} 往尾部追加，
     * 所以队首就是最久没被放进来、也最不可能马上再被用到的那把枪。淘汰从队首开始，不是从队尾——
     * 淘汰队尾会把刚构建好、正要被渲染的那把枪扔掉，而真正冷掉的条目永远留在表里。
     *
     * <p>只在渲染线程调用：{@code onEvict} 会释放原生状态，必须保证没有正在进行的原生渲染
     * 还在读它。所有渲染都在渲染线程同步完成，所以在两次渲染之间淘汰是安全的。
     */
    private void evictOldest(ResourceLocation keep) {
        if (capacity <= 0) {
            return;
        }
        List<T> evicted;
        synchronized (this) {
            var current = snapshot;
            var ready = current.ready();
            var excess = ready.size() - capacity;
            if (excess <= 0) {
                return;
            }
            var trimmed = new LinkedHashMap<ResourceLocation, T>(ready.size());
            evicted = new ArrayList<>(excess);
            for (var entry : ready.entrySet()) {
                if (excess > 0 && !entry.getKey().equals(keep)) {
                    evicted.add(entry.getValue());
                    excess--;
                    continue;
                }
                trimmed.put(entry.getKey(), entry.getValue());
            }
            if (evicted.isEmpty()) {
                return;
            }
            snapshot = new Snapshot<>(trimmed, current.pending(), current.unavailable());
        }
        if (onEvict != null) {
            for (var value : evicted) {
                try {
                    onEvict.accept(value);
                } catch (Throwable ignored) {
                    // 释放失败不影响渲染。
                }
            }
        }
    }

    /**
     * 释放"已经没人用"的条目。
     *
     * <p>渲染路径每帧都会对真正在画面上的枪调用 {@link #get}，所以超过 {@code maxIdleNanos}
     * 没有被取用过的条目，意味着持有它的玩家已经死亡、离开视野或换了枪——那份原生烘焙模型
     * 已经没有理由继续常驻。这里把它们连同 {@code onEvict} 一起释放，让常驻占用只跟
     * "此刻画面上有几把枪"相关，而不是跟"这一局见过几把枪"相关。
     *
     * <p>只在渲染线程调用，原因同 {@link #evictOldest}：原生状态不能被正在渲染的句柄并发释放。
     */
    public void sweepIdle(long maxIdleNanos) {
        if (discarded) {
            return;
        }
        List<T> released;
        var now = System.nanoTime();
        synchronized (this) {
            var current = snapshot;
            var ready = current.ready();
            if (ready.isEmpty()) {
                return;
            }
            var kept = new LinkedHashMap<ResourceLocation, T>(ready.size());
            var idleIds = new ArrayList<ResourceLocation>();
            released = new ArrayList<>(0);
            for (var entry : ready.entrySet()) {
                final long seen;
                synchronized (lastUse) {
                    seen = lastUse.getLong(entry.getKey());
                }
                if (seen == 0L) {
                    // 刚构建好、还没轮到渲染：给一个完整的宽限期，别刚建好就扔掉。
                    synchronized (lastUse) {
                        lastUse.put(entry.getKey(), now);
                    }
                } else if (now - seen > maxIdleNanos) {
                    idleIds.add(entry.getKey());
                    released.add(entry.getValue());
                    continue;
                }
                kept.put(entry.getKey(), entry.getValue());
            }
            if (idleIds.isEmpty()) {
                return;
            }
            synchronized (lastUse) {
                for (var id : idleIds) {
                    lastUse.removeLong(id);
                }
            }
            snapshot = new Snapshot<>(kept, current.pending(), current.unavailable());
        }
        for (var value : released) {
            release(value);
        }
    }

    /** 已就绪条目上限；{@code <= 0} 表示不限。 */
    public int capacity() {
        return capacity;
    }

    /** 诊断用：对每个已就绪条目执行一次回调（仅在非渲染线程调用）。 */
    public void forEachReady(Consumer<T> visitor) {
        for (T value : snapshot.ready().values()) {
            visitor.accept(value);
        }
    }
}
