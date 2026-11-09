package com.kinetic.geckolib3.core.processor;

import com.kinetic.geckolib3.core.controller.IAnimationController;
import com.kinetic.geckolib3.core.controller.IBoneAnimationQueue;
import com.kinetic.geckolib3.core.event.predicate.AnimationEvent;
import com.kinetic.geckolib3.core.molang.context.MolangContext;
import com.kinetic.geckolib3.core.molang.storage.MolangMemory;
import com.kinetic.geckolib3.model.AnimatableEntity;
import com.kinetic.molang.runtime.ExpressionEvaluator;
import it.unimi.dsi.fastutil.objects.Object2ReferenceMap;
import com.kinetic.geckolib3.core.snapshot.BoneTopLevelSnapshot;
import com.kinetic.geckolib3.core.molang.value.IValue;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    /** 一个只会抛异常的最小控制器：其余方法都是空实现。 */
    private static IAnimationController<AnimatableEntity<?>> exploding(Throwable thrown) {
        return new IAnimationController<>() {
            @Override public String getName() { return "exploding"; }
            @Override public String getStateName() { return "exploding"; }
            @Override public void updateModel(List<BoneTopLevelSnapshot> bones,
                                              Object2ReferenceMap<String, List<IValue>> handlers) { }
            @Override public void process(AnimationEvent<AnimatableEntity<?>> event,
                                          ExpressionEvaluator<MolangContext<?>> evaluator,
                                          boolean allowEmitting) {
                if (thrown instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (thrown instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException(thrown);
            }
            @Override public void visitBoneAnimationQueues(Consumer<IBoneAnimationQueue> visitor) { }
            @Override public void clear() { }
        };
    }

    @Test
    void runtimeExceptionIsCapturedAsAReturnValue() {
        RuntimeException captured = AnimationProcessor.tryProcess(
                exploding(new IllegalStateException("boom")), null, null, true);
        assertNotNull(captured, "RuntimeException 必须被收成返回值，否则会冒到渲染帧把游戏打崩");
        assertEquals("boom", captured.getMessage());
    }

    @Test
    void errorsStillPropagate() {
        assertThrows(OutOfMemoryError.class, () -> AnimationProcessor.tryProcess(
                        exploding(new OutOfMemoryError("oom")), null, null, true),
                "Error 不能被吞：OOM/栈溢出必须继续冒泡，否则进程会在不可用状态下继续跑");
    }

    @Test
    void theRenderLoopCallsThroughTheGuardedHelperAndRateLimitsTheLog() throws Exception {
        String text = Files.readString(SOURCE, StandardCharsets.UTF_8);
        int call = text.indexOf("tryProcess(controller, event, evaluator, allowEmitting)");
        assertTrue(call > 0, "渲染循环必须通过 tryProcess 跑控制器，而不是直接 controller.process");
        String after = text.substring(call, Math.min(text.length(), call + 700));
        assertTrue(after.contains("lastControllerErrorTicks"),
                "失败日志必须限流，否则坏掉的谓词会每帧刷一条");
        assertTrue(after.contains("skipping this frame"), "日志要说明这一帧被跳过，便于定位");
    }

}
