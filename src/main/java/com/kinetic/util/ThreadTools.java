package com.kinetic.util;

import java.util.concurrent.*;

public final class ThreadTools {
    /** 队列容量：足够让 worker 一直有活干，又不至于在掉帧时堆积出可见的内存。 */
    private static final int QUEUE_CAPACITY = Math.max(64, Runtime.getRuntime().availableProcessors() * 16);

    private static final ThreadPoolExecutor THREAD_POOL = new ThreadPoolExecutor(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            30,
            TimeUnit.SECONDS,
            // 有界队列：渲染线程每帧给每个可动画实体提交一次更新，机器慢时旧实现的无界队列会
            // 无限堆积任务（每个任务都持有实体与渲染上下文），表现为"游玩越久内存越高、越卡"。
            // 队列满时 CallerRuns 让提交线程就地执行：动画仍然更新，只是这一帧变慢，不会丢更新。
            new LinkedBlockingQueue<>(QUEUE_CAPACITY),
            runnable -> {
                Thread thread = new Thread(runnable, "KINETIC Worker");
                thread.setPriority(Thread.NORM_PRIORITY);
                thread.setDaemon(true);
                return thread;
            });
    static {
        THREAD_POOL.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    }

    public static Future<?> submit(Runnable runnable) {
        return THREAD_POOL.submit(runnable);
    }

    public static <T> Future<T> submit(Callable<T> runnable) {
        return THREAD_POOL.submit(runnable);
    }

    public static boolean safeSleep(int millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }
}
