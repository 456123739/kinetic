package com.kinetic.client.compat.ragdollified;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ragdollified 类加载隔离的回归护栏。
 *
 * <p>Ragdollified 是 <b>compile-only</b> 依赖。{@link RagdollifiedCompat} 是唯一会被无条件加载的
 * 入口（它挂在客户端初始化与客户端 tick 上），所以它的字节码里绝不能出现任何提供方类型——否则
 * 没装 Ragdollified 的客户端会直接 {@code NoClassDefFoundError}。持有提供方类型的类只有那几个
 * "只在确认安装后才会被触达"的实现类，外加一个只在提供方存在时才装载的 Mixin。
 *
 * <p>TaCZ 那套隔离有同样的护栏；这里补上，是因为此前的审计发现 Ragdollified 这条<b>没有</b>
 * 字节码扫描，隔离完全靠人工检查。
 */
class RagdollifiedCompatIsolationTest {
    private static final String PROVIDER_PREFIX = "com/raiiiden/";

    /** 允许引用提供方类型的类：它们都只在确认安装后才被加载。 */
    private static final String[] PROVIDER_AWARE = {
            "/com/kinetic/client/compat/ragdollified/KineticRagdollRenderer.class",
            "/com/kinetic/client/compat/ragdollified/KineticRagdollRig.class",
            "/com/kinetic/client/compat/ragdollified/KineticRagdollState.class",
            "/com/kinetic/mixin/client/ragdollified/ClientRagdollRendererMixin.class",
    };

    /** 门面必须完全干净：它在未安装时也会被加载。 */
    @Test
    void facadeNeverReferencesProviderTypes() throws Exception {
        var constantPool = readClassConstantPool(
                "/com/kinetic/client/compat/ragdollified/RagdollifiedCompat.class");
        assertFalse(constantPool.contains(PROVIDER_PREFIX),
                "RagdollifiedCompat 不得引用提供方类型，否则未安装时加载它会 NoClassDefFoundError");
    }

    /** 其余辅助类同样要在未安装时可以安全加载。 */
    @Test
    void supportClassesNeverReferenceProviderTypes() throws Exception {
        for (var name : new String[]{
                "RagdollifiedDeathWatcher", "RagdollifiedEvent", "KineticRagdollMath", "RagdollTuning"}) {
            var constantPool = readClassConstantPool(
                    "/com/kinetic/client/compat/ragdollified/" + name + ".class");
            assertFalse(constantPool.contains(PROVIDER_PREFIX), name + " 不得引用提供方类型");
        }
    }

    /** 反过来：持有提供方类型的那些类必须真的引用它，否则说明隔离被改坏了。 */
    @Test
    void providerAwareClassesAreWhereProviderTypesLive() throws Exception {
        for (var resource : PROVIDER_AWARE) {
            var constantPool = readClassConstantPool(resource);
            assertTrue(constantPool.contains(PROVIDER_PREFIX),
                    resource + " 应该持有提供方类型；若这里没有引用，说明隔离被改坏了");
        }
    }

    /**
     * 未安装时所有入口都必须是安全的空操作。
     *
     * <p>测试运行时 Ragdollified 确实不在类路径上，这正是"未安装"的等价环境。
     */
    @Test
    void entryPointsAreSafeWithoutTheProvider() {
        assertFalse(RagdollifiedCompat.isAvailable(), "测试环境里没有 Ragdollified");
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> {
            RagdollifiedCompat.init();
            RagdollifiedCompat.initRuntime();
            RagdollifiedCompat.refreshPendingDeathPoses();
            RagdollifiedDeathWatcher.tick();
        });
    }

    private static String readClassConstantPool(String resource) throws Exception {
        try (var in = RagdollifiedCompatIsolationTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "类文件不在测试类路径上: " + resource);
            // 常量池里的类名是 UTF-8 的 ASCII 序列，用 ISO-8859-1 解码可原样保留以便子串查找。
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}
