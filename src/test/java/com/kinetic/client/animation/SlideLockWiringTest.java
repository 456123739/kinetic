package com.kinetic.client.animation;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 滑铲锁定的接线护栏。
 *
 * <p>{@link SlideLock} 的状态机本身有逐 tick 单测，姿态合成有 FK 核验脚本；但"谓词到底有没有
 * 问过它"只能从字节码上确认。这里直接扫描常量池：每个必须让位的频道都必须引用 {@code SlideLock}，
 * 否则改回"只播滑铲、不拦持枪"时不会有任何测试失败，实机才会发现又被顶成头朝前。
 *
 * <p>这是与 {@code TaczCompatIsolationTest} 同一类护栏：扫描编译产物，不依赖运行期状态。
 */
class SlideLockWiringTest {
    private static final String SLIDE_LOCK = "com/kinetic/client/animation/SlideLock";

    @Test
    void everyChannelThatCanOverrideTheSlideAsksTheLock() throws Exception {
        // 主频道让位、跑酷频道续播、hold 固定为静止持枪、三条一次性频道停止。
        for (String className : new String[]{
                "com/kinetic/client/compat/parcool/ParCoolPredicate",
                "com/kinetic/client/animation/predicate/PlayerMainPredicate",
                "com/kinetic/client/animation/predicate/MainhandPredicate",
                "com/kinetic/client/animation/predicate/GunFirePredicate",
                "com/kinetic/client/animation/predicate/SwingPredicate",
                "com/kinetic/client/animation/predicate/UsePredicate"}) {
            assertTrue(constantPool(className).contains(SLIDE_LOCK),
                    className + " 必须引用 SlideLock，否则该频道会绕过滑铲锁定");
        }
    }

    @Test
    void processorCapturesAndRestoresTheLockedBones() throws Exception {
        var pool = constantPool("com/kinetic/geckolib3/core/processor/AnimationProcessor");
        assertTrue(pool.contains(SLIDE_LOCK), "AnimationProcessor 必须读取锁定状态");
        assertTrue(pool.contains("player.parcool"),
                "AnimationProcessor 必须按跑酷控制器的名字定位抓取时机");
        assertTrue(pool.contains("captureSlideLock") && pool.contains("restoreSlideLock"),
                "抓取与回写两个步骤都必须存在");
    }

    private static String constantPool(String className) throws Exception {
        var resource = "/" + className + ".class";
        try (var in = SlideLockWiringTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "类文件不在测试类路径上: " + resource);
            // 常量池里的类名/方法名是 ASCII，用 ISO-8859-1 可原样保留以便子串查找。
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}
