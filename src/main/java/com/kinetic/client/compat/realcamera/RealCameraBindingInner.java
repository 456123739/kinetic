package com.kinetic.client.compat.realcamera;

import com.kinetic.Kinetic;
import com.kinetic.config.ClientConfig;
import com.xtracr.realcamera.api.BindResult;
import com.xtracr.realcamera.api.RealCameraAPI;
import com.xtracr.realcamera.config.BindTarget;
import com.xtracr.realcamera.config.ConfigFile;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 真实相机的绑定适配层。
 *
 * <p><b>与真实相机自带 KINETIC 兼容的区别</b>：它自己那份（{@code KineticCompat}）每帧把相机实体离屏重画
 * 4~8 遍、用捕获顶点的缓冲源接住顶点、再按 UV 多边形反查图元；只要 KINETIC 那一遍渲染因为任何原因没
 * 产出几何，绑定就判失败，超过保留帧数后真实相机还会把自己整个关掉。这里改成<b>直接读 KINETIC 自己
 * 烘焙好的几何与骨骼姿态</b>：不额外画一次模型、不做 UV 反查缓存、不受「模型本帧有没有被画」影响。
 *
 * <p><b>优先级</b>：注册在 {@code 0}。真实相机按优先级从高到低取第一个可用的结果，而它自己的
 * {@code KineticCompat} 在 {@code -100}，因此只要这里给出了结果，那条昂贵路径就完全不会执行。用户显式
 * 打开「经典绑定模式」时优先级 {@code 100} 的 {@code LegacyBindingMode} 仍然优先，行为不变。
 *
 * <p><b>失败即让位</b>：这里任何一步拿不到数据（模型没加载、贴图未知、UV 在模型上找不到面）都返回
 * 空结果，真实相机会照常走它自己的兜底逻辑。也就是说本层只可能让绑定更快更准，不会让它消失。
 *
 * <p>本类只应在真实相机存在时被加载（见 {@link RealCameraCompat#init()}）。
 */
final class RealCameraBindingInner {
    /**
     * 比真实相机自己的 {@code KineticCompat}（-100）高，比用户的经典绑定模式（100）低。
     */
    private static final int PRIORITY = 0;

    private static final Vector3f POSITION = new Vector3f();
    private static final Vector3f FORWARD = new Vector3f();
    private static final Vector3f UPWARD = new Vector3f();

    /** 已经就「找不到面」报过日志的「贴图 + 模型」组合，避免逐帧刷屏。 */
    private static final Set<String> LOGGED_UNRESOLVED = ConcurrentHashMap.newKeySet();

    /** 解算抛出过异常后整个会话让位，见 {@link #computeBindResult}。 */
    private static volatile boolean disabledByFailure;

    private RealCameraBindingInner() {
    }

    static void register() {
        RealCameraAPI.registerFunction(PRIORITY, RealCameraBindingInner::computeBindResult);
    }

    /**
     * 真实相机每帧调用的绑定函数。
     *
     * <p><b>必须整体兜住异常</b>：对方的 {@code RealCameraAPI.computeBindResult} 直接 {@code apply}
     * 本函数，没有 try/catch，而这个调用发生在 {@code GameRenderer#renderLevel} 里。任何漏出去的异常
     * 都会直接崩掉客户端。
     *
     * <p>第一次异常之后本层<b>整个会话不再尝试</b>：解算的输入（模型、骨骼、配置）在同一份模型上
     * 是确定的，抛一次就会每帧都抛，而每帧构造一次异常与堆栈的代价正好落在最不该掉帧的位置。退回
     * 对方自己的路径是完整可用的降级，所以这里选"一次失败即永久让位"，而不是逐帧重试。
     */
    private static BindResult computeBindResult(Minecraft minecraft, float partialTick) {
        if (disabledByFailure) {
            return BindResult.EMPTY;
        }
        try {
            return resolve(minecraft, partialTick);
        } catch (Throwable failure) {
            disabledByFailure = true;
            Kinetic.LOGGER.warn("Real Camera camera binding failed; KINETIC yields to Real Camera's "
                    + "own model scan for the rest of this session", failure);
            return BindResult.EMPTY;
        }
    }

    private static BindResult resolve(Minecraft minecraft, float partialTick) {
        if (!modelBindingEnabled()) {
            return BindResult.EMPTY;
        }
        var source = RealCameraBindSource.resolve(minecraft, partialTick);
        if (source == null) {
            return BindResult.EMPTY;
        }

        String renderedTexture = source.texture().toString();
        for (BindTarget target : ConfigFile.config().getBindTargetList()) {
            if (target.isEmpty()) {
                continue;
            }
            String wantedTexture = target.textureId();
            if (wantedTexture == null || !renderedTexture.contains(wantedTexture)) {
                continue;
            }
            var result = evaluate(source, target);
            if (result != null) {
                return result;
            }
        }
        logUnresolved(renderedTexture, source.modelId());
        return BindResult.EMPTY;
    }

    /**
     * 一个「贴图 + 模型」组合只记一次「配置里的 UV 在这份模型上找不到面」。
     *
     * <p>这是本层唯一会静默让位的情形：真实相机会退回它自己的顶点捕获路径，而那条路径在同样的 UV 上
     * 同样找不到东西，最终只表现为「绑定失败」。不记日志的话，用户只能看到一个没有解释的提示。
     */
    private static void logUnresolved(String renderedTexture, String modelId) {
        if (!LOGGED_UNRESOLVED.add(renderedTexture + '|' + modelId)) {
            return;
        }
        Kinetic.LOGGER.info("[RealCamera] none of the configured bind targets match texture {} "
                + "(model {}); re-pick the binding point in Real Camera's model view screen",
                renderedTexture, modelId);
    }

    private static BindResult evaluate(RealCameraBindSource.Source source, BindTarget target) {
        var config = target.targetConfig();
        if (config == null) {
            return null;
        }
        var uv = new CameraBindSolver.Target(config.posU(), config.posV(),
                config.forwardU(), config.forwardV(), config.upwardU(), config.upwardV());
        var plan = source.solver().plan(uv);
        if (!plan.resolved()) {
            return null;
        }
        if (!source.solver().evaluate(plan, uv, source.bones(),
                source.modelToSpace(), source.normalToSpace(), POSITION, FORWARD, UPWARD)) {
            return null;
        }
        // 零长度朝向会让 BindResult#setForward 归一化出 NaN，进而把相机的旋转矩阵毁掉；宁可让位。
        if (!(FORWARD.lengthSquared() > 1.0e-12f) || !(UPWARD.lengthSquared() > 1.0e-12f)) {
            return null;
        }

        var result = new BindResult(target, false);
        result.setPosition(toVec3(POSITION, source.originOffset()));
        // 朝向是方向量，平移不影响它，因此不需要减掉实体位置。
        result.setForward(toVec3(FORWARD, null));
        result.setUpward(toVec3(UPWARD, null));
        return result.available() ? result : null;
    }

    /**
     * 把解算结果搬到真实相机要的空间。
     *
     * <p>真实相机在 {@code Camera#setup} 里把绑定位置加上「相机实体的插值位置」。玩家模型本来就画在
     * 实体局部空间，所以不需要补偿；布娃娃画在世界空间，必须先减掉那个位置。
     */
    private static Vec3 toVec3(Vector3f value, Vec3 originOffset) {
        if (originOffset == null) {
            return new Vec3(value.x, value.y, value.z);
        }
        return new Vec3(value.x - originOffset.x, value.y - originOffset.y, value.z - originOffset.z);
    }

    private static boolean modelBindingEnabled() {
        try {
            return ClientConfig.REAL_CAMERA_MODEL_BINDING.get();
        } catch (RuntimeException ignored) {
            // 配置尚未加载（理论上到不了这里）：按开启处理，此时绑定本来也不会被调用。
            return true;
        }
    }
}
