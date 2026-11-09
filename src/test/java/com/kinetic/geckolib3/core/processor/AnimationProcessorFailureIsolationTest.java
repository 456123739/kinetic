package com.kinetic.geckolib3.core.processor;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 控制器求值的故障隔离护栏。
 *
 * <p>谓词每帧、每个实体都跑一次，其中一半代码在碰第三方模组的反射/状态查询。任何一次
 * 抛出的 {@code RuntimeException} 都会直接冒到渲染帧里，把整个游戏打崩——而这类错误本来
 * 只该影响一个控制器的一帧。这里从源码上钉住三点：包在 try 里、只吞 RuntimeException
 * （Error 必须继续冒泡）、日志有限流。
 */
class AnimationProcessorFailureIsolationTest {
    private static final Path SOURCE =
            Path.of("src/main/java/com/kinetic/geckolib3/core/processor/AnimationProcessor.java");

    @Test
    void controllerFailureIsContainedAndLoggedAtMostOncePerInterval() throws Exception {
        assertTrue(Files.isRegularFile(SOURCE), "找不到 " + SOURCE.toAbsolutePath());
        String text = Files.readString(SOURCE, StandardCharsets.UTF_8);
        int call = text.indexOf("controller.process(event, evaluator, allowEmitting)");
        assertTrue(call > 0, "必须保留 controller.process 调用");

        String before = text.substring(Math.max(0, call - 400), call);
        assertTrue(before.contains("try {"), "controller.process 必须包在 try 里，否则谓词异常会崩掉整个渲染帧");

        String after = text.substring(call, Math.min(text.length(), call + 900));
        assertTrue(after.contains("catch (RuntimeException failure)"),
                "只能吞 RuntimeException：Error（OOM/栈溢出）继续冒泡才是正确行为");
        assertTrue(after.contains("lastControllerErrorTicks"),
                "异常日志必须限流，否则坏掉的谓词会每帧刷一条日志");
        assertTrue(after.contains("skipping this frame"), "日志要说明这一帧被跳过，便于定位");
    }
}
