package com.kinetic.client.compat.tacz.lod;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "整包复用"的判定。
 *
 * <p>这是这一轮改动的核心：枪包内容没变就一把枪都不扫——不读模型、不读贴图、不算哈希。
 * 断言同时钉住两件容易写错的事：跳过的包不能计入待扫总数（否则进度条永远到不了头），
 * 拿不到指纹时绝不能当成"没变"（那会让更新过的枪包永远等不到预热）。
 */
class GunLodPrewarmPlanTest {

    @Test
    void unchangedPacksAreSkippedEntirely() {
        var index = GunLodPackIndex.load(null);
        index.markComplete("eos", "dir:same");
        index.markComplete("tacz", "zip:same");

        var result = GunLodPrewarmPlan.plan(
                List.of(gun("eos", "a"), gun("eos", "b"), gun("tacz", "c")),
                namespace -> "eos".equals(namespace) ? "dir:same" : "zip:same",
                index);

        assertEquals(0, result.slots().size(), "整包复用后不该有任何枪进队列");
        assertEquals(3, result.skipped(), "跳过的枪必须计为已完成，否则进度条到不了头");
        assertTrue(result.namespaceTotals().isEmpty());
    }

    @Test
    void changedPackIsSweptWhileOthersAreSkipped() {
        var index = GunLodPackIndex.load(null);
        index.markComplete("eos", "dir:old");
        index.markComplete("tacz", "zip:same");

        var result = GunLodPrewarmPlan.plan(
                List.of(gun("eos", "a"), gun("eos", "b"), gun("tacz", "c")),
                namespace -> "eos".equals(namespace) ? "dir:new" : "zip:same",
                index);

        assertEquals(2, result.slots().size(), "只有变了的枪包要扫");
        assertEquals(1, result.skipped(), "没变的那个枪包整包跳过");
        assertEquals(2, result.namespaceTotals().get("eos"));
        assertEquals(null, result.namespaceTotals().get("tacz"), "跳过的包不进待扫总数");
    }

    @Test
    void unknownFingerprintIsNeverTreatedAsUnchanged() {
        var index = GunLodPackIndex.load(null);
        index.markComplete("eos", "dir:whatever");

        var result = GunLodPrewarmPlan.plan(List.of(gun("eos", "a")),
                namespace -> null, index);

        assertEquals(1, result.slots().size(), "拿不到枪包身份就必须老老实实扫");
        assertEquals(0, result.skipped());
    }

    @Test
    void fingerprintIsComputedOncePerNamespace() {
        var calls = new AtomicInteger();
        Map<String, Integer> seen = new HashMap<>();

        GunLodPrewarmPlan.plan(
                List.of(gun("eos", "a"), gun("eos", "b"), gun("eos", "c"), gun("tacz", "d")),
                namespace -> {
                    calls.incrementAndGet();
                    seen.merge(namespace, 1, Integer::sum);
                    return "fp:" + namespace;
                },
                GunLodPackIndex.load(null));

        assertEquals(2, calls.get(), "指纹按命名空间算一次，不能按枪算");
        assertEquals(1, seen.get("eos"));
        assertEquals(1, seen.get("tacz"));
    }

    @Test
    void emptyGunListPlansNothing() {
        var result = GunLodPrewarmPlan.plan(List.of(), namespace -> "fp",
                GunLodPackIndex.load(null));
        assertEquals(0, result.slots().size());
        assertEquals(0, result.skipped());
        assertTrue(result.fingerprints().isEmpty());
    }

    @Test
    void slotsCarryTheirNamespaceForLaterBookkeeping() {
        var result = GunLodPrewarmPlan.plan(List.of(gun("eos", "a")), namespace -> null,
                GunLodPackIndex.load(null));
        assertEquals("eos", result.slots().get(0).namespace());
        assertEquals(new ResourceLocation("eos", "a"), result.slots().get(0).gunId());
    }

    @Test
    void aFingerprintMapMayHoldNulls() {
        // Map.copyOf 会拒绝 null 值；计划里"无法证明未变"就是用 null 表示的。
        var result = GunLodPrewarmPlan.plan(List.of(gun("eos", "a")), namespace -> null,
                GunLodPackIndex.load(null));
        assertTrue(result.fingerprints().containsKey("eos"));
        assertEquals(null, result.fingerprints().get("eos"));
    }

    private static ResourceLocation gun(String namespace, String path) {
        return new ResourceLocation(namespace, path);
    }
}
