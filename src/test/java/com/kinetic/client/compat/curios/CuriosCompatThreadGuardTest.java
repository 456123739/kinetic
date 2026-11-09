package com.kinetic.client.compat.curios;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Curios 查询的线程护栏。
 *
 * <p>{@code CuriosApi} 返回的 {@code ItemStackHandler} 不是线程安全的。动画求值目前在渲染线程上跑，
 * 但 native extract 与模型烘焙在 worker 上，历史上这个入口被跨线程调用过。三个查询方法都必须
 * 先过 {@code onRenderThread()}，否则 worker 上的读会撞上半更新的列表。
 */
class CuriosCompatThreadGuardTest {
    private static final Path SOURCE =
            Path.of("src/main/java/com/kinetic/client/compat/curios/CuriosCompat.java");

    @Test
    void everyQueryChecksTheThreadBeforeTouchingCurios() throws Exception {
        assertTrue(Files.isRegularFile(SOURCE), "找不到 " + SOURCE.toAbsolutePath());
        String text = Files.readString(SOURCE, StandardCharsets.UTF_8);
        for (String signature : new String[]{
                "hasAnyItemEquipped(LivingEntity entity, String slotType, ReferenceOpenHashSet<Item> items)",
                "hasAnyItemEquippedWithAnyTag(LivingEntity entity, String slotType, List<TagKey<Item>> tags)",
                "hasAnyItemEquippedWithAllTag(LivingEntity entity, String slotType, List<TagKey<Item>> tags)"}) {
            int start = text.indexOf(signature);
            assertTrue(start > 0, "找不到 " + signature);
            String body = text.substring(start, Math.min(text.length(), start + 220));
            assertTrue(body.contains("onRenderThread()"),
                    signature + " 必须先检查线程，否则 worker 上会并发访问 Curios 的 ItemStackHandler");
        }
    }

    @Test
    void threadProbeFailsOpenSoOfflineTestsKeepTheOldBehaviour() throws Exception {
        String text = Files.readString(SOURCE, StandardCharsets.UTF_8);
        int probe = text.indexOf("private static boolean onRenderThread()");
        assertTrue(probe > 0, "必须保留 onRenderThread()");
        String body = text.substring(probe, Math.min(text.length(), probe + 400));
        assertTrue(body.contains("catch (Throwable"),
                "无 GL 上下文（离线单测）时 RenderSystem 不可用，必须兜住");
        assertTrue(body.contains("return true;"),
                "探测失败时必须放行（保持原有行为），不能把 curios 条件静默关掉");
    }
}
