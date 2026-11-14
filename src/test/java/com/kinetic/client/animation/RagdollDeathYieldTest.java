package com.kinetic.client.animation;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 布娃娃没生成出来时，死亡动画必须还在。
 *
 * <p>实战教训：提供方换版本后接口调不通、布娃娃没生成，而 KINETIC 照样让出死亡动画——
 * 尸体既没有布娃娃也没有死亡姿势，玩家看到的就是"死了直接消失"。护栏三条：
 * 排队后要回头验证、验证失败要登记回退、死亡动画的让出判定要按实体问。
 */
class RagdollDeathYieldTest {
    private static final File COMPAT = new File(
            "src/main/java/com/kinetic/client/compat/ragdollified/RagdollifiedCompat.java");
    private static final File WATCHER = new File(
            "src/main/java/com/kinetic/client/compat/ragdollified/RagdollifiedDeathWatcher.java");
    private static final File REGISTER = new File(
            "src/main/java/com/kinetic/client/animation/AnimationRegister.java");

    @Test
    void queuedRagdollsAreVerified() throws Exception {
        String compat = Files.readString(COMPAT.toPath(), StandardCharsets.UTF_8);
        assertTrue(compat.contains("PENDING_VERIFY"), "排队后必须登记待验证");
        assertTrue(compat.contains("verifyQueuedRagdolls"), "必须有验证入口");
        assertTrue(compat.contains("RAGDOLL_VERIFY_TICKS"), "验证必须有超时");
        String watcher = Files.readString(WATCHER.toPath(), StandardCharsets.UTF_8);
        assertTrue(watcher.contains("verifyQueuedRagdolls"), "客户端每 tick 必须回头验证");
    }

    @Test
    void yieldingRequiresAnActualRagdoll() throws Exception {
        // 1.2.2 的 ClientDeathHandler 会按模型类型判定能不能生成布娃娃；KINETIC 换过渲染器，
        // 判定为不支持 → 提供方不会生成。此时若还"按配置"让出死亡动画，尸体就凭空消失。
        String compat = Files.readString(COMPAT.toPath(), StandardCharsets.UTF_8);
        int rule = compat.indexOf("isPlayerRagdollEnabled() && hasRagdollById(id)");
        assertTrue(rule > 0,
                "让出死亡动画必须要求布娃娃真的存在（或我们自己排过队），不能只看配置");
        assertTrue(compat.contains("setEntityHidden"),
                "1.2.2 会隐藏尸体；回退时必须能把它放出来");
        assertTrue(compat.contains("unhideEntity"), "回退路径必须恢复可见性");
    }

    @Test
    void theServerOwnsTheRagdollAndTheClientOnlyBacksUpp() throws Exception {
        // 1.2.2 反编译确认：PhysicsHooks.spawnRagdoll 只在服务端跑，玩家的 modelType 写死
        // HUMANOID_STANDARD（不看客户端渲染器）；客户端只负责渲染同步过来的布娃娃。
        // 所以客户端必须先等，等不到才自己补——否则同一具尸体会有两份。
        String compat = Files.readString(COMPAT.toPath(), StandardCharsets.UTF_8);
        assertTrue(compat.contains("DEFERRED_SPAWN"), "客户端 spawn 必须延后");
        assertTrue(compat.contains("flushDeferredSpawns"), "延后队列必须有处理入口");
        int wait = compat.indexOf("hasRagdollById(entityId)");
        int spawn = compat.indexOf("spawnRagdoll(living", wait);
        assertTrue(wait > 0 && spawn > wait,
                "必须先查服务端那份是否存在，再决定自己补");
    }

    @Test
    void failedRagdollsKeepTheDeathAnimation() throws Exception {
        String compat = Files.readString(COMPAT.toPath(), StandardCharsets.UTF_8);
        assertTrue(compat.contains("FALLBACK_DEATHS"), "验证失败必须登记回退");
        assertTrue(compat.contains("shouldYieldDeathAnimation(Entity entity)"),
                "让出判定必须能按实体问");
        String register = Files.readString(REGISTER.toPath(), StandardCharsets.UTF_8);
        assertTrue(register.contains("shouldYieldDeathAnimation(player)"),
                "死亡动画必须传实体进去，否则回退标记不起作用");
    }
}
