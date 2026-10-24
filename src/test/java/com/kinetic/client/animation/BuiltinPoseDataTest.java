package com.kinetic.client.animation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内置模型动画数据的关键约定。
 *
 * <p>这些问题只在实机动作里看得出来，但根因都在数据上，可以离线钉住：
 * <ul>
 *   <li>{@code swing:sword} 的地面/空中分支必须<b>实时</b>按 {@code q.is_on_ground} 判定。
 *       旧写法把 {@code q.is_jumping} 存进 {@code v.jump}，只在动画第一帧算一次；控制器复用
 *       同一段动画时这个值会滞留，于是地面挥砍出跳劈、空中挥砍出地面横扫。</li>
 *   <li>打药姿势：副手前臂伸直（0），主手前臂弯曲，两个动作互为镜像。</li>
 * </ul>
 */
class BuiltinPoseDataTest {
    private static final String[] MODELS = {"2_steve", "1_alex"};

    private static JsonObject animations(String model) throws Exception {
        var path = "/assets/kinetic/builtin/misc/" + model + "/animations/arm.animation.json";
        try (var in = BuiltinPoseDataTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "缺少内置动画: " + path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("animations");
        }
    }

    @Test
    void swordSwingPicksItsVariantFromALiveGroundQuery() throws Exception {
        for (String model : MODELS) {
            JsonObject swing = animations(model).getAsJsonObject("swing:sword");
            String bones = swing.getAsJsonObject("bones").toString();
            assertFalse(bones.contains("v.jump"),
                    model + ": 骨骼表达式不得再依赖第一帧写入的 v.jump（会滞留）");
            assertTrue(bones.contains("q.is_on_ground"),
                    model + ": 地面/空中分支必须每帧按 q.is_on_ground 求值");
            assertEquals(6, swing.getAsJsonObject("bones").size(),
                    model + ": 挥砍只驱动躯干、头和持刀手，不得写腿部骨骼");
            for (String leg : List.of("LeftLeg", "RightLeg", "LeftFoot", "RightFoot", "DownBody")) {
                assertFalse(swing.getAsJsonObject("bones").has(leg),
                        model + ": 挥砍动画不能覆盖腿部 " + leg);
            }
        }
    }

    @Test
    void medicalPoseKeepsTheOffHandStraightAndTheMainHandBent() throws Exception {
        for (String model : MODELS) {
            var animations = animations(model);
            JsonObject main = animations.getAsJsonObject("use_mainhand:medical").getAsJsonObject("bones");
            JsonObject off = animations.getAsJsonObject("use_offhand:medical").getAsJsonObject("bones");

            assertPose(model + " 主手打药", main, "LeftForeArm", "RightForeArm");
            assertPose(model + " 副手打药", off, "RightForeArm", "LeftForeArm");

            JsonArray mainArm = rotationAt(main, "LeftArm", "0.62");
            JsonArray offArm = rotationAt(off, "RightArm", "0.62");
            assertEquals(mainArm.get(0).getAsDouble(), offArm.get(0).getAsDouble(), 1e-6,
                    model + ": 两个动作必须是镜像");
            assertEquals(-mainArm.get(1).getAsDouble(), offArm.get(1).getAsDouble(), 1e-6);
            assertEquals(-mainArm.get(2).getAsDouble(), offArm.get(2).getAsDouble(), 1e-6);
        }
    }

    private static void assertPose(String label, JsonObject bones,
                                   String offHandBone, String mainHandBone) {
        assertEquals(0.0, rotationAt(bones, offHandBone, "0.62").get(0).getAsDouble(), 1e-6,
                label + ": 副手前臂必须伸直");
        assertTrue(Math.abs(rotationAt(bones, mainHandBone, "0.62").get(0).getAsDouble()) > 30.0,
                label + ": 主手前臂必须明显弯曲");
    }

    private static JsonArray rotationAt(JsonObject bones, String bone, String time) {
        JsonObject rotation = bones.getAsJsonObject(bone).getAsJsonObject("rotation");
        JsonElement frame = rotation.get(time);
        if (frame == null) {
            // 静态通道：rotation 本身就是数组
            return rotation.getAsJsonArray();
        }
        JsonElement post = frame.getAsJsonObject().get("post");
        return (post != null ? post : frame).getAsJsonArray();
    }
}
