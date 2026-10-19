package com.kinetic.client.compat.tacz.lod;

import com.kinetic.Kinetic;
import com.kinetic.client.compat.tacz.TACZCompat;
import com.kinetic.model.resource.client.render.BakedModelCache;
import com.kinetic.util.ThreadTools;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/**
 * 枪械 LOD 的后台预热器：把"上千把枪"的冷烘焙成本挪到玩家还没用到它们的时候。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>单把枪的冷路径（Bedrock JSON 解析 + 减面 + 原生烘焙 + PNG 解码）实测约 9~51 ms，平均
 * 约 35 ms。服务器上枪包总量约 1000 把、同屏最多十几把，于是"十几个人同时掏出十几把不同的枪"
 * 会在 worker 池上排进几百毫秒的连续 CPU，低配机上直接和渲染抢核。预热把这份成本提前花掉，
 * 运行时只需要读一个约 200~300 KB 的磁盘缓存文件。
 *
 * <h2>整包复用</h2>
 *
 * <p>只要枪包内容的指纹没变（{@link GunLodPackFingerprint}），整包直接跳过：不读模型、不读贴图、
 * 不算哈希。记录写在 {@link GunLodPackIndex} 里，跨进程存活。这样"第二次启动"几乎不做任何事，
 * 而"更新过某个枪包"只重扫那一个包。
 *
 * <p>指纹只决定要不要预热，不决定渲染什么——按需路径永远重新算真实缓存键，所以指纹判错最多是
 * 少预热一次，绝不会让画面用错模型。
 *
 * <h2>两档节奏</h2>
 *
 * <ul>
 *   <li><b>未进入世界</b>（主菜单 / 加载画面）：没有游戏在跑，算力是白给的，用满
 *       {@code max(2, 核数/2)} 个 worker 全速跑完。每完成一把立刻补下一把，不受 tick 速率限制。</li>
 *   <li><b>已进入世界</b>：降到同时 1 把、且每 {@link #THROTTLED_INTERVAL_TICKS} tick 才发一把
 *       （约单核 5%），并且<b>一旦有玩家手里的枪在等按需构建就整帧让路</b>
 *       （{@link TACZCompat#hasPendingGunLod()}），保证正在用的枪永远优先。</li>
 * </ul>
 *
 * <p>预热<b>不在启动路径上</b>：它由客户端 tick 驱动、跑在 worker 池上，渲染线程从不等待，
 * 启动时间与它无关；启动完成后它会继续在后台跑。
 *
 * <h2>内存</h2>
 *
 * <p>预热只调用 {@link TaczGunLodBuilder#prewarm}，它走 {@code GunLodFactory.bakeOnly}：
 * <b>只落盘、不读回原生模型</b>。所以预热 1000 把枪的常驻原生占用是零——否则每把约
 * 200~300 KB，1000 把就是约 250 MB。常驻内存仍然只由 {@link GunLodRegistry} 的 16 条上限决定。
 *
 * <p><b>峰值</b>不等于常驻：单把枪的冷路径（解析 JSON + 减面 + proto + 解码贴图）峰值是几十 MB，
 * 而"未进入世界"那一档会并发提交。并发度因此限制到 {@link #IDLE_CONCURRENCY}（最多 4），
 * 并且堆使用率超过 {@link #HEAP_PRESSURE_RATIO} 时暂停提交，回落后自动继续。
 *
 * <h2>取消</h2>
 *
 * <p>用代次号（generation）而不是中断：资源重载时把代次加一，已经排进 worker 池的任务在开始执行时
 * 发现代次过期就立刻返回，因此取消是"排空队列"而不是"等它跑完"。
 *
 * <p>本类不引用任何 TaCZ 类型（枚举与预热都走 {@link TACZCompat} 的门面），因此未安装 TaCZ 时
 * 可以安全加载。
 */
public final class GunLodPrewarm {
    /**
     * 已进入世界时两把枪之间至少隔多少 tick。
     *
     * <p>单把枪约 35 ms，20 tick/s 下每 tick 发一把等于单核 70%——那是不可接受的。
     * 隔 20 tick（1 秒）约等于单核 5%，1000 把枪约 17 分钟补完；主菜单那一轮通常已经跑完大半。
     */
    static final int THROTTLED_INTERVAL_TICKS = 20;

    /**
     * 未进入世界时的并发度。
     *
     * <p>刻意压得低：单把枪的冷路径会同时持有解析后的 Bedrock JSON、减面中间态、proto 与一张
     * 解码后的贴图，峰值是几十 MB 量级。按核数开满（这里曾经是 {@code 核数/2}，32 线程机上是
     * 16）等于把这几十 MB 乘以 16，堆很容易被顶满——表现就是"预热吃满内存"。这里限到最多 4 路，
     * 再配合下面的堆余量闸门，把峰值钉在可预期范围内。
     */
    static final int IDLE_CONCURRENCY = Math.max(1,
            Math.min(4, Runtime.getRuntime().availableProcessors() / 2));

    /**
     * 堆使用率超过这个比例就暂停提交新的预热任务，等 GC 回落后自动继续。
     *
     * <p>预热是纯优化：宁可这一轮少算几把，也不能把堆占满、把游戏挤成 GC 卡顿。
     */
    static final double HEAP_PRESSURE_RATIO = 0.75;

    /**
     * 单次会话最多预热多少把——防御性硬上限。
     *
     * <p>即使枪包异常庞大（或索引判定失效导致反复重扫），预热也不会无限占用磁盘与 CPU。
     * 超过之后本次会话直接停手，剩下的枪交给按需构建。512 把已远超"一局里真正会遇到的枪数"。
     */
    static final int MAX_GUNS_PER_SESSION = 512;

    /** 堆余量不足时整场会话停手（不是暂停等待）。 */
    private static volatile boolean aborted;

    private static final AtomicInteger GENERATION = new AtomicInteger();
    private static final AtomicInteger CURSOR = new AtomicInteger();
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();
    private static final AtomicInteger COMPLETED = new AtomicInteger();
    private static final AtomicInteger FAILED = new AtomicInteger();
    private static final AtomicInteger TOTAL = new AtomicInteger();

    /** 每个命名空间在这次扫描里的收尾状态；全部成功后才写进枪包索引。 */
    private static final Map<String, NamespaceTally> TALLIES = new ConcurrentHashMap<>();

    private static volatile List<GunLodPrewarmPlan.Slot> queue = List.of();
    private static volatile boolean enumerated;
    private static volatile boolean enumerating;
    private static volatile boolean inWorld;
    private static volatile long lastThrottledSubmitTick;
    private static volatile long tickCount;
    private static volatile GunLodPackIndex index;

    private GunLodPrewarm() {
    }

    /** 一个命名空间的进度：总数、已完成、失败数，以及它的枪包指纹。 */
    private static final class NamespaceTally {
        final AtomicInteger total = new AtomicInteger();
        final AtomicInteger done = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        volatile String fingerprint;
    }

    /**
     * 节奏策略。刻意抽成不依赖 Minecraft 的纯函数，这样"限速"和"让路"这两条最容易写错的规则
     * 可以直接单测，而不需要启动一个客户端。
     */
    static final class Policy {
        private Policy() {
        }

        /** 该不该在这一刻发一把枪。 */
        static boolean maySubmit(boolean inWorld, boolean hasPending, long tick,
                                 long lastThrottledSubmitTick, int inFlight) {
            if (inWorld) {
                // 已进入世界：同时最多 1 把，有按需构建在等就让路，两把之间至少隔一个间隔。
                if (hasPending || inFlight > 0) {
                    return false;
                }
                return tick - lastThrottledSubmitTick >= THROTTLED_INTERVAL_TICKS;
            }
            // 未进入世界：没有游戏在跑，直接吃满池子。
            return inFlight < IDLE_CONCURRENCY;
        }

        /**
         * 该不该把这个枪包记进索引（= 下次启动整包跳过）。
         *
         * <p>这是"中断不会留下不完整数据"的关键：只有<b>全部</b>枪都确认烘好、一把都没失败、
         * 而且拿得到枪包指纹时才记录。任何一把没烘好就整包不记，下次重新扫这个包——
         * 宁可多扫一遍，也不能把"还没热"记成"已经热"。
         */
        static boolean mayRecordPack(int done, int total, int failed, String fingerprint) {
            return total > 0 && done >= total && failed == 0 && fingerprint != null;
        }
    }

    /**
     * 每个客户端 tick 泵一次。由 {@code ClientTickEvent} 调用。
     *
     * <p>幂等且廉价：枚举只做一次，之后就是几次原子读。
     */
    public static void tick() {
        // 默认关闭：预热是纯优化，但它会持续吃 CPU/磁盘，并且在枪包很多时显著抬高内存。
        // 按需构建本来就够用（首次遇到某把枪时异步烘焙一次），所以把这份风险交给用户显式开启。
        if (!com.kinetic.config.ClientConfig.ENABLE_GUN_LOD_PREWARM.get()) {
            return;
        }
        try {
            tickInternal();
        } catch (Throwable ignored) {
            // 这是客户端 tick 路径：任何异常都不能把游戏带下去。预热是纯优化，
            // 失败一次就等下一个 tick（枚举最多每秒重试一次，所以不会刷屏）。
        }
    }

    private static void tickInternal() {
        tickCount++;
        inWorld = Minecraft.getInstance().level != null;
        var cache = BakedModelCache.shared();
        if (cache == null) {
            // 客户端模型服务还没装好（启动早期 / 重载窗口）。此时预热写不进任何地方，
            // 提交只会白做减面，所以整帧跳过。
            return;
        }
        if (Minecraft.getInstance().getOverlay() != null) {
            // 还在启动 / 进服 / 资源重载的加载画面上：客户端正在读盘、建模型，预热这时去抢 worker
            // 和磁盘只会拖慢加载。用户要的是"启动完成后继续后台计算"，不是"和启动抢资源"。
            // 加载画面一消失就自动接着跑，不需要额外状态。
            return;
        }
        if (!enumerated) {
            // 枚举要扫描枪包目录、算枪包指纹（一个 1000 文件的枪包约上千次 stat），
            // 绝不能放在渲染线程上，所以整段丢给 worker。
            startEnumeration(cache);
            return;
        }
        if (CURSOR.get() >= queue.size()) {
            // 这一轮跑完了：把计划表放掉。它是一千把枪的 Slot 列表，没必要挂一整局。
            // TOTAL/COMPLETED 已经记着进度，诊断与进度条不受影响。
            if (!queue.isEmpty()) {
                queue = List.of();
            }
            if (inWorld) {
                return;
            }
        }
        if (inWorld) {
            if (!Policy.maySubmit(true, TACZCompat.hasPendingGunLod(), tickCount,
                    lastThrottledSubmitTick, IN_FLIGHT.get())) {
                return;
            }
            if (submitOne()) {
                lastThrottledSubmitTick = tickCount;
            }
        } else {
            pumpIdle();
        }
    }

    /**
     * 在 worker 上枚举一次枪表：解析枪包根目录、算枪包指纹、按 {@link GunLodPrewarmPlan} 决定
     * 这一次要扫哪些枪。
     *
     * <p>渲染线程只负责发起；结果通过 volatile 字段发布，发布顺序保证读方看到队列时其它字段已经写好。
     */
    private static void startEnumeration(BakedModelCache cache) {
        if (enumerating) {
            return;
        }
        if (!TACZCompat.isInstalled()) {
            // 没装 TaCZ 就永远不会有一把枪，不要每秒重试一次枚举。
            return;
        }
        // TaCZ 的枪械索引在资源重载后才建立；没建立起来之前不要反复枚举。
        if (tickCount % 20 != 0 && tickCount != 1) {
            return;
        }
        final List<ResourceLocation> ids;
        try {
            ids = TACZCompat.allGunIds();
        } catch (Throwable ignored) {
            // 枪包索引还没建立起来时读取可能抛错；下一个重试窗口再试。
            return;
        }
        if (ids.isEmpty()) {
            return;
        }
        var generation = GENERATION.get();
        enumerating = true;
        try {
            ThreadTools.submit(() -> {
                try {
                    var gameDirectory = Minecraft.getInstance().gameDirectory;
                    var roots = GunLodPackRoots.resolve(
                            gameDirectory == null ? null : gameDirectory.toPath());
                    if (generation != GENERATION.get()) {
                        // 枪包扫描本身也是几百毫秒级的工作，重载之后就没必要再算指纹了。
                        return;
                    }
                    var loadedIndex = GunLodPackIndex.load(
                            cache.profileRoot().resolve(GunLodPackIndex.FILE_NAME));
                    var plan = GunLodPrewarmPlan.plan(ids,
                            namespace -> GunLodPackFingerprint.of(roots.get(namespace)),
                            loadedIndex);
                    publishPlan(generation, ids, plan, loadedIndex);
                } catch (Throwable ignored) {
                    // 枚举失败只是这一轮没结果；下一个重试窗口再试。
                } finally {
                    if (generation == GENERATION.get()) {
                        enumerating = false;
                    }
                }
            });
        } catch (Throwable rejected) {
            // 线程池拒绝等异常不能影响渲染。
            enumerating = false;
        }
    }

    /**
     * 发布枚举结果。
     *
     * <p>与 {@link #restart()} 共用同一把锁，并且<b>在锁内再验一次代次</b>：否则"验完代次、
     * 结果 restart() 插进来、然后照样把旧计划装上去"会留下一个再也等不到重新枚举的会话——
     * 因为 {@code enumerated} 已经被置真，重载后新加的枪这一整局都不会被预热。
     *
     * <p>同时把 {@code CURSOR} 归零：换队列就必须从第 0 个开始。这一条同时兜住了另一种竞态——
     * 重启瞬间还在 {@code pumpIdle} 里的 worker 会对空队列多消耗一个游标，若不归零，
     * 新队列的第 0 把枪永远不会被提交，进度条会永久停在 N-1/N。
     */
    private static synchronized void publishPlan(int generation, List<ResourceLocation> ids,
                                                 GunLodPrewarmPlan.Result plan,
                                                 GunLodPackIndex loadedIndex) {
        if (generation != GENERATION.get()) {
            return;
        }
        queue = plan.slots();
        CURSOR.set(0);
        TOTAL.set(ids.size());
        COMPLETED.set(plan.skipped());
        index = loadedIndex;
        // 把计划里的每个命名空间的收尾状态装好；全部成功时才会写进枪包索引。
        TALLIES.clear();
        for (var entry : plan.fingerprints().entrySet()) {
            var tally = new NamespaceTally();
            tally.fingerprint = entry.getValue();
            tally.total.set(plan.namespaceTotals().getOrDefault(entry.getKey(), 0));
            TALLIES.put(entry.getKey(), tally);
        }
        // 立刻允许第一把（否则刚枚举完还要再等一个间隔）。
        lastThrottledSubmitTick = tickCount - THROTTLED_INTERVAL_TICKS;
        enumerated = true;
    }

    /**
     * 资源重载后重启：丢弃旧队列、让所有在途任务作废、重新枚举。
     *
     * <p>枪包换了，磁盘缓存的键（枪 id + 模型内容 + 贴图内容）本身就会变，所以这里不需要清磁盘；
     * 枪包索引也是按内容指纹记的，换包后自然不命中。
     */
    public static synchronized void restart() {
        GENERATION.incrementAndGet();
        queue = List.of();
        enumerated = false;
        enumerating = false;
        CURSOR.set(0);
        COMPLETED.set(0);
        FAILED.set(0);
        TOTAL.set(0);
        TALLIES.clear();
        aborted = false;
        lastThrottledSubmitTick = 0;
        // IN_FLIGHT 不重置：在途任务完成时自己会减，重置反而会让并发度虚高。
    }

    /** 未进入世界时由完成事件继续泵，避免 20 tick/s 成为吞吐上限。 */
    private static void pumpIdle() {
        if (inWorld || !enumerated) {
            return;
        }
        var generation = GENERATION.get();
        while (Policy.maySubmit(false, false, 0L, 0L, IN_FLIGHT.get())) {
            if (!submitOne()) {
                return;
            }
            if (generation != GENERATION.get()) {
                return;
            }
        }
    }

    /**
     * 发一把枪到 worker 池。
     *
     * @return 是否真的发出去了（队列已空时返回 false）
     */
    /**
     * 堆还有没有余量再解析下一把枪。
     *
     * <p>{@code maxMemory} 为 {@link Long#MAX_VALUE} 表示这个 JVM 没设上限（单元测试等），此时不拦。
     * 这个判断只看"整体堆"，不做逐任务计量——预热是尽力而为的优化，粗粒度够了。
     */
    private static long usedHeapMb() {
        var runtime = Runtime.getRuntime();
        return (runtime.totalMemory() - runtime.freeMemory()) >> 20;
    }

    private static long maxHeapMb() {
        return Runtime.getRuntime().maxMemory() >> 20;
    }

    static boolean hasHeapHeadroom() {
        var runtime = Runtime.getRuntime();
        var max = runtime.maxMemory();
        if (max <= 0 || max == Long.MAX_VALUE) {
            return true;
        }
        var used = runtime.totalMemory() - runtime.freeMemory();
        return used < (long) (max * HEAP_PRESSURE_RATIO);
    }

    private static boolean submitOne() {
        if (aborted) {
            return false;
        }
        if (COMPLETED.get() >= MAX_GUNS_PER_SESSION) {
            aborted = true;
            Kinetic.LOGGER.info("Gun LOD prewarm stopped at the per-session cap ({})",
                    MAX_GUNS_PER_SESSION);
            return false;
        }
        // 堆吃紧时整场停手：继续"暂停-恢复"只会让内存反复顶到阈值，玩家的可玩性比预热重要。
        if (!hasHeapHeadroom()) {
            aborted = true;
            Kinetic.LOGGER.warn("Gun LOD prewarm aborted: heap is above {}% of max",
                    (int) (HEAP_PRESSURE_RATIO * 100));
            return false;
        }
        var index = CURSOR.getAndIncrement();
        var current = queue;
        if (index >= current.size()) {
            return false;
        }
        var slot = current.get(index);
        var generation = GENERATION.get();
        IN_FLIGHT.incrementAndGet();
        try {
            ThreadTools.submit(() -> {
                var warm = false;
                var startedAt = System.nanoTime();
                var heapBefore = usedHeapMb();
                try {
                    if (generation != GENERATION.get()) {
                        // 资源重载/重启后作废：立刻返回，让队列快速排空。
                        return;
                    }
                    warm = TACZCompat.prewarmGunLod(slot.gunId());
                } catch (Throwable ignored) {
                    warm = false;
                } finally {
                    // 逐枪记录耗时与堆变化。这是唯一能定位"哪把枪把内存吃掉了"的证据来源，
                    // 一把一行、量很小，出问题直接看日志即可。
                    Kinetic.LOGGER.info("Gun LOD prewarm: {} warm={} {}ms heap {}MB -> {}MB ({}MB/{})",
                            slot.gunId(), warm, (System.nanoTime() - startedAt) / 1_000_000,
                            heapBefore, usedHeapMb(), usedHeapMb(), maxHeapMb());
                    IN_FLIGHT.decrementAndGet();
                    // 过期任务只还并发度，不碰新一代的进度与统计。
                    if (generation == GENERATION.get()) {
                        COMPLETED.incrementAndGet();
                        if (!warm) {
                            FAILED.incrementAndGet();
                        }
                        finishOne(slot.namespace(), warm);
                        pumpIdle();
                    }
                }
            });
            return true;
        } catch (Throwable rejected) {
            // 线程池拒绝等异常不能影响渲染。
            IN_FLIGHT.decrementAndGet();
            return false;
        }
    }

    /**
     * 记一把枪的收尾；某个枪包全部成功时把它的指纹写进索引。
     *
     * <p>只有<b>全部</b>成功才记录：只要有一把失败（缺贴图、原生拒绝等），这个包下次还要重扫，
     * 失败的那把才有机会重试。
     */
    private static void finishOne(String namespace, boolean warm) {
        var tally = TALLIES.get(namespace);
        var currentIndex = index;
        if (tally == null || currentIndex == null) {
            return;
        }
        if (!warm) {
            tally.failed.incrementAndGet();
        }
        var done = tally.done.incrementAndGet();
        // 每 50 把打一行进度 + 堆占用：这是判断"预热到底吃了多少内存"的唯一可靠依据。
        // 出问题时把这几行发出来就能定位，不用猜。
        var completedNow = COMPLETED.get();
        if (completedNow % 50 == 0) {
            var runtime = Runtime.getRuntime();
            var usedMb = (runtime.totalMemory() - runtime.freeMemory()) >> 20;
            var maxMb = runtime.maxMemory() >> 20;
            Kinetic.LOGGER.info("Gun LOD prewarm progress: {}/{}, failed={}, heap={}MB/{}MB",
                    completedNow, TOTAL.get(), FAILED.get(), usedMb, maxMb);
        }
        if (Policy.mayRecordPack(done, tally.total.get(), tally.failed.get(), tally.fingerprint)) {
            currentIndex.markComplete(namespace, tally.fingerprint);
        }
    }

    /** 剩余待预热的枪数。供诊断与进度显示使用。 */
    public static int remaining() {
        return Math.max(0, TOTAL.get() - COMPLETED.get());
    }

    /** 已处理完的枪数（含因整包复用而跳过的）。供诊断使用。 */
    public static int completed() {
        return COMPLETED.get();
    }

    /** 枚举出的枪总数（含跳过的）。供诊断使用。 */
    public static int total() {
        return TOTAL.get();
    }

    /** 预热失败的枪数（缺模型/缺贴图/原生拒绝）。供诊断使用。 */
    public static int failed() {
        return FAILED.get();
    }

    /** 是否还有枪等着预热——进度显示据此决定要不要出现。关闭预热时永远为 false。 */
    public static boolean isActive() {
        return com.kinetic.config.ClientConfig.ENABLE_GUN_LOD_PREWARM.get()
                && enumerated && remaining() > 0;
    }

    /** 已记录的枪包数——供诊断使用。 */
    public static int indexedPacks() {
        var current = index;
        return current == null ? 0 : current.size();
    }
}
