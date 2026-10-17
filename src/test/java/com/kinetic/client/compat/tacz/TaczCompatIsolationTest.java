package com.kinetic.client.compat.tacz;

import com.kinetic.client.compat.tacz.lod.GunLodRegistry;
import com.kinetic.client.compat.tacz.lod.TaczGunLodBuilder;
import java.nio.charset.StandardCharsets;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /**
     * 枪械 LOD 的其余类必须完全与 TaCZ 无关：它们要么在未安装 TaCZ 时也会被加载，
     * 要么需要离线单测。唯一允许引用 TaCZ 的是 {@code TaczGunLodBuilder}（它只在确认安装后才被触达）。
     */
    @Test
    void gunLodSupportClassesNeverReferenceTaczTypes() throws Exception {
        var packagePath = "/com/kinetic/client/compat/tacz/lod/";
        for (var name : new String[]{
                "GunAnimationLod", "GunLodCache", "GunLodCacheKey", "GunLodDecimator",
                "GunLodDistance", "GunLodFactory", "GunLodHandle", "GunLodPackFingerprint",
                "GunLodPackIndex", "GunLodPackRoots", "GunLodPrewarm", "GunLodPrewarmPlan",
                "GunLodRegistry"}) {
            var constantPool = readClassConstantPool(packagePath + name + ".class");
            assertFalse(constantPool.contains(DELEGATE_PREFIX),
                    name + " 不得引用 TaCZ 类型");
        }
    }

    /**
     * 最直接的证明：测试运行时 TaCZ 根本不在类路径上，这正是"未安装 TaCZ"的等价环境。
     * 构建器必须返回 {@code null}（把 {@code NoClassDefFoundError} 吞掉），绝不能把异常抛到渲染路径上。
     */
    @Test
    void builderDegradesToNullWhenTaczIsAbsent() {
        assertNull(TaczGunLodBuilder.build(null), "null 枪 id 必须安全");
        assertFalse(TaczGunLodBuilder.prewarm(null), "null 枪 id 的预热必须安静失败");
        assertFalse(TaczGunLodBuilder.prewarm(new ResourceLocation("tacz", "ak47")),
                "未安装 TaCZ 时预热必须安静失败");
        assertDoesNotThrow(() -> assertNull(TaczGunLodBuilder.build(new ResourceLocation("tacz", "ak47")),
                "未安装 TaCZ 时必须降级为 null"));
        assertDoesNotThrow(() -> assertNull(GunLodRegistry.get(new ResourceLocation("tacz", "ak47")),
                "首次调用只排队异步构建，本帧返回 null"));
        assertDoesNotThrow(GunLodRegistry::clear);
    }

    private static String readClassConstantPool(String resource) throws Exception {
        try (var in = TaczCompatIsolationTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "类文件不在测试类路径上: " + resource);
            // 常量池里的类名是 UTF-8 的 ASCII 序列，用 ISO-8859-1 解码可原样保留以便子串查找。
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}
