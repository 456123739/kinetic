package com.kinetic.client.animation;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 匍匐侧躺的接线护栏。
 *
 * <p>状态机与数据契约都有自己的测试，但"频道到底有没有注册、是不是排在 use/swing 之后、
 * 谓词有没有去问状态机"这几件事只能从产物上确认。这里扫常量池 + 源码顺序，
 * 与 {@link SlideLockWiringTest} 同一类护栏。
 */
class ProneWiringTest {
    private static final String PRONE_ACTION = "com/kinetic/client/animation/ProneAction";
    private static final String PREDICATE = "com/kinetic/client/animation/predicate/PronePredicate";

    @Test
    void playerModelRegistersTheProneChannel() throws Exception {
        String pool = constantPool("com/kinetic/client/controller/collections/PlayerControllerCollection");
        // 控制器名是 String.format("player.%s", "prone") 拼出来的，常量池里只有 "prone"。
        assertTrue(pool.contains("prone"), "玩家模型必须注册 prone 频道");
        assertTrue(pool.contains(PREDICATE), "player.prone 必须用 PronePredicate 选动画");
    }

    @Test
    void proneChannelRunsAfterTheChannelsItOverrides() throws Exception {
        File source = new File("src/main/java/com/kinetic/client/controller/collections/PlayerControllerCollection.java");
        assertTrue(source.isFile(), "找不到 " + source.getAbsolutePath());
        String text = Files.readString(source.toPath(), StandardCharsets.UTF_8);
        int use = text.indexOf("simple(\"use\"");
        int swing = text.indexOf("simple(\"swing\"");
        int prone = text.indexOf("simple(\"prone\"");
        assertTrue(use > 0 && swing > 0 && prone > 0, "三个频道都必须注册");
        assertTrue(prone > use && prone > swing,
                "player.prone 必须排在 use/swing 之后：它是同一帧里最后写躯干/头/手臂的频道，"
                        + "排在前面就会被站姿动画顶回'头朝下'");
    }

    @Test
    void predicateConsultsTheStateMachineAndFollowsOtherChannels() throws Exception {
        String pool = constantPool("com/kinetic/client/animation/predicate/PronePredicate");
        assertTrue(pool.contains(PRONE_ACTION), "PronePredicate 必须读取 ProneAction 状态");
        for (String controller : new String[]{"player.use", "player.fire", "player.hold_mainhand"}) {
            assertTrue(pool.contains(controller),
                    "PronePredicate 必须按 " + controller + " 当前在播的动画判断动作种类");
        }
        assertTrue(pool.contains("prone:action:idle"), "必须存在通用回退动画名");
    }

    @Test
    void molangExposesTheRollVariable() throws Exception {
        String pool = constantPool("com/kinetic/client/animation/molang/KineticBinding");
        assertTrue(pool.contains("prone_roll"), "KineticBinding 必须暴露 kinetic.prone_roll");
        assertTrue(pool.contains(PRONE_ACTION), "kinetic.prone_roll 必须由 ProneAction 提供");
    }

    @Test
    void controllersCanReportTheAnimationTheyArePlaying() throws Exception {
        String pool = constantPool("com/kinetic/geckolib3/core/controller/CodedAnimationController");
        assertTrue(pool.contains("getCurrentAnimationName"),
                "coded 控制器必须实现 getCurrentAnimationName（匍匐侧躺据此识别动作）");
    }

    @Test
    void proneSwingIsSelectedByTheSwingChannel() throws Exception {
        String pool = constantPool("com/kinetic/client/animation/predicate/SwingPredicate");
        assertTrue(pool.contains("prone:swing"), "SwingPredicate 必须能在趴下时切到 prone:swing");
        assertTrue(pool.contains(PRONE_ACTION), "SwingPredicate 必须用 ProneAction.isProne 判断趴姿");
    }

    private static String constantPool(String className) throws Exception {
        var resource = "/" + className + ".class";
        try (var in = ProneWiringTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "类文件不在测试类路径上: " + resource);
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}
