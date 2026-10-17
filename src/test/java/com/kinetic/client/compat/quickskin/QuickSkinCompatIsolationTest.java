package com.kinetic.client.compat.quickskin;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 类加载隔离的回归护栏（对应 {@code TaczCompatIsolationTest}）。
 *
 * <p>Quick Skin 是<b>可选</b>依赖：未安装时它的任何类型都不在类路径上。{@link QuickSkinCompat}
 * 会在那种环境下被加载，因此它的字节码里不得出现 {@code com/quickskin/}（类型描述符）或
 * {@code com.quickskin.}（反射用的字符串）。真正的 Quick Skin 名称只允许出现在
 * {@link QuickSkinCompatInner} 里，而它只在确认安装后才被加载。
 */
class QuickSkinCompatIsolationTest {
    private static final String FACADE_CLASS =
            "/com/kinetic/client/compat/quickskin/QuickSkinCompat.class";
    private static final String DELEGATE_CLASS =
            "/com/kinetic/client/compat/quickskin/QuickSkinCompatInner.class";

    private static final String QUICK_SKIN_TYPE_PREFIX = "com/quickskin/";
    private static final String QUICK_SKIN_NAME_PREFIX = "com.quickskin.";

    @Test
    void facadeNeverReferencesQuickSkin() throws Exception {
        var constantPool = readClassConstantPool(FACADE_CLASS);
        assertFalse(constantPool.contains(QUICK_SKIN_TYPE_PREFIX),
                "QuickSkinCompat 不得引用 Quick Skin 类型，否则未安装时会 NoClassDefFoundError");
        assertFalse(constantPool.contains(QUICK_SKIN_NAME_PREFIX),
                "QuickSkinCompat 连 Quick Skin 的类名都不该出现；集成必须全部在 QuickSkinCompatInner 里");
    }

    /** 委托类才允许出现 Quick Skin 的类名——它只在确认安装后加载。 */
    @Test
    void delegateIsWhereQuickSkinNamesLive() throws Exception {
        var constantPool = readClassConstantPool(DELEGATE_CLASS);
        assertTrue(constantPool.contains("com.quickskin.mod.common.event.InternalEventBus"),
                "QuickSkinCompatInner 应当通过内部事件总线订阅外观更新");
        assertTrue(constantPool.contains("com.quickskin.mod.common.event.PlayerAppearanceUpdateEvent"),
                "QuickSkinCompatInner 应当订阅 PlayerAppearanceUpdateEvent");
    }

    /**
     * 测试运行时 Quick Skin 不在类路径上，正是「未安装」的等价环境：
     * 订阅必须安全降级为「不做事件驱动失效」，绝不能把异常抛到初始化路径上。
     */
    @Test
    void subscribingDegradesSafelyWhenQuickSkinIsAbsent() {
        assertFalse(QuickSkinCompat.isInstalled(), "单测环境不应装有 Quick Skin");
        assertDoesNotThrow(QuickSkinCompatInner::register);
    }

    private static String readClassConstantPool(String resource) throws Exception {
        try (var in = QuickSkinCompatIsolationTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "类文件不在测试类路径上: " + resource);
            // 常量池里的类名是 UTF-8 的 ASCII 序列，用 ISO-8859-1 解码可原样保留以便子串查找。
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}
