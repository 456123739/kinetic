package com.kinetic.capability;

import com.kinetic.model.catalog.builtin.BuiltinModelIndex;
import com.kinetic.model.domain.Hash256;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内置玩家模型变体判定的纯逻辑测试：不依赖 Minecraft，也不依赖 Quick Skin。
 *
 * <p>重点验证：改为事件驱动后，「只在标脏时判定」与「旧版每帧判定」得到完全相同的变体。
 */
class BuiltinSkinVariantDecisionTest {
    private static final Hash256 STEVE = hash(1);
    private static final Hash256 ALEX = hash(2);
    private static final Hash256 CUSTOM = hash(3);
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private static final String STEVE_PATH = BuiltinModelIndex.INTRINSIC_DEFAULT_PATH.value();
    private static final String ALEX_PATH = BuiltinModelIndex.SLIM_PLAYER_PATH.value();

    @Test
    void slimSkinMovesTheWideBuiltinToTheSlimVariant() {
        var decision = new BuiltinSkinVariantDecision();

        assertEquals(ALEX, decision.decide("slim", STEVE, BuiltinSkinVariantDecisionTest::isBuiltin,
                BuiltinSkinVariantDecisionTest::resolve));
    }

    @Test
    void defaultSkinMovesTheSlimBuiltinToTheWideVariant() {
        var decision = new BuiltinSkinVariantDecision();

        assertEquals(STEVE, decision.decide("default", ALEX, BuiltinSkinVariantDecisionTest::isBuiltin,
                BuiltinSkinVariantDecisionTest::resolve));
    }

    @Test
    void customModelsAreNeverOverridden() {
        var decision = new BuiltinSkinVariantDecision();

        assertNull(decision.decide("slim", CUSTOM, BuiltinSkinVariantDecisionTest::isBuiltin,
                BuiltinSkinVariantDecisionTest::resolve));
    }

    @Test
    void unknownSkinNameFallsBackToTheWideBuiltin() {
        var decision = new BuiltinSkinVariantDecision();

        assertEquals(STEVE, decision.decide("classic", ALEX, BuiltinSkinVariantDecisionTest::isBuiltin,
                BuiltinSkinVariantDecisionTest::resolve));
    }

    @Test
    void missingInputsKeepTheCurrentSelection() {
        var decision = new BuiltinSkinVariantDecision();

        assertNull(decision.decide(null, STEVE, BuiltinSkinVariantDecisionTest::isBuiltin,
                BuiltinSkinVariantDecisionTest::resolve));
        assertNull(decision.decide("", STEVE, BuiltinSkinVariantDecisionTest::isBuiltin,
                BuiltinSkinVariantDecisionTest::resolve));
        assertNull(decision.decide("slim", null, BuiltinSkinVariantDecisionTest::isBuiltin,
                BuiltinSkinVariantDecisionTest::resolve));
    }

    /** 输入没变时不重新扫描目录（旧版逐帧优化的核心）。 */
    @Test
    void unchangedInputsDoNotRescanTheCatalog() {
        var decision = new BuiltinSkinVariantDecision();
        var lookups = new AtomicInteger();

        decision.decide("slim", ALEX, BuiltinSkinVariantDecisionTest::isBuiltin,
                path -> {
                    lookups.incrementAndGet();
                    return resolve(path);
                });
        decision.decide("slim", ALEX, BuiltinSkinVariantDecisionTest::isBuiltin,
                path -> {
                    lookups.incrementAndGet();
                    return resolve(path);
                });

        assertEquals(1, lookups.get(), "同一 (皮肤, 模型) 组合只应查一次目录");
    }

    /**
     * 事件/创建驱动 vs 旧版逐帧轮询：只要皮肤变化、模型变化与玩家创建都会标脏，
     * 两条路径的最终变体（以及每一步的变体）必须完全一致。
     */
    @Test
    void gatedEvaluationMatchesTheOldPerFrameEvaluation() {
        List<List<Step>> timelines = List.of(
                // 启动即细手臂皮肤：创建时判定一次
                List.of(Step.skin("slim"), Step.frame(), Step.frame()),
                // 游戏内从宽皮肤切到细皮肤（Quick Skin 事件），再切回来
                List.of(Step.skin("default"), Step.frame(), Step.skin("slim"), Step.frame(),
                        Step.skin("default"), Step.frame()),
                // 切到自定义模型后不再覆盖，再切回内置模型
                List.of(Step.skin("slim"), Step.frame(), Step.model(CUSTOM), Step.frame(),
                        Step.model(STEVE), Step.frame()),
                // 服务端把模型改成另一个内置变体
                List.of(Step.skin("slim"), Step.frame(), Step.model(ALEX), Step.frame(),
                        Step.model(STEVE), Step.frame()),
                // 外部把模型改回「切换前」的旧值：旧版下一帧就会把它再收敛回皮肤匹配的变体
                List.of(Step.skin("slim"), Step.frame(), Step.model(STEVE), Step.frame(),
                        Step.model(STEVE), Step.frame()),
                // 先自定义再细皮肤
                List.of(Step.model(CUSTOM), Step.frame(), Step.skin("slim"), Step.frame(),
                        Step.model(STEVE), Step.frame(), Step.frame()));

        for (List<Step> timeline : timelines) {
            assertEquals(perFrame(timeline), gated(timeline),
                    "事件驱动路径与逐帧路径的变体轨迹必须一致: " + timeline);
        }
    }

    private static List<Hash256> perFrame(List<Step> timeline) {
        var decision = new BuiltinSkinVariantDecision();
        Hash256 applied = STEVE;
        String skin = "default";
        var trajectory = new ArrayList<Hash256>();
        for (Step step : timeline) {
            skin = step.skin() != null ? step.skin() : skin;
            applied = step.model() != null ? step.model() : applied;
            Hash256 matched = decision.decide(skin, applied, BuiltinSkinVariantDecisionTest::isBuiltin,
                    BuiltinSkinVariantDecisionTest::resolve);
            if (matched != null) {
                applied = matched;
            }
            trajectory.add(applied);
        }
        return trajectory;
    }

    private static List<Hash256> gated(List<Step> timeline) {
        var invalidation = new SkinVariantInvalidation();
        var decision = new BuiltinSkinVariantDecision();
        Hash256 applied = STEVE;
        String skin = "default";
        invalidation.markDirty(PLAYER);
        var trajectory = new ArrayList<Hash256>();
        for (Step step : timeline) {
            if (step.skin() != null) {
                skin = step.skin();
                invalidation.markDirty(PLAYER);
            }
            if (step.model() != null) {
                applied = step.model();
                invalidation.markDirty(PLAYER);
            }
            if (invalidation.consumeDirty(PLAYER)) {
                Hash256 matched = decision.decide(skin, applied, BuiltinSkinVariantDecisionTest::isBuiltin,
                        BuiltinSkinVariantDecisionTest::resolve);
                if (matched != null) {
                    applied = matched;
                }
            }
            trajectory.add(applied);
        }
        return trajectory;
    }

    private record Step(String skin, Hash256 model) {
        static Step frame() {
            return new Step(null, null);
        }

        static Step skin(String name) {
            return new Step(name, null);
        }

        static Step model(Hash256 hash) {
            return new Step(null, hash);
        }
    }

    private static boolean isBuiltin(Hash256 hash) {
        return STEVE.equals(hash) || ALEX.equals(hash);
    }

    private static Optional<Hash256> resolve(String path) {
        if (STEVE_PATH.equals(path)) {
            return Optional.of(STEVE);
        }
        if (ALEX_PATH.equals(path)) {
            return Optional.of(ALEX);
        }
        return Optional.empty();
    }

    private static Hash256 hash(int seed) {
        var bytes = new byte[Hash256.SIZE];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) (seed + index);
        }
        return new Hash256(bytes);
    }

    @Test
    void builtinPathsAreDistinct() {
        assertTrue(!STEVE_PATH.equals(ALEX_PATH), "宽/细内置模型路径必须不同");
    }
}
