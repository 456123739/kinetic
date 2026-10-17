package com.kinetic.model.resource.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * 玩家模型烘焙掩码。
 *
 * <p>回归点：原生烘焙按「烘焙用贴图」的 alpha 把整面全透明的 quad 判成 {@code kEmpty} 并删除。
 * 内置 Steve/Alex 玩家模型是用模型自带贴图烘焙、却用玩家皮肤绘制的，于是模型贴图里恰好透明的
 * 部位（手臂叠加层、手、眼皮、瞳孔、眉毛）会被永久删除——实机表现就是「手臂的外层皮肤不渲染」，
 * 而躯干的外套因为自带贴图在该处不透明而照常显示。掩码让这类面保留下来，绘制时再由玩家皮肤的
 * alpha 裁剪决定可见性。
 */
class PlayerBakeMaskTest {

    @Test
    void zeroAlphaBecomesOpaqueSoNoQuadIsDropped() throws Exception {
        var real = new NativeImage(NativeImage.Format.RGBA, 3, 1, false);
        real.setPixelRGBA(0, 0, 0x00FFFFFF);   // 全透明（内置贴图里手臂叠加层就是这种像素）
        real.setPixelRGBA(1, 0, 0xFF123456);   // 不透明
        real.setPixelRGBA(2, 0, 0x80123456);   // 半透明
        try (var mask = new ModelRenderTargetLoader.PlayerBakeMask(() -> real)) {
            var masked = mask.get();
            assertNotSame(real, masked, "不能就地改写原贴图：它同时是绘制用的贴图");
            assertEquals(0xFF, alpha(masked, 0), "alpha==0 必须抬到不透明，否则整面会被烘焙删掉");
            assertEquals(0xFF, alpha(masked, 1));
            assertEquals(0x80, alpha(masked, 2), "半透明必须保持半透明，否则会改变透明渲染路径");
            for (var x = 0; x < 3; x++) {
                assertEquals(real.getPixelRGBA(x, 0) & 0x00FFFFFF,
                        masked.getPixelRGBA(x, 0) & 0x00FFFFFF,
                        "只允许改 alpha，颜色通道必须原样保留");
            }
            assertEquals(3, masked.getWidth());
            assertEquals(1, masked.getHeight());
        } finally {
            real.close();
        }
    }

    @Test
    void maskIsBuiltOnceAndClosedWithTheBake() throws Exception {
        var real = new NativeImage(NativeImage.Format.RGBA, 2, 2, false);
        var calls = new int[1];
        try (var mask = new ModelRenderTargetLoader.PlayerBakeMask(() -> {
            calls[0]++;
            return real;
        })) {
            var first = mask.get();
            assertEquals(first, mask.get(), "同一次烘焙里复用同一份掩码，不重复解码");
            assertEquals(1, calls[0]);
        } finally {
            real.close();
        }
    }

    @Test
    void maskedBakeHashIsDistinctAndStable() {
        var textureHash = new byte[32];
        Arrays.fill(textureHash, (byte) 7);
        var masked = ModelRenderTargetLoader.maskedTextureHash(textureHash);

        assertFalse(Arrays.equals(textureHash, masked),
                "掩码烘焙的几何不同，必须落在不同的烘焙缓存条目上，否则升级后会复用旧的删面结果");
        assertArrayEquals(masked, ModelRenderTargetLoader.maskedTextureHash(textureHash.clone()),
                "同一贴图必须得到同一哈希，否则缓存永远命中不了");

        var other = new byte[32];
        Arrays.fill(other, (byte) 8);
        assertFalse(Arrays.equals(masked, ModelRenderTargetLoader.maskedTextureHash(other)));
    }

    private static int alpha(NativeImage image, int x) {
        return (image.getPixelRGBA(x, 0) >>> 24) & 0xFF;
    }
}
