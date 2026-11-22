package com.kinetic.model.resource.client;

import com.kinetic.geckolib3.core.builder.Animation;
import it.unimi.dsi.fastutil.objects.Object2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import java.io.FileNotFoundException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Objects;
import java.util.function.Function;

/** Per-render-target animation ownership with local-first default fallback. */
public final class AnimationStore extends Object2ReferenceOpenHashMap<String, Animation>
        implements AutoCloseable {
    private final AnimationSource source;
    private final AnimationBinder binder;
    private final AnimationStore fallback;
    private final Function<String, ModelResourceFailureGate> failureGates;
    // 惰性加载会在运行期写入，读路径（每帧每控制器一次）必须无锁；写只在 synchronized 的 load 里。
    private final Map<String, Slot> slots = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public AnimationStore() {
        this(null, null, null, ignored -> ModelResourceFailureGate.none());
    }

    private AnimationStore(AnimationSource source, AnimationBinder binder,
                           AnimationStore fallback,
                           Function<String, ModelResourceFailureGate> failureGates) {
        this.source = source;
        this.binder = binder;
        this.fallback = fallback;
        this.failureGates = failureGates;
    }

    public static AnimationStore lazy(Iterable<String> names,
                                      AnimationSource source,
                                      AnimationBinder binder) {
        return lazy(names, source, binder, null);
    }

    public static AnimationStore lazy(Iterable<String> names,
                                      AnimationSource source,
                                      AnimationBinder binder,
                                      AnimationStore fallback) {
        return lazy(names, source, binder, fallback,
                ignored -> ModelResourceFailureGate.none());
    }

    public static AnimationStore lazy(Iterable<String> names,
                                      AnimationSource source,
                                      AnimationBinder binder,
                                      AnimationStore fallback,
                                      Function<String, ModelResourceFailureGate> failureGates) {
        var result = new AnimationStore(Objects.requireNonNull(source, "source"),
                Objects.requireNonNull(binder, "binder"), fallback,
                Objects.requireNonNull(failureGates, "failureGates"));
        for (var name : names) {
            Objects.requireNonNull(name, "animation name");
            if (result.containsLocal(name)) {
                throw new IllegalArgumentException("Duplicate animation name: " + name);
            }
            result.put(name, null);
            result.slots.put(name, new Slot(State.UNLOADED, null));
        }
        return result;
    }

    public static AnimationStore eager(Map<String, Animation> animations) {
        var result = new AnimationStore();
        animations.forEach((name, animation) -> {
            var checkedName = Objects.requireNonNull(name, "animation name");
            var checkedAnimation = Objects.requireNonNull(animation, "animation");
            result.put(checkedName, checkedAnimation);
            result.slots.put(checkedName, new Slot(State.READY, checkedAnimation));
        });
        return result;
    }

    @Override
    public Animation get(Object key) {
        requireOpen();
        if (!(key instanceof String name)) {
            return fallback == null ? null : fallback.get(key);
        }
        var slot = slots.get(name);
        if (slot == null) {
            return fallback == null ? null : fallback.get(key);
        }
        // 已就绪（或本来就没有加载源）时完全无锁，避免所有玩家共享默认动画集时
        // 每帧每控制器都在同一个监视器上排队。
        if (slot.state() == State.READY || source == null) {
            return slot.animation();
        }
        return load(name);
    }

    private synchronized Animation load(String name) {
        var slot = slots.get(name);
        if (slot == null) {
            return fallback == null ? null : fallback.get(name);
        }
        if (slot.state() == State.READY || source == null) {
            return slot.animation();
        }
        if (slot.state() == State.FAILED) {
            return fallback == null ? null : fallback.get(name);
        }
        var failureGate = Objects.requireNonNull(failureGates.apply(name), "failure gate");
        if (failureGate.failure().isPresent()) {
            slots.put(name, new Slot(State.FAILED, null));
            return fallback == null ? null : fallback.get(name);
        }
        try {
            var proto = source.load(name);
            if (proto == null) {
                failureGate.fail(new FileNotFoundException(
                        "Baked animation payload is missing: " + name));
                slots.put(name, new Slot(State.FAILED, null));
                return fallback == null ? null : fallback.get(name);
            }
            var value = Objects.requireNonNull(binder.bind(proto), "bound animation");
            super.put(name, value);
            slots.put(name, new Slot(State.READY, value));
            // 绑定（含契约校验）已经完成，原始 proto 不再需要；留着就是把懒加载省下的内存又占回去。
            source.release(name);
            return value;
        } catch (Exception error) {
            failureGate.fail(error);
            slots.put(name, new Slot(State.FAILED, null));
            return fallback == null ? null : fallback.get(name);
        }
    }

    @Override
    public Animation getOrDefault(Object key, Animation defaultValue) {
        var value = get(key);
        return value == null ? defaultValue : value;
    }

    @Override
    public synchronized boolean containsKey(Object key) {
        requireOpen();
        return containsLocal(key) || fallback != null && fallback.containsKey(key);
    }

    private boolean containsLocal(Object key) {
        return slots.containsKey(key);
    }

    @Override
    public synchronized ObjectSet<String> keySet() {
        requireOpen();
        if (fallback == null) {
            return super.keySet();
        }
        var names = new ObjectOpenHashSet<>(super.keySet());
        names.addAll(fallback.keySet());
        return ObjectSets.unmodifiable(names);
    }

    public synchronized State state(String name) {
        requireOpen();
        var slot = slots.get(name);
        return slot == null ? null : slot.state();
    }

    public synchronized boolean hasFailures() {
        requireOpen();
        return slots.values().stream().anyMatch(slot -> slot.state() == State.FAILED);
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Animation store is closed");
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        slots.clear();
        super.clear();
    }

    @FunctionalInterface
    public interface AnimationSource {
        com.kinetic.proto.mixel.asset.model.data.Animation load(String animationName) throws Exception;

        /**
         * 绑定成功后释放该动画的原始 proto。
         *
         * <p>默认模型的 352 个动画以 proto 形式常驻，只有真正绑定过的那几个才需要留下解析结果；
         * 但惰性绑定会把整张 proto 表一直挂在 source 上，等于"懒加载省下的内存又按原样留着"。
         * 成功绑定后就不再需要原文，可以整条丢掉。落盘缓存那种按需读文件的 source 是空实现。
         */
        default void release(String animationName) {
        }
    }

    @FunctionalInterface
    public interface AnimationBinder {
        Animation bind(com.kinetic.proto.mixel.asset.model.data.Animation animation) throws Exception;
    }

    public enum State {
        UNLOADED,
        READY,
        FAILED
    }

    private record Slot(State state, Animation animation) {
    }
}
