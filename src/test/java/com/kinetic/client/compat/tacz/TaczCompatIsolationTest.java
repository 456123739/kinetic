package com.kinetic.client.compat.tacz;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 类加载隔离的回归护栏。
 *
 * <p>TaCZ 是 <b>compile-only</b> 依赖。如果 {@code TACZCompat} 自身的字节码里出现任何 TaCZ 类型，
 * 那么在没装 TaCZ 的客户端上加载这个类就会抛 {@code NoClassDefFoundError}——这是本特性唯一能崩
 * 客户端的地方。因此这里直接扫描它的常量池，确保它只做"检查是否安装 + 委托给独立类"。
 */
class TaczCompatIsolationTest {
    private static final String COMPAT_CLASS = "/com/kinetic/client/compat/tacz/TACZCompat.class";
    private static final String DELEGATE_PREFIX = "com/tacz/";

    @Test
    void compatFacadeNeverReferencesTaczTypes() throws Exception {
        var constantPool = readClassConstantPool(COMPAT_CLASS);
        assertFalse(constantPool.contains(DELEGATE_PREFIX),
                "TACZCompat 不得引用 TaCZ 类型，否则未安装 TaCZ 的客户端会 NoClassDefFoundError");
    }

    /** 委托类<b>应该</b>引用 TaCZ 类型——它只在确认安装后才会被加载。 */
    @Test
    void delegateClassIsWhereTaczTypesLive() throws Exception {
        var constantPool = readClassConstantPool(
                "/com/kinetic/client/compat/tacz/TacCompatInner.class");
        assertTrue(constantPool.contains(DELEGATE_PREFIX),
                "TacCompatInner 才是持有 TaCZ 类型的地方；若这里没有引用，说明隔离被改坏了");
    }

    private static String readClassConstantPool(String resource) throws Exception {
        try (var in = TaczCompatIsolationTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "类文件不在测试类路径上: " + resource);
            // 常量池里的类名是 UTF-8 的 ASCII 序列，用 ISO-8859-1 解码可原样保留以便子串查找。
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}
