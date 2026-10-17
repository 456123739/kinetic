package com.kinetic.client.compat.realcamera;

import com.kinetic.capability.PlayerAnimatableCapability;
import com.kinetic.capability.PlayerAnimatableCapabilityProvider;
import com.kinetic.client.event.RegisterEntityRenderersEvent;
import com.kinetic.geckolib3.core.util.Color;
import com.kinetic.geckolib3.geo.render.built.GeoModel;
import com.kinetic.natives.render.BonePoseView;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 「这一帧该按谁的模型绑定相机」的唯一判定点。
 *
 * <p>两条来源：
 * <ol>
 *   <li><b>活着的本地玩家</b> —— 用玩家自己的 KINETIC 模型。姿态由
 *       {@link com.kinetic.geckolib3.geo.GeoReplacedEntityRenderer#applyModelPose} 算出来
 *       （与绘制共用同一份实现），骨骼矩阵来自本帧 {@code levelImmutable()} 上下文烘焙出的原生状态；</li>
 *   <li><b>已死亡的本地玩家</b> —— 若 Ragdollified 正用 KINETIC 模型画这具尸体，则改用布娃娃：
 *       相机跟着尸体走。这既让死亡视角准确实用，也避免了「玩家模型已被隐藏 → 捕获不到顶点 →
 *       真实相机判定绑定失败并自我关闭」。这一条通过 {@link #setRagdollProvider} 注入，
 *       从而在没装 Ragdollified 时不会加载到它的任何类型。</li>
 * </ol>
 *
 * <p>本类<b>不引用真实相机的任何类型</b>，只产出「解算器 + 骨骼姿态 + 姿态矩阵 + 贴图」。真实相机
 * 缺席时本类也能安全加载；真实相机的适配层见 {@link RealCameraBindingInner}。
 *
 * <p>{@link Source} 里可能含共享 scratch 矩阵：只在本次 {@code resolve} 返回后、下一次调用之前有效。
 */
public final class RealCameraBindSource {
    /** 布娃娃来源。由 Ragdollified 兼容层在运行时初始化时注入；未安装时保持 {@code null}。 */
    public interface RagdollProvider {
        @Nullable Source resolve(Player player, float partialTick);
    }

    /**
     * 一次绑定所需的全部输入。
     *
     * @param solver        UV → 几何解算器（按模型缓存）
     * @param bones         本帧的骨骼姿态矩阵
     * @param modelToSpace  「模型局部 → 目标空间」的位置矩阵
     * @param normalToSpace 「模型局部 → 目标空间」的法线矩阵
     * @param texture       本帧身体实际使用的贴图，用来与真实相机配置里的 {@code textureId} 对齐
     * @param modelId       当前模型 id，仅用于日志
     * @param originOffset  真实相机会把绑定位置加上「相机实体的插值位置」；布娃娃路径需要先减掉它
     */
    public record Source(CameraBindSolver solver, BonePoseView bones,
                         Matrix4f modelToSpace, Matrix3f normalToSpace,
                         ResourceLocation texture, String modelId,
                         @Nullable Vec3 originOffset) {
    }

    /** 每个模型的解算器。键是弱引用：模型被卸载后表项自行消失，不会吊住原生资源。 */
    private static final Map<GeoModel, CameraBindSolver> SOLVERS = new WeakHashMap<>();

    private static final PoseStack SCRATCH_POSE = new PoseStack();
    private static final Matrix4f SCRATCH_MODEL = new Matrix4f();
    private static final Matrix3f SCRATCH_NORMAL = new Matrix3f();

    @Nullable
    private static volatile RagdollProvider ragdollProvider;

    private RealCameraBindSource() {
    }

    /** 由 Ragdollified 兼容层注入；未安装 Ragdollified 时永远不被调用。 */
    public static void setRagdollProvider(@Nullable RagdollProvider provider) {
        ragdollProvider = provider;
    }

    /**
     * 解析本帧的绑定来源。
     *
     * @return {@code null} 表示 KINETIC 这一帧给不出结果（模型没加载 / 没烘焙 / 贴图未知……），调用方
     *         应当放弃这次绑定，让真实相机走它自己的兜底路径
     */
    @Nullable
    public static Source resolve(Minecraft minecraft, float partialTick) {
        Entity cameraEntity = minecraft.getCameraEntity();
        if (!(cameraEntity instanceof AbstractClientPlayer player)) {
            return null;
        }

        var ragdoll = ragdollProvider;
        if (ragdoll != null && player.isDeadOrDying()) {
            var source = ragdoll.resolve(player, partialTick);
            if (source != null) {
                return source;
            }
            // 布娃娃还没构造好、或本帧没接管绘制：继续走玩家模型这条，不要在这一帧直接放弃绑定。
        }

        var capability = player.getCapability(PlayerAnimatableCapabilityProvider.CAP).orElse(null);
        if (capability == null || !capability.isInitializedAndEnabled() || !capability.hasRenderableModel()) {
            return null;
        }
        return forPlayer(capability, partialTick);
    }

    /**
     * 用玩家渲染器把姿态算进共享 scratch，再把矩阵交给调用方。
     *
     * <p>姿态与贴图都取自<b>同一份</b>渲染数据：{@code data.renderedTexture} 是本帧真正画出来的
     * 那张贴图（已过 {@code SpecialPlayerRenderEvent} 覆盖链），因此内置 Steve/Alex 模型能正确匹配
     * 真实相机默认配置里的 {@code minecraft:textures/entity/player/} 与 {@code minecraft:skins/}。
     */
    @Nullable
    public static Source forPlayer(PlayerAnimatableCapability capability, float partialTick) {
        var data = capability.getMainRenderData();
        if (data == null || data.renderedTexture == null || !data.modelState.isValid()) {
            return null;
        }
        var geoModel = data.modelState.getModel();
        if (geoModel == null || geoModel.bakedModel() == null) {
            return null;
        }
        var bones = data.modelState.getNativeState().getBonePoses();
        if (bones == null || bones.getBoneCount() <= 0) {
            return null;
        }

        var renderer = RegisterEntityRenderersEvent.getPlayerRenderer();
        SCRATCH_POSE.pushPose();
        try {
            renderer.applyModelPose(capability.getEntity(), data, SCRATCH_POSE, partialTick);
            // 与绘制时一致：preRender 负责模型自身的宽高缩放。默认实现只做 scale，因此这里传 null
            // 缓冲源是安全的；若将来有人覆写 preRender 并用到了缓冲源，这里必须同步修改。
            renderer.preRender(data, capability, SCRATCH_POSE, null, 0, 0, Color.WHITE);
            SCRATCH_MODEL.set(SCRATCH_POSE.last().pose());
            SCRATCH_NORMAL.set(SCRATCH_POSE.last().normal());
        } finally {
            SCRATCH_POSE.popPose();
        }
        return new Source(solverFor(geoModel), bones, SCRATCH_MODEL, SCRATCH_NORMAL,
                data.renderedTexture, capability.getModelId(), null);
    }

    /** 取（并按模型缓存）某个模型的 UV → 几何解算器。 */
    public static CameraBindSolver solverFor(GeoModel geoModel) {
        var cached = SOLVERS.get(geoModel);
        if (cached != null) {
            return cached;
        }
        var solver = new CameraBindSolver(CameraBindGeometry.extract(geoModel.bakedModel()));
        SOLVERS.put(geoModel, solver);
        return solver;
    }
}
