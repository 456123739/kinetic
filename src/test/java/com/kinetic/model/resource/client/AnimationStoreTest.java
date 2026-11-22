package com.kinetic.model.resource.client;

import com.kinetic.geckolib3.core.builder.Animation;
import com.kinetic.geckolib3.core.builder.LoopType;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimationStoreTest {
    @Test
    void loadsAndBindsOnlyTheFirstRequestedAnimation() {
        var loads = new HashMap<String, Integer>();
        var binds = new HashMap<String, Integer>();
        var store = AnimationStore.lazy(List.of("walk", "idle"), name -> {
            loads.merge(name, 1, Integer::sum);
            return com.kinetic.proto.mixel.asset.model.data.Animation
                    .newBuilder()
                    .setName(name)
                    .setLength(0)
                    .setLoop(com.kinetic.proto.mixel.asset.model.data.LoopType.LOOP_TYPE_PLAY_ONCE)
                    .build();
        }, source -> {
            binds.merge(source.name(), 1, Integer::sum);
            return animation(source.name());
        });

        assertEquals(AnimationStore.State.UNLOADED, store.state("walk"));
        assertEquals(AnimationStore.State.UNLOADED, store.state("idle"));
        assertFalse(store.hasFailures());

        var walk = store.get("walk");

        assertEquals("walk", walk.name);
        assertEquals(1, loads.get("walk"));
        assertEquals(1, binds.get("walk"));
        assertNull(loads.get("idle"));
        assertNull(binds.get("idle"));
        assertEquals(AnimationStore.State.READY, store.state("walk"));
        assertEquals(AnimationStore.State.UNLOADED, store.state("idle"));
        assertSame(walk, store.get("walk"));
        assertEquals(1, loads.get("walk"));
    }

    @Test
    void releasedSourceDropsBoundProtosAndKeepsTheRestLoadable() {
        // 默认模型的 352 个动画以 proto 常驻；绑定成功后必须能把原文丢掉，
        // 否则"惰性绑定"省下的内存会以另一份形式继续占着。
        var protos = new java.util.LinkedHashMap<String,
                com.kinetic.proto.mixel.asset.model.data.Animation>();
        for (var name : List.of("walk", "idle")) {
            protos.put(name, com.kinetic.proto.mixel.asset.model.data.Animation.newBuilder()
                    .setName(name).setLength(0)
                    .setLoop(com.kinetic.proto.mixel.asset.model.data.LoopType.LOOP_TYPE_LOOP)
                    .build());
        }
        var store = AnimationStore.lazy(protos.keySet(), new AnimationStore.AnimationSource() {
            @Override
            public com.kinetic.proto.mixel.asset.model.data.Animation load(String name) {
                return protos.get(name);
            }

            @Override
            public void release(String name) {
                protos.remove(name);
            }
        }, source -> animation(source.name()));

        assertEquals("walk", store.get("walk").name);
        assertEquals(List.of("idle"), List.copyOf(protos.keySet()),
                "绑定完成后只剩还没被取用的动画");
        assertEquals("idle", store.get("idle").name);
        assertTrue(protos.isEmpty(), "全部绑定后不应再保留任何 proto");
        assertEquals(AnimationStore.State.READY, store.state("walk"));
        assertSame(store.get("walk"), store.get("walk"), "释放原文不影响已绑定结果");
    }

    @Test
    void concurrentReadersBindEachAnimationExactlyOnce() throws Exception {
        var binds = new java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>();
        var store = AnimationStore.lazy(List.of("walk", "idle", "run", "jump"),
                name -> com.kinetic.proto.mixel.asset.model.data.Animation.newBuilder()
                        .setName(name).setLength(0)
                        .setLoop(com.kinetic.proto.mixel.asset.model.data.LoopType.LOOP_TYPE_LOOP)
                        .build(),
                source -> {
                    binds.computeIfAbsent(source.name(), ignored -> new AtomicInteger())
                            .incrementAndGet();
                    return animation(source.name());
                });
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var tasks = new java.util.ArrayList<java.util.concurrent.Callable<Animation>>();
            for (int i = 0; i < 200; i++) {
                var name = List.of("walk", "idle", "run", "jump").get(i % 4);
                tasks.add(() -> store.get(name));
            }
            var results = pool.invokeAll(tasks);
            for (var result : results) {
                assertNotNull(result.get());
            }
        } finally {
            pool.shutdownNow();
        }
        for (var entry : binds.entrySet()) {
            assertEquals(1, entry.getValue().get(),
                    entry.getKey() + " 只能绑定一次");
        }
    }

    @Test
    void bindFailureIsIsolatedToOneAnimation() {
        var loads = new HashMap<String, Integer>();
        var ready = animation("ready");
        var store = AnimationStore.lazy(List.of("ready", "broken"), name -> {
            loads.merge(name, 1, Integer::sum);
            return com.kinetic.proto.mixel.asset.model.data.Animation
                    .newBuilder()
                    .setName(name)
                    .setLength(0)
                    .setLoop(com.kinetic.proto.mixel.asset.model.data.LoopType.LOOP_TYPE_PLAY_ONCE)
                    .build();
        }, source -> {
            if (source.name().equals("broken")) {
                throw new IOException("bind failed");
            }
            return ready;
        });

        assertSame(ready, store.get("ready"));
        assertNull(store.get("broken"));
        assertEquals(AnimationStore.State.READY, store.state("ready"));
        assertEquals(AnimationStore.State.FAILED, store.state("broken"));
        assertTrue(store.hasFailures());
        assertSame(ready, store.get("ready"));
        assertNull(store.get("broken"));
        assertEquals(1, loads.get("ready"));
        assertEquals(1, loads.get("broken"));
    }

    @Test
    void fallsBackWhenLocalNameIsAbsentOrPayloadIsMissing() {
        var fallbackAnimation = animation("shared");
        var fallbackOnly = animation("fallback-only");
        var fallback = AnimationStore.eager(Map.of(
                "shared", fallbackAnimation,
                "fallback-only", fallbackOnly));
        var store = AnimationStore.lazy(List.of("shared"),
                name -> null, ignored -> animation("unused"), fallback);

        assertSame(fallbackAnimation, store.get("shared"));
        assertSame(fallbackOnly, store.get("fallback-only"));
    }

    @Test
    void bindsPresentLocalAnimationBeforeUsingFallback() {
        var fallbackAnimation = animation("shared");
        var localAnimation = animation("shared");
        var fallback = AnimationStore.eager(Map.of("shared", fallbackAnimation));
        var store = AnimationStore.lazy(List.of("shared"),
                name -> com.kinetic.proto.mixel.asset.model.data.Animation
                        .newBuilder()
                        .setName(name)
                        .setLength(0)
                        .setLoop(com.kinetic.proto.mixel.asset.model.data.LoopType.LOOP_TYPE_PLAY_ONCE)
                        .build(),
                ignored -> localAnimation, fallback);

        assertSame(localAnimation, store.get("shared"));
    }

    @Test
    void failedAnimationFallsBackAndDoesNotRetry() {
        var fallbackAnimation = animation("shared");
        var fallback = AnimationStore.eager(Map.of("shared", fallbackAnimation));
        var loads = new AtomicInteger();
        var failure = new AtomicReference<Throwable>();
        var gate = new ModelResourceFailureGate() {
            @Override
            public Optional<Throwable> failure() {
                return Optional.ofNullable(failure.get());
            }

            @Override
            public void fail(Throwable cause) {
                failure.compareAndSet(null, cause);
            }
        };
        var store = AnimationStore.lazy(List.of("shared"), name -> {
            loads.incrementAndGet();
            throw new IOException("broken");
        }, ignored -> animation("unused"), fallback, ignored -> gate);

        assertSame(fallbackAnimation, store.get("shared"));
        assertSame(fallbackAnimation, store.get("shared"));
        assertEquals(1, loads.get());
        assertEquals(AnimationStore.State.FAILED, store.state("shared"));

        var reconstructed = AnimationStore.lazy(List.of("shared"), name -> {
            loads.incrementAndGet();
            return null;
        }, ignored -> animation("unused"), fallback, ignored -> gate);
        assertSame(fallbackAnimation, reconstructed.get("shared"));
        assertEquals(1, loads.get());
    }

    private static Animation animation(String name) {
        return new Animation(name, 0, LoopType.PLAY_ONCE, null,
                List.of(), List.of(), List.of());
    }
}
