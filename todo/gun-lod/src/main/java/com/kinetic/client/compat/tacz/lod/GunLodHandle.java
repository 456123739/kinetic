package com.kinetic.client.compat.tacz.lod;

import com.kinetic.config.ClientConfig;
import com.kinetic.geckolib3.model.AnimatedGeoModel;
import com.kinetic.natives.render.NativeModelState;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;

/**
 * 一把枪的、可以直接交给原生渲染器的 LOD 句柄。
 *
 * <p>构建（TaCZ display 解析 → 减面 → 原生烘焙）全部发生在 worker 线程，产出的就是这个对象；
 * 唯一的渲染线程状态是 {@link NativeModelState}，它在<b>首次渲染时</b>懒创建并只 extract 一次，
 * 之后每帧直接复用。
 *
 * <p>为什么可以只 extract 一次：{@link NativeModelState} 的位姿与骨骼索引由它自己持有
 * （native 侧是 {@code std::vector} 成员，不是帧级 arena），且 LOD 枪是静态姿态、没有动画写入，
 * 所以 extract 的产物在句柄存活期间一直有效。extract 只允许在渲染线程调用，
 * 因此本类不做任何同步——它只在渲染线程被访问。
 *
 * <p>本类不引用任何 TaCZ 类型。
 */
public final class GunLodHandle {
    private final AnimatedGeoModel animatedModel;
    private final ResourceLocation texture;
    /** TaCZ 声明的第三人称缩放，逐轴保存：TaCZ 用的是 {@code scale(v.x, v.y, v.z)}，不是等比。 */
    private final Vector3f scale;
    /** 把模型的 {@code thirdperson_hand} 骨骼摆到手上的平移量（模型单位，KINETIC 坐标系）。 */
    private final Vector3f anchor;

    private NativeModelState nativeState;
    private RenderType renderType;
    private boolean renderTypeCulling;
    private int vertexCount;
    private boolean prepared;
    private boolean broken;

    GunLodHandle(AnimatedGeoModel animatedModel, ResourceLocation texture,
                 Vector3f scale, Vector3f anchor) {
        this.animatedModel = animatedModel;
        this.texture = texture;
        this.scale = scale;
        this.anchor = anchor;
    }

    /**
     * 渲染线程首次使用时做一次 extract。返回 false 表示这个句柄不可用，调用方必须回退。
     * 只会真正尝试一次，失败后永久返回 false。
     */
    public boolean prepare() {
        if (prepared) {
            return nativeState != null;
        }
        prepared = true;
        // 原生状态一旦创建就必须有归宿：extract 抛错、或者任何一步失败，都要在这里关掉它，
        // 否则每次失败的 prepare 都会漏一份原生 state。成功时把所有权交给字段（置 null 跳过 finally）。
        NativeModelState state = null;
        try {
            state = NativeModelState.create();
            if (!state.extract(animatedModel.getModel().bakedModel(),
                    animatedModel.getBoneAttributes())) {
                broken = true;
                return false;
            }
            var count = state.getTotalVertexCount();
            if (count <= 0) {
                broken = true;
                return false;
            }
            nativeState = state;
            vertexCount = count;
            state = null;
            return true;
        } catch (Throwable failure) {
            broken = true;
            nativeState = null;
            vertexCount = 0;
            return false;
        } finally {
            if (state != null) {
                try {
                    state.close();
                } catch (Throwable ignored) {
                    // 释放失败不影响本帧回退。
                }
            }
        }
    }

    /** 仅在 {@link #prepare()} 返回 true 后可用。 */
    public NativeModelState nativeState() {
        return nativeState;
    }

    /** 仅在 {@link #prepare()} 返回 true 后可用。 */
    public int vertexCount() {
        return vertexCount;
    }

    /** 已经解析好的贴图资源位置（{@code assets/<ns>/textures/...png}）。 */
    public ResourceLocation texture() {
        return texture;
    }

    /**
     * 该贴图对应的渲染类型。{@code RenderType.entityCutout/entityCutoutNoCull} 每次调用都会新建对象，
     * 所以这里缓存一份：枪的贴图在句柄存活期间不变。背面裁剪开关是运行时配置，缓存按开关取值，
     * 开关变化时重建一次。
     */
    public RenderType renderType() {
        var culling = ClientConfig.CULL_BACK_FACES.get();
        var cached = renderType;
        if (cached == null || renderTypeCulling != culling) {
            cached = culling
                    ? RenderType.entityCutout(texture)
                    : RenderType.entityCutoutNoCull(texture);
            renderType = cached;
            renderTypeCulling = culling;
        }
        return cached;
    }

    /** 该枪在 TaCZ display 里声明的第三人称缩放（逐轴）；用户配置的额外倍率由渲染方另行乘上。 */
    public Vector3f scale() {
        return scale;
    }

    /**
     * 把模型的 {@code thirdperson_hand} 骨骼摆到手上的平移量，单位是模型单位（1/16 格），
     * 且已经换到 KINETIC 的坐标系（X 取反）。
     *
     * <p>这是 TaCZ 那套"原点路径"变换里<b>唯一可以确定的部分</b>：它的作用就是把
     * {@code thirdperson_hand} 这个节点搬到姿态原点，而该节点在两个已知模型里都没有旋转、
     * 父节点枢轴都是 (0,0,0)，所以剩下的就是一次纯平移 {@code -pivot * scale}。
     */
    public Vector3f anchor() {
        return anchor;
    }

    public int boneCount() {
        return animatedModel.getSortedBones().size();
    }

    /** 供诊断使用。 */
    public boolean isBroken() {
        return broken;
    }

    /**
     * 只释放 {@code extract} 出来的原生状态；烘焙模型保留，所以之后可以重新 {@link #prepare()}。
     *
     * <p>给"暂时放掉、稍后还要用"的场景（例如同一个句柄被重新激活）。
     */
    public void release() {
        var state = nativeState;
        nativeState = null;
        renderType = null;
        prepared = false;
        broken = false;
        vertexCount = 0;
        if (state != null) {
            try {
                state.close();
            } catch (Throwable ignored) {
                // 释放失败无关紧要。
            }
        }
    }

    /**
     * 彻底释放：原生状态 <b>+</b> 烘焙模型。句柄被缓存淘汰或资源重载丢弃时调用，之后不可再用。
     *
     * <p>为什么必须连烘焙模型一起关：每个句柄持有一份原生烘焙模型（约 200~300 KB）。
     * 一局里换过几十把枪就会淘汰几十次，重载一次更是把整张表全丢掉；只关原生状态的话，
     * 这些模型要等 GC 触发 Cleaner 才回收，在长局里会积累成可观的常驻原生内存。
     *
     * <p>顺序是先关状态再关模型：{@code NativeModelState} 里持有烘焙模型的裸指针，
     * 反过来会变成 use-after-free。
     */
    public void dispose() {
        release();
        try {
            var model = animatedModel.getModel();
            if (model != null) {
                model.close();
            }
        } catch (Throwable ignored) {
            // 释放失败无关紧要。
        }
    }
}
