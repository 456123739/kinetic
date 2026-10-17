package com.kinetic.client.compat.tacz.lod;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 动画 LOD 允许表验证。用的是 KINETIC 内置的<b>真实</b> {@code tac.animation.json} 里的动画名，
 * 不是手编的样例——先断言这些名字确实存在于文件中，再断言分类结果。
 */
class GunAnimationLodTest {
    /** 超出距离后必须屏蔽：换弹、攀爬、近战、开火。 */
    private static final String[] MUST_SUPPRESS = {
            "tac:reload:pistol", "tac:reload:rifle", "tac:reload:rpg",
            "tac:climb:pistol", "tac:climb:rifle", "tac:climb:rpg",
            "tac:climbing:pistol", "tac:climbing:rifle",
            "tac:climbing:fire:pistol", "tac:climbing:fire:rifle",
            "tac:melee:pistol", "tac:melee:rifle", "tac:melee:rpg",
            "tac:aim:fire:pistol", "tac:aim:fire:rifle", "tac:aim:fire:rpg",
            "tac:hold:fire:pistol", "tac:hold:fire:rifle", "tac:hold:fire:rpg",
    };

    /** 超出距离后必须保留的基本姿态：站立、行走、跑动、持枪、瞄准、手雷持有。 */
    private static final String[] MUST_ALLOW = {
            "tac:idle", "tac:walk", "tac:run",
            "tac:run:pistol", "tac:run:rifle", "tac:run:rpg",
            "tac:hold:pistol", "tac:hold:rifle", "tac:hold:rpg",
            "tac:aim:pistol", "tac:aim:rifle", "tac:aim:rpg",
            "tac:mainhand:grenade", "tac:offhand:grenade",
    };

    @Test
    void suppressesStateMachineAnimationsButKeepsBasicPoses() throws Exception {
        Set<String> real = realTacAnimationNames();

        for (String name : MUST_SUPPRESS) {
            assertTrue(real.contains(name), "期望屏蔽的名字必须真实存在于 tac.animation.json: " + name);
            assertFalse(GunAnimationLod.isAllowed(name, true), "超出距离应屏蔽: " + name);
        }
        for (String name : MUST_ALLOW) {
            assertTrue(real.contains(name), "期望保留的名字必须真实存在于 tac.animation.json: " + name);
            assertTrue(GunAnimationLod.isAllowed(name, true), "超出距离应保留: " + name);
        }
    }

    /** 距离内一律放行；null / 空名也放行（不能因为拿不到名字就卡住动画）。 */
    @Test
    void allowsEverythingWithinDistance() {
        assertTrue(GunAnimationLod.isAllowed("tac:reload:pistol", false));
        assertTrue(GunAnimationLod.isAllowed("tac:aim:fire:rifle", false));
        assertTrue(GunAnimationLod.isAllowed(null, true));
        assertTrue(GunAnimationLod.isAllowed("", true));
    }

    /** 真实动画集里，被屏蔽的必须全部是开火/换弹/攀爬/近战，不能误伤基本姿态。 */
    @Test
    void doesNotSuppressAnythingUnexpected() throws Exception {
        for (String name : realTacAnimationNames()) {
            if (GunAnimationLod.isAllowed(name, true)) {
                continue;
            }
            var expected = name.contains("fire") || name.startsWith("tac:reload:")
                    || name.startsWith("tac:climb") || name.startsWith("tac:melee:");
            assertTrue(expected, "被屏蔽但不是预期的状态机动画: " + name);
        }
    }

    private static Set<String> realTacAnimationNames() throws Exception {
        try (var in = GunAnimationLodTest.class.getResourceAsStream(
                "/assets/kinetic/builtin/misc/2_steve/animations/tac.animation.json")) {
            assertNotNull(in, "内置 tac.animation.json 不在测试类路径上");
            var text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            JsonObject animations = new Gson().fromJson(text, JsonObject.class)
                    .getAsJsonObject("animations");
            assertNotNull(animations, "animations 节点缺失");
            var names = new HashSet<String>();
            animations.keySet().forEach(names::add);
            assertFalse(names.isEmpty(), "动画集为空");
            return names;
        }
    }
}
