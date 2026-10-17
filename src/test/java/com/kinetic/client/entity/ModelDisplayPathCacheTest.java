package com.kinetic.client.entity;

import com.kinetic.model.domain.Hash256;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code getModelId()} 展示路径缓存的失效语义：不依赖 Minecraft 客户端。
 *
 * <p>缓存必须在两种情况下失效——模型 hash 变化、目录快照被替换；其余情况必须命中，
 * 否则每个渲染帧仍会走一遍目录查询。
 */
class ModelDisplayPathCacheTest {

    private static Hash256 hash(int seed) {
        var bytes = new byte[Hash256.SIZE];
        bytes[0] = (byte) seed;
        return new Hash256(bytes);
    }

    @Test
    void emptyCacheMisses() {
        var cache = new ModelDisplayPathCache();

        assertNull(cache.find(hash(1), new Object()));
    }

    @Test
    void sameHashAndCatalogHits() {
        var cache = new ModelDisplayPathCache();
        var model = hash(1);
        var catalog = new Object();

        cache.put(model, catalog, "misc/2_steve");

        assertEquals("misc/2_steve", cache.find(model, catalog));
    }

    @Test
    void equalHashInstanceStillHits() {
        var cache = new ModelDisplayPathCache();
        var catalog = new Object();
        cache.put(hash(1), catalog, "misc/2_steve");

        // Hash256 按值相等，不同实例也必须命中（模型切换会用新的实例表示同一个模型）。
        assertEquals("misc/2_steve", cache.find(hash(1), catalog));
    }

    @Test
    void differentHashMisses() {
        var cache = new ModelDisplayPathCache();
        var catalog = new Object();
        cache.put(hash(1), catalog, "misc/2_steve");

        assertNull(cache.find(hash(2), catalog));
    }

    @Test
    void replacedCatalogMisses() {
        var cache = new ModelDisplayPathCache();
        var model = hash(1);
        cache.put(model, new Object(), "misc/2_steve");

        // 资源重载 / 会话发布会整体替换目录快照；即使内容恰好相同，引用也必然不同。
        assertNull(cache.find(model, new Object()));
    }

    @Test
    void clearMisses() {
        var cache = new ModelDisplayPathCache();
        var model = hash(1);
        var catalog = new Object();
        cache.put(model, catalog, "misc/2_steve");

        cache.clear();

        assertNull(cache.find(model, catalog));
    }

    @Test
    void newestPutWins() {
        var cache = new ModelDisplayPathCache();
        var catalog = new Object();
        cache.put(hash(1), catalog, "misc/2_steve");
        cache.put(hash(1), catalog, "misc/1_alex");

        assertEquals("misc/1_alex", cache.find(hash(1), catalog));
    }
}
