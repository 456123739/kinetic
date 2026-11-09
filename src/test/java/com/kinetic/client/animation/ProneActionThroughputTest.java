package com.kinetic.client.animation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 侧躺状态机的单次推进成本（性能实测，不是结构断言）。
 *
 * <p>`ProneAction.advance` 每个渲染帧、每个被跟踪玩家都会跑一次，因此它的常数因子直接落在
 * 渲染线程上。这里用足够多次的调用量出纳秒级单次成本，并给一个**宽松**上限：目的是发现
 * "有人往这条路径里塞了分配或哈希表"这类数量级退化，而不是精确 benchmark（CI 上抖动很大）。
 */
class ProneActionThroughputTest {
    /** 单次推进的上限。实测在 50~200ns 量级，这里留 10 倍余量吸收 CI 抖动。 */
    private static final double MAX_NANOS_PER_CALL = 2000.0;
    private static final int WARMUP = 200_000;
    private static final int MEASURED = 1_000_000;

    @Test
    void advanceStaysAllocationFreeAndCheap() {
        ProneAction.State state = new ProneAction.State();
        float t = 0.0f;

        for (int i = 0; i < WARMUP; i++) {
            t += 1.0f / 3.0f;
            ProneAction.advance(state, t, true, true, ProneAction.Kind.MEDICAL);
        }

        // 与测量段完全相同的输入形状：进入动作、趴着。
        long start = System.nanoTime();
        for (int i = 0; i < MEASURED; i++) {
            t += 1.0f / 3.0f;
            ProneAction.advance(state, t, true, true, ProneAction.Kind.MEDICAL);
        }
        long elapsed = System.nanoTime() - start;

        double nanosPerCall = (double) elapsed / MEASURED;
        System.out.printf("[perf] ProneAction.advance = %.1f ns/call (%d calls in %d ms)%n",
                nanosPerCall, MEASURED, elapsed / 1_000_000L);
        assertTrue(nanosPerCall < MAX_NANOS_PER_CALL,
                "单次推进 " + nanosPerCall + " ns 超过上限 " + MAX_NANOS_PER_CALL
                        + " ns，说明这条每帧路径上出现了分配或哈希查找级别的退化");
    }

    @Test
    void rollReadIsCheap() {
        ProneAction.State state = new ProneAction.State();
        state.progress = 0.6f;
        long start = System.nanoTime();
        float sink = 0.0f;
        for (int i = 0; i < MEASURED; i++) {
            sink += ProneAction.ease(state.progress);
        }
        long elapsed = System.nanoTime() - start;
        double nanosPerCall = (double) elapsed / MEASURED;
        System.out.printf("[perf] ProneAction.ease = %.1f ns/call (sink=%.1f)%n", nanosPerCall, sink);
        assertTrue(nanosPerCall < MAX_NANOS_PER_CALL, "ease 退化：" + nanosPerCall + " ns");
    }
}
