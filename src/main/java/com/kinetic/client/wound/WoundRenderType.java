package com.kinetic.client.wound;

import com.kinetic.config.ClientConfig;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/**
 * 伤口叠加层专用的渲染类型：<b>混合 + 裁剪 + 写深度 + 不排序</b>，深度测试分两档。
 *
 * <p>四件事各自解决一个问题，缺一个都会退化成另一种代价：
 *
 * <ul>
 *   <li><b>混合</b>（{@code TRANSLUCENT_TRANSPARENCY}）：遮罩的 alpha 就是"这一点盖住皮肤多少"，
 *       于是边缘渐变、渗血外圈、碳灰都能按半透明叠上去。没有它就只能是非黑即白的裁剪，
 *       也就是"贴纸"观感。</li>
 *   <li><b>裁剪</b>（{@code entity_cutout} 着色器）：它在片元里丢弃 alpha &lt; 0.1。整个模型都会被
 *       再画一遍，而绝大部分像素的遮罩是全透明的；丢掉它们就不必为这些像素付混合与写帧缓冲的代价。</li>
 *   <li><b>写深度</b>：叠加层内部也有前后关系。写深度让近的像素自然压住远的，不需要额外排序。</li>
 *   <li><b>不排序</b>（{@code sortOnUpload = false}）：{@code entityTranslucent} 会在每次刷批时
 *       对全部 quad 排序，这是它已知的最大开销。伤口和身体同深度、且在提交前已经把身体那一批刷出去了，
 *       所以叠加层内部不需要按距离排序。</li>
 * </ul>
 *
 * <p><b>深度测试为什么分两档。</b> 基础层那一遍用 {@code EQUAL}：身体的深度已经写进缓冲，
 * 只有"这个像素确实是不透明的皮肤"的地方深度才相等，于是血不会画在透明贴图的位置上。
 * 第二层那一遍也用 {@code EQUAL}，这一条正是"外层皮肤存在就贴在外层、不存在才贴在内层"的全部机制——
 * 外层皮肤不透明时它写了深度，相等测试通过；外层皮肤透明时那个位置留下的是基础层的深度，
 * 与第二层的位置不等，这一遍整个被丢掉，不会有一层血浮在半空。
 *
 * <p>身体走的是透明渲染类型（{@code ForceCutoutRenderer=false}）时它<b>不写深度</b>，
 * 相等测试会把所有像素都判掉，所以那种配置下退回 {@code LEQUAL}：血照常画在身体上，
 * 代价是外层透明时那层血会浮着——这是"身体不提供深度"的必然结果，也是这一档唯一的降级。
 *
 * <p>类型按 (贴图, 深度测试) 缓存：遮罩是每档位、每层一张的常驻纹理，因此整个会话里只会构造出
 * 与"档位数 × 层数 × 深度档"同数量级的渲染类型，不会出现"每次调用都新建一个 RenderType 身份"
 * 从而破坏批处理的情况。
 */
public final class WoundRenderType extends RenderType {
    private static final Map<ResourceLocation, RenderType> EQUAL_CACHE = new HashMap<>();
    private static final Map<ResourceLocation, RenderType> LEQUAL_CACHE = new HashMap<>();

    private WoundRenderType(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize,
                            boolean affectsCrumbling, boolean sortOnUpload,
                            Runnable setupState, Runnable clearState) {
        super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setupState, clearState);
    }

    /**
     * 取该遮罩贴图对应的渲染类型；同一张贴图与同一个深度档永远返回同一个实例。
     *
     * @param equalDepth true 用 {@code EQUAL}（身体写了深度时），false 用 {@code LEQUAL}
     */
    public static RenderType of(ResourceLocation texture, boolean equalDepth) {
        Map<ResourceLocation, RenderType> cache = equalDepth ? EQUAL_CACHE : LEQUAL_CACHE;
        RenderType cached = cache.get(texture);
        if (cached != null) {
            return cached;
        }
        RenderType created = build(texture, equalDepth);
        cache.put(texture, created);
        return created;
    }

    private static RenderType build(ResourceLocation texture, boolean equalDepth) {
        var state = CompositeState.builder()
                .setShaderState(RENDERTYPE_ENTITY_CUTOUT_SHADER)
                .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                .setDepthTestState(equalDepth ? EQUAL_DEPTH_TEST : LEQUAL_DEPTH_TEST)
                // 与身体保持同一个裁剪选择，否则伤口会在被剔除的那一侧露出来。
                .setCullState(ClientConfig.CULL_BACK_FACES.get() ? CULL : NO_CULL)
                .setLightmapState(LIGHTMAP)
                .setOverlayState(OVERLAY)
                .setWriteMaskState(COLOR_DEPTH_WRITE)
                .createCompositeState(true);
        return create(equalDepth ? "kinetic_wound_overlay_equal" : "kinetic_wound_overlay_lequal",
                DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 256, true, false, state);
    }
}
