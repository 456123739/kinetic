package com.kinetic.client.compat.tacz;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 手枪判定缓存的失效语义：不依赖 TaCZ，也不依赖 Minecraft 客户端。
 */
class GunTypeCacheTest {

    private static ResourceLocation gun(String path) {
        return new ResourceLocation("tacz", path);
    }

    @Test
    void verdictIsResolvedOncePerGunId() {
        var cache = new GunTypeCache();
        var calls = new AtomicInteger();

        assertTrue(cache.isPistol(gun("pistol"), id -> {
            calls.incrementAndGet();
            return true;
        }));
        assertTrue(cache.isPistol(gun("pistol"), id -> {
            calls.incrementAndGet();
            return true;
        }));

        assertEquals(1, calls.get(), "同一个 gunId 只应查询一次枪包索引");
    }

    @Test
    void falseVerdictIsCachedToo() {
        var cache = new GunTypeCache();
        var calls = new AtomicInteger();

        assertFalse(cache.isPistol(gun("rifle"), id -> {
            calls.incrementAndGet();
            return false;
        }));
        assertFalse(cache.isPistol(gun("rifle"), id -> {
            calls.incrementAndGet();
            return false;
        }));

        assertEquals(1, calls.get(), "「不是手枪」同样要缓存，否则每帧仍会查索引");
    }

    @Test
    void differentGunIdsAreIndependent() {
        var cache = new GunTypeCache();

        assertTrue(cache.isPistol(gun("pistol"), id -> true));
        assertFalse(cache.isPistol(gun("rifle"), id -> false));
        assertTrue(cache.isPistol(gun("pistol"), id -> false), "已缓存的判定不应被其它枪覆盖");
    }

    @Test
    void clearForcesReResolution() {
        var cache = new GunTypeCache();
        var calls = new AtomicInteger();

        cache.isPistol(gun("pistol"), id -> {
            calls.incrementAndGet();
            return true;
        });
        cache.clear();
        cache.isPistol(gun("pistol"), id -> {
            calls.incrementAndGet();
            return true;
        });

        assertEquals(2, calls.get(), "资源重载后枪包索引会重建，必须重新判定");
    }

    @Test
    void staysBounded() {
        var cache = new GunTypeCache();
        for (var i = 0; i < GunTypeCache.MAX_ENTRIES * 3; i++) {
            cache.isPistol(gun("gun" + i), id -> false);
        }

        assertTrue(cache.size() <= GunTypeCache.MAX_ENTRIES,
                "超出上限后应整体清空，而不是无限增长");
    }
}
