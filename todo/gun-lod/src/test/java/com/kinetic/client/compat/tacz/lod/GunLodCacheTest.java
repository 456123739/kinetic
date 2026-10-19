package com.kinetic.client.compat.tacz.lod;

import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GunLodCache} 的行为验证：只构建一次、首次调用不阻塞、失败不重试、null 安全。
 * 用同步执行器代替 worker 池，因此完全不依赖 Minecraft 运行时。
 */
class GunLodCacheTest {
    private static final ResourceLocation AK47 = new ResourceLocation("tacz", "ak47");
    private static final ResourceLocation M4A1 = new ResourceLocation("tacz", "m4a1");

    @Test
    void buildsOnceAndServesFromCacheAfterwards() {
        var calls = new AtomicInteger();
        var cache = new GunLodCache<>(
                id -> {
                    calls.incrementAndGet();
                    return "lod:" + id.getPath();
                },
                Runnable::run);

        // 首次调用触发构建，但本帧不返回结果（渲染线程绝不等待）。
        assertNull(cache.get(AK47), "首次调用必须返回 null，不能阻塞");
        assertEquals(1, calls.get(), "首次调用应触发一次构建");

        // 之后命中缓存。
        assertEquals("lod:ak47", cache.get(AK47));
        assertEquals(1, calls.get(), "命中缓存不应再次构建");
        assertTrue(cache.isResolved(AK47));
        assertEquals(1, cache.readyCount());
    }

    @Test
    void keepsSeparateEntriesPerGun() {
        var calls = new AtomicInteger();
        var cache = new GunLodCache<>(
                id -> {
                    calls.incrementAndGet();
                    return "lod:" + id.getPath();
                },
                Runnable::run);

        cache.get(AK47);
        cache.get(M4A1);
        assertEquals(2, calls.get(), "不同枪各自构建一次");
        assertEquals("lod:ak47", cache.get(AK47));
        assertEquals("lod:m4a1", cache.get(M4A1));
        assertEquals(2, cache.readyCount());
    }

    @Test
    void recordsFailureAndNeverRetries() {
        var calls = new AtomicInteger();
        var cache = new GunLodCache<String>(
                id -> {
                    calls.incrementAndGet();
                    return null; // 枪包没有可用的 LOD
                },
                Runnable::run);

        assertNull(cache.get(AK47));
        assertEquals(1, calls.get());
        assertNull(cache.get(AK47), "失败后仍返回 null");
        assertEquals(1, calls.get(), "失败不得每帧重试");
        assertTrue(cache.isResolved(AK47), "失败也应视为已判定");
        assertEquals(0, cache.readyCount());
    }

    @Test
    void builderThrowingIsTreatedAsUnavailable() {
        var calls = new AtomicInteger();
        var cache = new GunLodCache<String>(
                id -> {
                    calls.incrementAndGet();
                    throw new IllegalStateException("模型解析失败");
                },
                Runnable::run);

        assertNull(cache.get(AK47), "构建抛异常不得外泄");
        assertNull(cache.get(AK47));
        assertEquals(1, calls.get(), "抛异常后不得重试");
    }

    @Test
    void nullIdAndRejectedExecutorAreSafe() {
        var cache = new GunLodCache<String>(id -> "x", Runnable::run);
        assertNull(cache.get(null));
        assertFalse(cache.isResolved(null));

        // 执行器拒绝任务时：不抛异常，且不会把该 id 永久卡在 pending。
        var rejecting = new GunLodCache<String>(id -> "x", command -> {
            throw new java.util.concurrent.RejectedExecutionException("busy");
        });
        assertNull(rejecting.get(AK47));
        assertFalse(rejecting.isResolved(AK47), "被拒绝时不应标记为已判定");
    }

    @Test
    void doesNotBuildTwiceWhilePending() {
        var calls = new AtomicInteger();
        var queued = new java.util.ArrayList<Runnable>();
        var cache = new GunLodCache<String>(
                id -> {
                    calls.incrementAndGet();
                    return "lod";
                },
                queued::add);

        cache.get(AK47);
        cache.get(AK47);
        cache.get(AK47);
        assertEquals(1, queued.size(), "同一把枪只应排队一次");
        assertEquals(0, calls.get(), "排队期间不应已经构建");

        queued.forEach(Runnable::run);
        assertNotNull(cache.get(AK47));
    }

    /**
     * 容量淘汰必须淘汰<b>最旧</b>的条目。
     *
     * <p>这条曾经是反的：实现保留了队首（最旧）的 {@code capacity} 条、把紧随其后的那条扔掉，
     * 于是"刚构建好、正要被渲染"的那把枪被淘汰，而真正冷掉的条目永远留在表里。用同步执行器
     * 可以精确地摆出插入顺序，从而把方向钉死。
     */
    @Test
    void evictsTheOldestEntryNotTheNewest() {
        var evicted = new java.util.ArrayList<String>();
        var cache = new GunLodCache<>(id -> "lod:" + id.getPath(), Runnable::run, 2, evicted::add);

        cache.get(gun("a"));
        cache.get(gun("b"));
        cache.get(gun("c"));

        // 请求中间那条触发淘汰：最旧的 a 必须让位，b 与 c 必须都在。
        assertEquals("lod:b", cache.get(gun("b")));
        assertEquals(2, cache.readyCount());
        assertEquals(java.util.List.of("lod:a"), evicted, "被淘汰的必须是最旧的 a");
        assertEquals("lod:c", cache.get(gun("c")), "最新的 c 不能被淘汰");
        assertEquals(java.util.List.of("lod:a"), evicted, "再命中一次不应触发第二次淘汰");
    }

    /**
     * 一次命中可能让缓存超出容量多条（例如连续构建了好几把之后才第一次命中），
     * 这时必须把<b>每一条</b>被淘汰的条目都交给回调释放，否则每条都漏一份原生模型。
     */
    @Test
    void releasesEveryEvictedEntryWhenSeveralMustGoAtOnce() {
        var evicted = new java.util.ArrayList<String>();
        var cache = new GunLodCache<>(id -> "lod:" + id.getPath(), Runnable::run, 1, evicted::add);

        cache.get(gun("a"));
        cache.get(gun("b"));
        cache.get(gun("c"));

        assertEquals("lod:c", cache.get(gun("c")));
        assertEquals(1, cache.readyCount(), "命中之后必须立刻回到容量上限");
        assertEquals(java.util.Set.of("lod:a", "lod:b"), new java.util.HashSet<>(evicted),
                "两条被淘汰的条目都必须被释放");
    }

    /**
     * 资源重载路径：{@link GunLodCache#discard()} 必须释放已有条目，并且让<b>重载之后才完成</b>
     * 的构建当场释放自己——那些句柄不会有人再用，也不会有人替它们释放。
     */
    @Test
    void discardReleasesReadyEntriesAndLateBuilds() {
        var released = new java.util.ArrayList<String>();
        var queued = new java.util.ArrayList<Runnable>();
        var cache = new GunLodCache<String>(id -> "lod:" + id.getPath(), queued::add, 0, released::add);

        cache.get(gun("a"));
        queued.forEach(Runnable::run);
        queued.clear();
        assertEquals(1, cache.readyCount());

        cache.get(gun("b"));
        cache.discard();

        assertEquals(0, cache.readyCount(), "作废之后表里不能还有条目");
        assertEquals(java.util.List.of("lod:a"), released, "已有的条目必须被释放");
        assertNull(cache.get(gun("a")), "作废之后不再服务");

        queued.forEach(Runnable::run);
        assertEquals(0, cache.readyCount(), "作废之后完成的构建不能留在表里");
        assertEquals(java.util.Set.of("lod:a", "lod:b"), new java.util.HashSet<>(released),
                "它必须被就地释放，否则没人会释放它");
    }

    private static ResourceLocation gun(String path) {
        return new ResourceLocation("tacz", path);
    }

    /**
     * 渲染线程读、worker 线程写的并发正确性。
     *
     * <p>这是本类唯一真正有风险的地方：{@code Object2ReferenceOpenHashMap} 在 rehash 时被并发读
     * 会读到错乱数据。缓存改成"写方换快照、读方只读 volatile 引用"之后，读方必须永远看不到半更新
     * 的容器。这里用多个读线程在构建期间持续读，任何容器损坏都会以异常或读到错误值暴露出来。
     */
    @Test
    void concurrentReadersNeverSeeCorruptedState() throws Exception {
        var guns = new java.util.ArrayList<ResourceLocation>();
        for (var i = 0; i < 40; i++) {
            guns.add(new ResourceLocation("tacz", "gun_" + i));
        }
        var release = new java.util.concurrent.CountDownLatch(1);
        var built = new java.util.concurrent.ConcurrentLinkedQueue<ResourceLocation>();
        var cache = new GunLodCache<String>(
                id -> {
                    built.add(id);
                    return "lod:" + id.getPath();
                },
                command -> {
                    var worker = new Thread(() -> {
                        try {
                            release.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        command.run();
                    }, "lod-builder");
                    worker.setDaemon(true);
                    worker.start();
                });

        var failures = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var readers = new java.util.ArrayList<Thread>();
        var stop = new java.util.concurrent.atomic.AtomicBoolean(false);
        for (var r = 0; r < 4; r++) {
            var thread = new Thread(() -> {
                while (!stop.get()) {
                    try {
                        for (var gun : guns) {
                            var value = cache.get(gun);
                            if (value != null && !value.equals("lod:" + gun.getPath())) {
                                throw new AssertionError("读到损坏的条目: " + value);
                            }
                        }
                        cache.readyCount();
                        cache.isResolved(guns.get(0));
                    } catch (Throwable t) {
                        failures.compareAndSet(null, t);
                        return;
                    }
                }
            }, "lod-reader-" + r);
            readers.add(thread);
            thread.start();
        }

        // 让读线程先跑起来，再放行所有构建。
        Thread.sleep(20);
        release.countDown();
        for (var i = 0; i < 1000 && cache.readyCount() < guns.size(); i++) {
            Thread.sleep(2);
        }
        stop.set(true);
        for (var thread : readers) {
            thread.join(5000);
        }

        assertNull(failures.get(), () -> "并发读不得出现异常或损坏值: " + failures.get());
        assertEquals(guns.size(), cache.readyCount(), "所有枪都必须构建完成");
        for (var gun : guns) {
            assertEquals("lod:" + gun.getPath(), cache.get(gun));
        }
    }
}
