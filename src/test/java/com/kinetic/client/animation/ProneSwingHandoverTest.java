package com.kinetic.client.animation;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨姿态接力：趴着挥到一半站起来（或反过来），动作要从**同一进度**接着播。
 *
 * <p>这是用户点名要的行为。没有它的时候，控制器会换到另一段动画并从第 0 帧开始，
 * 观感是"抬到一半的胳膊被拍回去再重新抬"。护栏分两段：
 * <ol>
 *   <li>谓词确实按进度走，而不是直接 {@code playAnimation}；</li>
 *   <li>进度是从"当前动画已播 tick / 当前动画长度"算出来的，不是写死的。</li>
 * </ol>
 */
class ProneSwingHandoverTest {
    private static final File SWING_PREDICATE =
            new File("src/main/java/com/kinetic/client/animation/predicate/SwingPredicate.java");
    private static final File PREDICATE_API =
            new File("src/main/java/com/kinetic/client/animation/predicate/IAnimationPredicate.java");
    private static final File PLAYER =
            new File("src/main/java/com/kinetic/geckolib3/core/controller/AnimationPlayer.java");

    @Test
    void swingUsesProgressPreservingHandover() throws Exception {
        String text = Files.readString(SWING_PREDICATE.toPath(), StandardCharsets.UTF_8);
        assertTrue(text.contains("playAnimationSeeked"),
                "SwingPredicate 必须用 playAnimationSeeked 交接，否则跨姿态会从头重播");
        assertTrue(text.contains("swingProgress"),
                "SwingPredicate 必须按当前进度交接");
        assertTrue(text.contains("getAnimationTicks()") && text.contains("getAnimationLength("),
                "进度必须由\"已播 tick / 动画长度\"算出来");
    }

    @Test
    void seekedPlaybackOnlySeeksWhenTheAnimationChanges() throws Exception {
        String text = Files.readString(PREDICATE_API.toPath(), StandardCharsets.UTF_8);
        int helper = text.indexOf("playAnimationSeeked");
        assertTrue(helper > 0, "缺少 playAnimationSeeked");
        int guard = text.indexOf("!animationName.equals(controller.getCurrentAnimationName())", helper);
        assertTrue(guard > helper,
                "同一段动画重复请求时不能 seek——那会把正在播的动作拽回中间");
    }

    @Test
    void animationPlayerAppliesAPendingSeekWhenTheNextAnimationStarts() throws Exception {
        String text = Files.readString(PLAYER.toPath(), StandardCharsets.UTF_8);
        assertTrue(text.contains("pendingSeekTicks"), "AnimationPlayer 必须记住待应用的跳转");
        int start = text.indexOf("this.animTickOffset = renderTicks;");
        int apply = text.indexOf("offsetForSeek(renderTicks, seek)", start);
        assertTrue(apply > start, "新动画开始的那一处必须应用 seek，否则跳转永远不生效");
        int clamp = text.indexOf("this.currentAnim.animationLength - 0.01f", start);
        assertTrue(clamp > start, "seek 必须夹在动画长度内，否则 PLAY_ONCE 会当场结束");
    }
}
