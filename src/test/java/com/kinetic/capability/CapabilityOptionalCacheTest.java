package com.kinetic.capability;

import net.minecraftforge.common.util.LazyOptional;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * capability 查询的 LazyOptional 缓存语义：不依赖 Minecraft 客户端。
 *
 * <p>缓存的关键性质有两个：同一个 provider 反复查询必须拿到<b>同一个</b> LazyOptional 实例
 * （否则每帧每实体都会重新分配），并且 supplier 最多只会被解析一次。
 */
class CapabilityOptionalCacheTest {

    @Test
    void uninstalledCacheIsEmpty() {
        var cache = new CapabilityOptionalCache<String>();

        assertFalse(cache.get().isPresent(), "尚未安装 supplier 时必须表现为 capability 不存在");
        assertSame(LazyOptional.empty(), cache.get(), "空缓存不应分配新对象");
    }

    @Test
    void repeatedLookupsReturnTheSameOptional() {
        var cache = new CapabilityOptionalCache<String>();
        cache.install(() -> "value");

        var first = cache.get();
        var second = cache.get();

        assertSame(first, second, "同一 provider 的重复查询必须复用同一个 LazyOptional");
        assertTrue(first.isPresent());
        assertEquals("value", first.orElse(null));
    }

    @Test
    void supplierIsResolvedAtMostOnce() {
        var cache = new CapabilityOptionalCache<String>();
        var calls = new AtomicInteger();
        cache.install(() -> {
            calls.incrementAndGet();
            return "value";
        });

        for (var i = 0; i < 100; i++) {
            assertEquals("value", cache.get().orElse(null));
        }

        assertEquals(1, calls.get(), "LazyOptional 必须自行记住解析结果，supplier 只调用一次");
    }

    @Test
    void installReplacesTheOptional() {
        var cache = new CapabilityOptionalCache<String>();
        cache.install(() -> "first");
        var first = cache.get();

        cache.install(() -> "second");
        var second = cache.get();

        assertNotSame(first, second);
        assertEquals("second", second.orElse(null));
    }

    @Test
    void clearFallsBackToEmpty() {
        var cache = new CapabilityOptionalCache<String>();
        cache.install(() -> "value");
        assertTrue(cache.get().isPresent());

        cache.clear();

        assertFalse(cache.get().isPresent());
        assertSame(LazyOptional.empty(), cache.get());
    }
}
