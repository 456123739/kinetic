package com.kinetic.capability;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 变体失效集合的纯逻辑测试：不依赖 Minecraft，也不依赖 Quick Skin。
 */
class SkinVariantInvalidationTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void dirtyIsConsumedExactlyOnce() {
        var invalidation = new SkinVariantInvalidation();

        invalidation.markDirty(ALICE);

        assertTrue(invalidation.consumeDirty(ALICE));
        assertFalse(invalidation.consumeDirty(ALICE), "同一玩家不应被重复判定");
    }

    @Test
    void cleanPlayerIsNeverDue() {
        var invalidation = new SkinVariantInvalidation();

        assertFalse(invalidation.consumeDirty(ALICE));
        assertFalse(invalidation.consumeDirty(null), "null 玩家必须安全");
        invalidation.markDirty(null);
        assertEquals(0, invalidation.size());
    }

    @Test
    void markingOnePlayerDoesNotDisturbAnother() {
        var invalidation = new SkinVariantInvalidation();

        invalidation.markDirty(ALICE);

        assertFalse(invalidation.consumeDirty(BOB));
        assertTrue(invalidation.consumeDirty(ALICE));
    }

    @Test
    void leaveClearsThePerPlayerEntry() {
        var invalidation = new SkinVariantInvalidation();

        invalidation.markDirty(ALICE);
        invalidation.forget(ALICE);

        assertEquals(0, invalidation.size());
        assertFalse(invalidation.consumeDirty(ALICE));
    }

    @Test
    void markDirtyAllMarksEveryPlayer() {
        var invalidation = new SkinVariantInvalidation();

        invalidation.markDirtyAll(List.of(ALICE, BOB));

        assertTrue(invalidation.consumeDirty(ALICE));
        assertTrue(invalidation.consumeDirty(BOB));
    }

    /** 即使离场清理被漏掉，集合也不会无限增长。 */
    @Test
    void trackedSetStaysBounded() {
        var invalidation = new SkinVariantInvalidation();

        for (int index = 0; index < SkinVariantInvalidation.MAX_TRACKED_PLAYERS + 512; index++) {
            invalidation.markDirty(new UUID(0L, index));
        }

        assertTrue(invalidation.size() <= SkinVariantInvalidation.MAX_TRACKED_PLAYERS,
                "失效集合必须有界，实际大小 " + invalidation.size());
    }

    /** 事件总线允许任意线程发布，因此标脏/消费必须并发安全。 */
    @Test
    void concurrentMarkingLosesNothing() throws Exception {
        var invalidation = new SkinVariantInvalidation();
        int threads = 8;
        int perThread = 256;
        var start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            for (int thread = 0; thread < threads; thread++) {
                int base = thread * perThread;
                executor.execute(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int index = 0; index < perThread; index++) {
                        invalidation.markDirty(new UUID(0L, base + index));
                    }
                });
            }
            start.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));

            Set<UUID> consumed = new HashSet<>();
            for (int index = 0; index < threads * perThread; index++) {
                UUID player = new UUID(0L, index);
                if (invalidation.consumeDirty(player)) {
                    consumed.add(player);
                }
            }
            assertEquals(threads * perThread, consumed.size(), "并发标脏不应丢失条目");
        } finally {
            executor.shutdownNow();
        }
    }
}
