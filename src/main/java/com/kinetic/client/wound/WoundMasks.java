package com.kinetic.client.wound;

import com.kinetic.Kinetic;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * 受伤遮罩贴图的生命周期：每个档位、每一层各一张 64×64 的 {@link DynamicTexture}，第一次真正用到时才生成。
 *
 * <p><b>为什么按需生成</b>：档位是配置项（2~10），密度也是。启动时按上限全生成会白做几十张，
 * 而实际用到的往往只有少数几张——一个 16 人对局里最高档都不一定出现。首次用到时生成一张
 * 64×64 的图只有 4096 个像素，代价可以忽略，之后整局都是缓存命中。
 *
 * <p><b>为什么缓存键包含档位数与密度</b>：遮罩内容由 {@code (tier, tiers, density)} 共同决定。
 * 只按 tier 缓存的话，玩家改了配置之后旧遮罩会一直留在显存里，画出来的伤口数量和配置对不上。
 * 这里记住生成时的配置，一旦不一致就整批丢弃重来。
 *
 * <p><b>为什么不需要在资源重载时清理</b>：{@link DynamicTexture} 继承的
 * {@code AbstractTexture#reset} 只是把自己重新注册回 {@code TextureManager}，
 * 内容不会被资源包影响（它不来自任何资源包），所以重载后仍然有效。
 */
public final class WoundMasks {
    /** 注册进 TextureManager 的前缀；会得到 {@code dynamic/kinetic_wounds_N} 这样的名字。 */
    private static final String PREFIX = "kinetic_wounds";

    private static final ResourceLocation[] BASE =
            new ResourceLocation[WoundTier.MAX_TIERS + 1];
    private static final ResourceLocation[] OVERLAY =
            new ResourceLocation[WoundTier.MAX_TIERS + 1];
    private static int builtTiers = -1;
    private static int builtDensity = -1;

    private WoundMasks() {
    }

    /**
     * 取某一档位、某一层的遮罩贴图；档位不在 1..{@link WoundTier#MAX_TIERS} 时返回 {@code null}。
     *
     * <p>会创建并注册 GL 贴图，必须在渲染线程调用（{@link DynamicTexture} 在别的线程会推迟上传，
     * 那样这一帧拿到的是空贴图）。
     */
    @Nullable
    public static ResourceLocation locationFor(int tier, int tiers, int densityPercent, WoundMaskPainter.Layer layer) {
        if (tier <= 0 || tier > WoundTier.MAX_TIERS) {
            return null;
        }
        int clampedTiers = WoundTier.clampTiers(tiers);
        int density = Math.max(0, Math.min(100, densityPercent));
        if (clampedTiers != builtTiers || density != builtDensity) {
            discardAll();
            builtTiers = clampedTiers;
            builtDensity = density;
        }
        ResourceLocation[] slots = layer == WoundMaskPainter.Layer.OVERLAY ? OVERLAY : BASE;
        ResourceLocation cached = slots[tier];
        if (cached != null) {
            return cached;
        }
        int[] pixels = layer == WoundMaskPainter.Layer.OVERLAY
                ? WoundMaskPainter.paintOverlay(tier, clampedTiers, density)
                : WoundMaskPainter.paint(tier, clampedTiers, density);
        ResourceLocation created = create(pixels, tier, layer);
        slots[tier] = created;
        return created;
    }

    @Nullable
    private static ResourceLocation create(int[] pixels, int tier, WoundMaskPainter.Layer layer) {
        NativeImage image = new NativeImage(WoundMaskPainter.SIZE, WoundMaskPainter.SIZE, false);
        try {
            for (int y = 0; y < WoundMaskPainter.SIZE; y++) {
                for (int x = 0; x < WoundMaskPainter.SIZE; x++) {
                    // setPixelRGBA 直接按 ABGR 写内存；灰度三通道相同，只有 alpha 需要在高位。
                    image.setPixelRGBA(x, y, pixels[y * WoundMaskPainter.SIZE + x]);
                }
            }
            return Minecraft.getInstance().getTextureManager().register(PREFIX, new DynamicTexture(image));
        } catch (RuntimeException | Error failure) {
            // 交不出去就必须自己关，否则每次重试都会漏一张 NativeImage。
            image.close();
            Kinetic.LOGGER.warn("Failed to register a wound mask texture for tier {} ({})",
                    tier, layer, failure);
            return null;
        }
    }

    /** 丢弃全部已生成的遮罩；配置变了或者要主动释放显存时调用。 */
    public static void discardAll() {
        var manager = Minecraft.getInstance().getTextureManager();
        release(manager, BASE);
        release(manager, OVERLAY);
        builtTiers = -1;
        builtDensity = -1;
    }

    private static void release(net.minecraft.client.renderer.texture.TextureManager manager,
                               ResourceLocation[] slots) {
        for (int tier = 0; tier < slots.length; tier++) {
            ResourceLocation location = slots[tier];
            if (location == null) {
                continue;
            }
            slots[tier] = null;
            try {
                manager.release(location);
            } catch (RuntimeException failure) {
                Kinetic.LOGGER.warn("Failed to release a wound mask texture {}", location, failure);
            }
        }
    }
}
