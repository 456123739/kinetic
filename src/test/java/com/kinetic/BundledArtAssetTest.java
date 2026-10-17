package com.kinetic;

import com.kinetic.client.skin.DefaultPlayerSkins;
import com.kinetic.model.catalog.builtin.BuiltinModelIndex;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 随模组自带的图片资源：兜底皮肤与模组 logo。
 *
 * <p>这两类资源只在特定路径上被引用，平时不会被任何逻辑加载，因此缺文件或尺寸不对很难在运行时
 * 暴露出来：兜底皮肤只会在"读不到玩家皮肤"时才用，logo 只在模组列表里显示。用测试把它们钉住。
 */
class BundledArtAssetTest {
    private static final Path RESOURCES = Path.of("src/main/resources");

    @Test
    void defaultSkinsArePresentAndMatchTheModelTextureLayout() throws IOException {
        // 内置 Steve/Alex 模型声明 64x64 贴图，兜底皮肤必须同尺寸，否则 UV 全错。
        assertSkin("assets/kinetic/textures/default/steve.png");
        assertSkin("assets/kinetic/textures/default/alex.png");
    }

    @Test
    void defaultSkinsFollowTheModelVariant() {
        assertEquals(new ResourceLocation("kinetic", "textures/default/steve.png"),
                DefaultPlayerSkins.forModelId(BuiltinModelIndex.INTRINSIC_DEFAULT_PATH.value()));
        assertEquals(new ResourceLocation("kinetic", "textures/default/alex.png"),
                DefaultPlayerSkins.forModelId(BuiltinModelIndex.SLIM_PLAYER_PATH.value()));
        // 非内置模型按粗手臂处理，不要返回 null。
        assertEquals(new ResourceLocation("kinetic", "textures/default/steve.png"),
                DefaultPlayerSkins.forModelId("something/else"));
    }

    @Test
    void modLogoDeclaredInModsTomlExists() throws IOException {
        var toml = Files.readString(RESOURCES.resolve("META-INF/mods.toml"));
        var logo = toml.lines()
                .filter(line -> line.contains("logoFile"))
                .map(line -> line.replaceAll(".*logoFile\\s*=\\s*\"([^\"]+)\".*", "$1"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("mods.toml 没有声明 logoFile"));
        Path file = RESOURCES.resolve(logo);
        assertTrue(Files.isRegularFile(file), "mods.toml 声明的 logo 不存在: " + file);
        var size = pngSize(file);
        assertEquals(size[0], size[1], "模组 logo 必须是正方形: " + file);
        assertTrue(size[0] >= 256, "模组 logo 太小，缩放到列表图标会糊: " + size[0]);
    }

    private static void assertSkin(String relative) throws IOException {
        Path file = RESOURCES.resolve(relative);
        assertTrue(Files.isRegularFile(file), "兜底皮肤缺失: " + file);
        var size = pngSize(file);
        assertEquals(64, size[0], "兜底皮肤宽度必须是 64: " + file);
        assertEquals(64, size[1], "兜底皮肤高度必须是 64: " + file);
    }

    /** 只读 PNG 的 IHDR：签名 8 字节 + 长度 4 + 类型 4，随后是大端宽高。 */
    private static int[] pngSize(Path file) throws IOException {
        var header = new byte[24];
        try (var input = Files.newInputStream(file)) {
            if (input.readNBytes(header, 0, header.length) != header.length) {
                throw new AssertionError("不是合法的 PNG（文件过短）: " + file);
            }
        }
        var buffer = ByteBuffer.wrap(header);
        var signature = new byte[8];
        buffer.get(signature);
        assertEquals((byte) 0x89, signature[0], "缺少 PNG 签名: " + file);
        assertEquals('P', signature[1]);
        return new int[]{buffer.getInt(16), buffer.getInt(20)};
    }
}
