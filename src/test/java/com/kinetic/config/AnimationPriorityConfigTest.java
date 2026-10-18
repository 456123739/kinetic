package com.kinetic.config;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimationPriorityConfigTest {
    @Test
    void shippedTableParsesAndKeepsParcoolSlideAboveTaczCrawl() throws Exception {
        var stream = AnimationPriorityConfigTest.class.getResourceAsStream(
                "/serverconfig/kinetic_animation_priority.toml");
        assertNotNull(stream, "随模组发布的默认优先级表必须存在");
        final List<String> entries;
        try (var reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            entries = AnimationPriorityConfig.flatten(reader);
        }
        assertTrue(entries.contains("parcool_slide=1200"), entries.toString());
        assertTrue(entries.contains("tacz_crawl=700"), entries.toString());

        AnimationPriorityConfig.refresh(entries);
        assertTrue(priority("parcool:sliding") > priority("tac:climb:rifle"),
                "Parcool 滑铲必须高于 TACZ 爬行");
        assertTrue(priority("parcool:sliding") > priority("tac:climbing:rifle"));
    }

    @Test
    void shippedDefaultsReproduceTheBuiltinOrder() throws Exception {
        var stream = Objects.requireNonNull(AnimationPriorityConfigTest.class
                .getResourceAsStream("/serverconfig/kinetic_animation_priority.toml"));
        final List<String> entries;
        try (var reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            entries = AnimationPriorityConfig.flatten(reader);
        }
        AnimationPriorityConfig.refresh(entries);
        // 默认值与内置档位权重一致：保持默认时不会改变原有判断结果。
        assertEquals(600, priority("attacked"));
        assertEquals(600, priority("sneak"));
        assertEquals(600, priority("sneaking"));
        assertEquals(400, priority("walk"));
        assertEquals(200, priority("idle"));
    }

    @Test
    void aliasesExactIdsPrefixesAndFallbackResolve() {
        AnimationPriorityConfig.refresh(List.of(
                "parcool_slide=1200",
                "tacz_crawl=700",
                "\"parcool:wall_slide_left\"=900",
                "tac:aim:=800",
                "walk=1"));

        assertEquals(1200, priority("parcool:sliding"));
        assertEquals(700, priority("tac:climb:rifle"));
        assertEquals(700, priority("tac:climbing:pistol"));
        assertEquals(900, priority("parcool:wall_slide_left"));
        assertEquals(800, priority("tac:aim:rifle"));
        assertEquals(1, priority("walk"));
        // 未配置的动画回退到调用方传入的内置权重
        assertEquals(1000, AnimationPriorityConfig.priorityFor("death", 1000));
        assertEquals(42, AnimationPriorityConfig.priorityFor(null, 42));
    }

    @Test
    void malformedEntriesAreIgnoredInsteadOfFailing() {
        AnimationPriorityConfig.refresh(List.of(
                "walk=not-a-number",
                "no-separator",
                "=10",
                "idle=10"));
        assertEquals(10, priority("idle"));
        assertEquals(777, AnimationPriorityConfig.priorityFor("walk", 777));
    }

    @Test
    void slideOutranksCrawlFollowsTheTable() {
        // 持枪与开火两条频道都靠这个结论决定 Pose.SWIMMING 归谁；配置反转时必须跟着反转，
        // 否则开火仍会把跑酷滑铲顶成 TACZ 爬行。
        AnimationPriorityConfig.refresh(List.of("parcool_slide=1200", "tacz_crawl=700"));
        assertTrue(AnimationPriorityConfig.slideOutranksCrawl());

        AnimationPriorityConfig.refresh(List.of("parcool_slide=600", "tacz_crawl=700"));
        assertFalse(AnimationPriorityConfig.slideOutranksCrawl());

        AnimationPriorityConfig.refresh(List.of("parcool_slide=700", "tacz_crawl=700"));
        assertFalse(AnimationPriorityConfig.slideOutranksCrawl(), "同优先级时不应抢走爬行姿态");
    }

    private static int priority(String animationName) {
        return AnimationPriorityConfig.priorityFor(animationName, -1);
    }
}
