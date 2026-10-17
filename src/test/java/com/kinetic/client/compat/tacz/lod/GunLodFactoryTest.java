package com.kinetic.client.compat.tacz.lod;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link GunLodFactory} 中不依赖 Minecraft 运行时的部分。
 *
 * <p>{@code toTextureLocation} 是这条链路上唯一会"静默找不到贴图"的地方：TaCZ 的 display 里
 * {@code model_texture} 写的是去掉前缀/后缀的资源路径，而原版贴图管理器要完整路径。这里对三种
 * 写法都做幂等转换，避免个别枪包写法不同就整把枪没有 LOD。
 */
class GunLodFactoryTest {

    @Test
    void convertsTaczStylePathToVanillaTextureLocation() {
        assertEquals(new ResourceLocation("tacz", "textures/gun/uv/ak47.png"),
                GunLodFactory.toTextureLocation(new ResourceLocation("tacz", "gun/uv/ak47")));
    }

    @Test
    void conversionIsIdempotentForAlreadyCompletePaths() {
        var complete = new ResourceLocation("tacz", "textures/gun/uv/ak47.png");
        assertEquals(complete, GunLodFactory.toTextureLocation(complete));
        assertEquals(complete,
                GunLodFactory.toTextureLocation(new ResourceLocation("tacz", "gun/uv/ak47.png")));
    }

    @Test
    void keepsNamespace() {
        assertEquals(new ResourceLocation("my_pack", "textures/gun/uv/rifle.png"),
                GunLodFactory.toTextureLocation(new ResourceLocation("my_pack", "gun/uv/rifle")));
    }

    @Test
    void nullDeclaredTextureStaysNull() {
        assertNull(GunLodFactory.toTextureLocation(null));
    }

    @Test
    void nullModelIsRejectedWithoutTouchingMinecraft() {
        // 必须在没有任何 Minecraft 运行时的环境下安全返回 null：
        // 解析不出 proto 模型时，冷路径要在碰 Minecraft 之前就退出。
        var texture = new ResourceLocation("tacz", "gun/uv/ak47");
        var scale = new org.joml.Vector3f(1.0f, 1.0f, 1.0f);
        assertNull(GunLodFactory.bakeAndStore(null, container(1), "name", new byte[32],
                null, new byte[0], texture, scale, new org.joml.Vector3f()));
        assertNull(GunLodFactory.bakeAndStore(null, container(1), "name", new byte[32],
                null, new byte[0], null, scale, new org.joml.Vector3f()));
        // 预热路径同样要在没有模型时安静失败，而不是抛出去。
        assertFalse(GunLodFactory.bakeOnly(null, container(1), "name", new byte[32],
                null, new byte[0], new org.joml.Vector3f()));
    }

    private static com.kinetic.model.domain.Hash256 container(int seed) {
        var bytes = new byte[com.kinetic.model.domain.Hash256.SIZE];
        java.util.Arrays.fill(bytes, (byte) seed);
        return new com.kinetic.model.domain.Hash256(bytes);
    }
}
