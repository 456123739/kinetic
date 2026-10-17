package com.kinetic.mixin.plugin;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 保证 Mixin 配置插件在启动最早期不加载任何会牵动 vanilla 实体类型的类。
 *
 * <p>插件构造函数与 {@code getMixins()} 运行在 Mixin 装载配置的阶段，此时任何一条
 * {@code net.minecraft.world.entity.Entity} 的加载都会让别的模组针对 {@code Entity} 的 Mixin
 * 在 prepare 阶段直接失败（{@code MixinTargetAlreadyLoadedException}）。这类崩溃与被牵连的模组
 * 无关，只看谁先碰了 {@code Entity}，因此必须由静态检查守住，而不是靠运行时碰运气。
 */
class MixinTweakerEarlyLoadTest {
    private static final String FORBIDDEN_ENTITY = "net/minecraft/world/entity/Entity";
    private static final String FORBIDDEN_RAGDOLL_COMPAT =
            "com/kinetic/client/compat/ragdollified/";
    private static final String REQUIRED_LOADING_LIST = "net/minecraftforge/fml/loading/LoadingModList";

    @Test
    void pluginDoesNotPullInEntityTypesOrCompatLayers() throws IOException {
        byte[] bytecode = readClassBytes(MixinTweaker.class);
        String constants = new String(bytecode, StandardCharsets.ISO_8859_1);

        assertTrue(constants.contains(REQUIRED_LOADING_LIST),
                "检查样本本身应包含 FML 的 LoadingModList 引用，否则说明读错了类文件");

        assertFalse(constants.contains(FORBIDDEN_ENTITY),
                "Mixin 配置插件不得引用 vanilla Entity：一旦在装载阶段加载它，"
                        + "其它模组针对 Entity 的 Mixin 会在 prepare 阶段失败并中断启动");

        assertFalse(constants.contains(FORBIDDEN_RAGDOLL_COMPAT),
                "Mixin 配置插件不得引用布娃娃联动层：该类的方法描述符带有 "
                        + "LivingEntity/AbstractClientPlayer 等类型，同样会提前加载 Entity");
    }

    private static byte[] readClassBytes(Class<?> type) throws IOException {
        String resource = type.getSimpleName() + ".class";
        try (InputStream stream = type.getResourceAsStream(resource)) {
            assertNotNull(stream, "找不到类文件资源: " + resource);
            return stream.readAllBytes();
        }
    }
}
