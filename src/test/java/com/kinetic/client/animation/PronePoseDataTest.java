package com.kinetic.client.animation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kinetic.client.animation.molang.CustomMolangParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 匍匐专用动画的数据契约。
 *
 * <p>这些约定一旦破掉，实机上就是"翻到一半跳一下""头又开始扎地"或者"腿被冻住"，
 * 但都能离线钉住：
 * <ul>
 *   <li>{@code prone:action:*} 的每个通道都必须带 {@code kinetic.prone_roll} 的混合——
 *       否则 roll=0 时它不再等于底层动画的输出，进出侧躺会跳变；</li>
 *   <li>它们不许写 {@code Root / AllBody / DownBody / 腿 / 手}：下肢与根由主频道独占，
 *       手由原动作动画负责；</li>
 *   <li>{@code bone_rot('X')} 里的骨骼名必须真的存在于该模型；</li>
 *   <li>{@code tac:climb*} 不许再写下肢与根——那是"开火把腿部爬行冻住"的根因。</li>
 * </ul>
 */
class PronePoseDataTest {
    private static final String[] MODELS = {"2_steve", "1_alex"};

    /** 侧躺动画禁止写的骨骼。 */
    private static final Set<String> FORBIDDEN = Set.of(
            "Root", "DownBody",
            "LeftHand", "RightHand", "LeftHandLocator", "RightHandLocator");

    /** 用于验证 bone_rot 目标实在的骨骼集合来源：主模型几何。 */
    private static Set<String> modelBones(String model) throws Exception {
        String path = "/assets/kinetic/builtin/misc/" + model + "/models/main.json";
        JsonObject root;
        try (var in = PronePoseDataTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "缺少模型: " + path);
            root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        Set<String> names = new TreeSet<>();
        collect(root.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject()
                .getAsJsonArray("bones"), names);
        return names;
    }

    private static void collect(JsonArray bones, Set<String> out) {
        for (JsonElement element : bones) {
            JsonObject bone = element.getAsJsonObject();
            out.add(bone.get("name").getAsString());
            if (bone.has("children")) {
                collect(bone.getAsJsonArray("children"), out);
            }
        }
    }

    private static JsonObject animations(String model, String file) throws Exception {
        String path = "/assets/kinetic/builtin/misc/" + model + "/animations/" + file;
        try (var in = PronePoseDataTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "缺少动画文件: " + path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("animations");
        }
    }

    /** 收集一个动画里所有 "字符串形式" 的通道值（molang 表达式）。 */
    private static List<String> expressions(JsonObject animation) {
        List<String> out = new ArrayList<>();
        for (var bone : animation.getAsJsonObject("bones").entrySet()) {
            for (var channel : bone.getValue().getAsJsonObject().entrySet()) {
                JsonElement value = channel.getValue();
                if (value.isJsonArray()) {
                    collectStrings(value.getAsJsonArray(), out);
                } else if (value.isJsonObject()) {
                    for (var key : value.getAsJsonObject().entrySet()) {
                        JsonElement v = key.getValue();
                        if (v.isJsonArray()) {
                            collectStrings(v.getAsJsonArray(), out);
                        } else if (v.isJsonObject() && v.getAsJsonObject().has("post")) {
                            collectStrings(v.getAsJsonObject().getAsJsonArray("post"), out);
                        }
                    }
                }
            }
        }
        return out;
    }

    private static void collectStrings(JsonArray array, List<String> out) {
        for (JsonElement e : array) {
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
                out.add(e.getAsString());
            }
        }
    }

    @Test
    void rolledAnimationsBlendEveryChannelFromTheLivePose() throws Exception {
        for (String model : MODELS) {
            var all = animations(model, "main.animation.json");
            List<String> names = new ArrayList<>();
            for (var entry : all.entrySet()) {
                if (entry.getKey().startsWith("prone:action:")) {
                    names.add(entry.getKey());
                }
            }
            assertFalse(names.isEmpty(), model + ": 缺少 prone:action:* 动画");
            for (String name : names) {
                JsonObject anim = all.getAsJsonObject(name);
                JsonObject bones = anim.getAsJsonObject("bones");
                assertTrue(bones.has("MAllBody"), model + "/" + name + ": 必须自带 MAllBody 侧滚");
                assertTrue(bones.has("AllBody"),
                        model + "/" + name + ": 必须把 AllBody 压平——TaCZ 的站姿动画在 AllBody 上写了"
                                + " head_yaw 补偿，趴下时它的局部 Y 轴就是世界侧滚轴，会把侧躺的身体再拧一次");
                for (var bone : bones.entrySet()) {
                    assertFalse(FORBIDDEN.contains(bone.getKey()),
                            model + "/" + name + ": 不允许写 " + bone.getKey());
                    String json = bone.getValue().toString();
                    assertTrue(json.contains("kinetic.prone_roll"),
                            model + "/" + name + "/" + bone.getKey()
                                    + ": 每个通道都必须用 kinetic.prone_roll 混合，否则 roll=0 时与底层动画不一致");
                }
                for (String expression : expressions(anim)) {
                    try {
                        // 与运行期同一个绑定集（kinetic / ysm / ctrl / math / query），
                        // 因此 kinetic.prone_roll、bone_rot(...) 都按真实语义解析。
                        CustomMolangParser.parseSingleExpressionUnsafe(expression);
                    } catch (Exception e) {
                        fail(model + "/" + name + ": molang 表达式解析失败: " + expression + " → " + e);
                    }
                }
            }
        }
    }

    @Test
    void boneRotTargetsExistInTheModel() throws Exception {
        for (String model : MODELS) {
            Set<String> bones = modelBones(model);
            var all = animations(model, "main.animation.json");
            for (var entry : all.entrySet()) {
                if (!entry.getKey().startsWith("prone:")) {
                    continue;
                }
                for (String expression : expressions(entry.getValue().getAsJsonObject())) {
                    int index = 0;
                    while ((index = expression.indexOf("bone_rot('", index)) >= 0) {
                        int end = expression.indexOf(')', index);
                        String bone = expression.substring(index + "bone_rot('".length(), end).replace("'", "").trim();
                        assertTrue(bones.contains(bone),
                                model + "/" + entry.getKey() + ": bone_rot 指向不存在的骨骼 " + bone);
                        index = end;
                    }
                }
            }
        }
    }

    @Test
    void proneMeleeAndThrowKeepTheLegsFree() throws Exception {
        for (String model : MODELS) {
            for (String name : List.of("prone:swing", "prone:throw")) {
                JsonObject anim = animations(model, "main.animation.json").getAsJsonObject(name);
                assertNotNull(anim, model + ": 缺少 " + name);
                JsonObject bones = anim.getAsJsonObject("bones");
                for (String leg : List.of("LeftLeg", "LeftLowerLeg", "LeftFoot", "RightLeg",
                        "RightLowerLeg", "RightFoot", "DownBody", "Root", "AllBody")) {
                    assertFalse(bones.has(leg), model + "/" + name + ": 不能写 " + leg + "（腿要继续爬行）");
                }
                assertTrue(bones.has("Head"), model + "/" + name + ": 必须自己写头（否则沿用站姿的头，会朝地）");
                assertTrue(bones.has("RightArm"), model + "/" + name + ": 必须写持械手");
                // 手臂通道必须连续：相邻关键帧的任何分量都不允许跳超过 180 度
                for (var bone : bones.entrySet()) {
                    JsonObject rotation = bone.getValue().getAsJsonObject().getAsJsonObject("rotation");
                    if (rotation == null || rotation.isJsonArray()) {
                        continue;
                    }
                    List<String> times = new ArrayList<>(rotation.keySet());
                    times.sort((a, b) -> Double.compare(Double.parseDouble(a), Double.parseDouble(b)));
                    for (int i = 1; i < times.size(); i++) {
                        JsonArray prev = rotation.getAsJsonObject(times.get(i - 1)).getAsJsonArray("post");
                        JsonArray cur = rotation.getAsJsonObject(times.get(i)).getAsJsonArray("post");
                        for (int axis = 0; axis < 3; axis++) {
                            double delta = Math.abs(cur.get(axis).getAsDouble() - prev.get(axis).getAsDouble());
                            assertTrue(delta <= 180.0,
                                    model + "/" + name + "/" + bone.getKey() + ": " + times.get(i - 1)
                                            + "->" + times.get(i) + " 轴 " + axis + " 跳了 " + delta
                                            + " 度（会让手臂瞬移/抽搐）");
                        }
                    }
                }
            }
        }
    }

    @Test
    void tacProneAnimationsLeaveTheLowerBodyToTheMainChannel() throws Exception {
        Set<String> lower = Set.of("Root", "AllBody", "DownBody",
                "LeftLeg", "LeftLowerLeg", "LeftFoot", "RightLeg", "RightLowerLeg", "RightFoot");
        for (String model : MODELS) {
            var all = animations(model, "tac.animation.json");
            int checked = 0;
            for (var entry : all.entrySet()) {
                if (!entry.getKey().startsWith("tac:climb")) {
                    continue;
                }
                checked++;
                JsonObject bones = entry.getValue().getAsJsonObject().getAsJsonObject("bones");
                for (String bone : lower) {
                    assertFalse(bones.has(bone),
                            model + "/" + entry.getKey() + ": 不许写 " + bone
                                    + "——下肢必须只由主频道的 climb/climbing 驱动，否则开火会把爬行冻住");
                }
            }
            assertTrue(checked >= 18, model + ": tac:climb* 动画数量异常（" + checked + "）");
        }
    }
}
