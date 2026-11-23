package com.kinetic.client.compat.ragdollified;

import com.kinetic.geckolib3.core.util.MathUtil;
import com.kinetic.geckolib3.geo.animated.GeoModelState;
import com.kinetic.geckolib3.geo.render.built.GeoModel;
import com.kinetic.geckolib3.model.AnimatedGeoModel;
import com.raiiiden.ragdollified.RagdollPart;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 单个布娃娃的 KINETIC 侧状态：捕获的死亡姿态、6 根刚体骨骼的常量偏移、细分子骨骼的次级动力学。
 *
 * <p><b>零跳变</b>：死亡瞬间把 KINETIC 正在绘制的骨骼姿态整份复制下来作为初值；大关节在第一帧解出
 * 常量偏移 {@code C = q_torso(0)⁻¹ · Q_part(0) · L_captured⁻¹}，使首帧姿态与死亡瞬间完全一致。
 * 无论 Ragdollified 由原版人形姿态推出的初始朝向与 KINETIC 姿态差多少，都不会出现突变。
 *
 * <p><b>关节限制</b>：子骨骼只能在表内范围内活动，铰链关节的另外两个轴锁死在死亡瞬间的角度。
 * 越界的初始姿态不会被硬掰回去（那会造成突变），而是由弹簧平滑拉回范围内；
 * 同时硬限制放宽到包含初值，保证积分过程不越界抖动。
 *
 * <p><b>性能</b>：每帧 6 次四元数求解、至多 3 个固定子步的弹簧积分、一次原生 extract。
 * 姿态稳定后进入休眠，跳过积分与重新 extract。
 */
final class KineticRagdollState {
    private static final int ATTR = AnimatedGeoModel.BONE_ATTRIBUTE_COUNT;
    private static final float MODEL_UNIT = 1f / 16f;

    /** 次级动力学固定步长与每帧最大子步数，避免掉帧时积分发散。 */
    private static final float FIXED_STEP = 1f / 60f;
    private static final int MAX_SUBSTEPS = 3;
    private static final float MAX_FRAME_SECONDS = 0.1f;

    /** 休眠阈值。 */
    private static final float SLEEP_VELOCITY = 0.08f;
    private static final float SLEEP_LINEAR = 0.04f;
    private static final float SLEEP_ANGULAR = 0.25f;

    /** 躯干包围盒防穿透参数。 */
    private static final float AVOID_GAIN = 12f;
    private static final float AVOID_MARGIN = 0.02f;
    private static final float AVOID_MAX_SPEED = 25f;

    /** 地面防穿透的余量：末端停在地表之上这么多格。 */
    private static final float GROUND_MARGIN = 0.02f;

    /** 地表最多可以高出躯干这么多格，超过就认为这份高度不可信。 */
    private static final float GROUND_TRUST_MARGIN = 0.25f;

    private static final Vector3f GRAVITY = new Vector3f(0f, -1f, 0f);
    private static final Vector3f TORSO_HALF = RagdollPart.TORSO.getHalfExtents();

    /** 判定「刚体没动」的位置与朝向阈值。 */
    private static final float STILL_POSITION = 1.0e-4f;
    private static final float STILL_ROTATION = 1.0f - 1.0e-7f;

    /** 超过这个帧间隔就认为中间停顿过（例如休眠后重新被撞），按新起点重建运动历史。 */
    private static final float HISTORY_RESET_SECONDS = 0.25f;

    private final int ragdollId;
    private final KineticRagdollRig rig;
    private final float widthScale;
    private final float heightScale;

    /**
     * 当前贴图。实时能力可用时每帧跟随它，能力消失（客户端玩家实体被移除）后继续使用死亡瞬间
     * 捕获的那一份，因此尸体不会因为能力重置而变成无贴图或退回原版人形。
     */
    @Nullable
    private ResourceLocation texture;

    /**
     * 死亡瞬间的模型身份（{@code misc/2_steve} 这类展示路径）。
     *
     * <p>尸体本身不带能力，玩家实体也早已被移除，所以身份只能在建立状态时一并存下来——
     * 受伤外观要靠它判断「这个模型是不是内置玩家模型」。
     */
    private final String modelId;

    /** 独立于玩家的模型实例：共享同一份烘焙模型，但骨骼属性数组归本状态所有。 */
    private final AnimatedGeoModel model;
    private final GeoModel source;
    private final float[] attributes;
    private final float[] base;

    private final GeoModelState renderState = new GeoModelState();

    private final float[] angle;
    private final float[] velocity;
    private final float[] lower;
    private final float[] upper;
    private final Vector3f[] restDirection;
    private final Quaternionf[] staticChainRotation;

    /** 大关节常量偏移 C。 */
    private final Quaternionf[] macroOffset = new Quaternionf[KineticRagdollRig.PART_COUNT];
    private boolean macroReady;

    /**
     * 本帧的地表高度（世界 Y）；{@code NaN} 表示拿不到世界信息，此时不做地面约束。
     *
     * <p>由渲染侧每帧传入一次，状态本身不依赖 Minecraft 类型。
     */
    private float groundY = Float.NaN;

    /** 每帧世界朝向，子骨骼求父级坐标系时使用（滞后一帧，影响可忽略）。 */
    private final Quaternionf[] worldRotation;

    private final Quaternionf parentScratch = new Quaternionf();
    private final Quaternionf tmpA = new Quaternionf();
    private final Quaternionf tmpB = new Quaternionf();
    private final Quaternionf tmpC = new Quaternionf();
    private final Vector3f tmpV1 = new Vector3f();
    private final Vector3f tmpV2 = new Vector3f();
    private final Vector3f tmpV3 = new Vector3f();
    private final Vector3f tmpV4 = new Vector3f();
    private final Vector3f euler = new Vector3f();
    private final Matrix4f matrixScratch = new Matrix4f();

    private final Vector3f[] prevPosition = new Vector3f[KineticRagdollRig.PART_COUNT];
    private final Vector3f[] prevVelocity = new Vector3f[KineticRagdollRig.PART_COUNT];
    private final Vector3f[] acceleration = new Vector3f[KineticRagdollRig.PART_COUNT];
    private final Quaternionf[] prevRotation = new Quaternionf[KineticRagdollRig.PART_COUNT];
    private final float[] partAngularSpeed = new float[KineticRagdollRig.PART_COUNT];
    private boolean historyReady;

    /** 上一帧提取出的关节位置（模型局部空间，格）：自身枢轴与末端枢轴。 */
    private final Vector3f[] jointLocal;
    private final Vector3f[] tipLocal;
    private final Vector3f lastHips = new Vector3f();
    private final Vector3f lastTorsoCenter = new Vector3f();
    private final Quaternionf lastTorsoRotation = new Quaternionf();
    private boolean jointsReady;

    private float accumulator;
    private boolean asleep;

    /** 上一次真正重建姿态时的刚体位姿，用于稳定后跳过重复 extract。 */
    private final Vector3f[] builtPosition = new Vector3f[KineticRagdollRig.PART_COUNT];
    private final Quaternionf[] builtRotation = new Quaternionf[KineticRagdollRig.PART_COUNT];
    private boolean poseBuilt;

    /**
     * 本帧从提供方读到的刚体位姿。由状态自己持有，而不是渲染器里的一份共享静态缓冲。
     *
     * <p>渲染器遇到"这个部位没有位姿"（例如被 {@code renderHiddenPartMask} 隐藏的断肢）时会跳过
     * 该部位，以保留上一帧的值。如果缓冲是渲染器里的静态数组，那个"上一帧的值"就可能是<b>另一具
     * 布娃娃</b>的——同屏有两具尸体时断肢会被画到别人的位置上。放进状态里之后，保留的永远是这具
     * 布娃娃自己的上一帧。
     */
    private final Vector3f[] partPosition = new Vector3f[KineticRagdollRig.PART_COUNT];
    private final Quaternionf[] partRotation = new Quaternionf[KineticRagdollRig.PART_COUNT];

    KineticRagdollState(int ragdollId, KineticRagdollRig rig, GeoModel source, float[] capturedPose,
                    float widthScale, float heightScale, @Nullable ResourceLocation texture,
                    String modelId) {
        this.ragdollId = ragdollId;
        this.rig = rig;
        this.source = source;
        this.widthScale = widthScale;
        this.heightScale = heightScale;
        this.texture = texture;
        this.modelId = modelId;
        this.base = capturedPose.clone();
        this.model = new AnimatedGeoModel(source);
        this.attributes = model.getBoneAttributes();
        System.arraycopy(base, 0, attributes, 0, Math.min(base.length, attributes.length));

        var dynamics = rig.dynamics();
        this.angle = new float[dynamics.length * 3];
        this.velocity = new float[dynamics.length * 3];
        this.lower = new float[dynamics.length * 3];
        this.upper = new float[dynamics.length * 3];
        this.restDirection = new Vector3f[dynamics.length];
        this.staticChainRotation = new Quaternionf[dynamics.length];
        this.jointLocal = new Vector3f[dynamics.length];
        this.tipLocal = new Vector3f[dynamics.length];

        for (int part = 0; part < KineticRagdollRig.PART_COUNT; part++) {
            prevPosition[part] = new Vector3f();
            prevVelocity[part] = new Vector3f();
            acceleration[part] = new Vector3f();
            prevRotation[part] = new Quaternionf();
            macroOffset[part] = new Quaternionf();
            builtPosition[part] = new Vector3f();
            builtRotation[part] = new Quaternionf();
            partPosition[part] = new Vector3f();
            partRotation[part] = new Quaternionf();
        }
        worldRotation = new Quaternionf[rig.boneCount];
        for (int i = 0; i < worldRotation.length; i++) {
            worldRotation[i] = new Quaternionf();
        }

        for (int i = 0; i < dynamics.length; i++) {
            var dynamic = dynamics[i];
            jointLocal[i] = new Vector3f();
            tipLocal[i] = new Vector3f();

            int offset = dynamic.bone * ATTR;
            for (int axis = 0; axis < 3; axis++) {
                float captured = base[offset + axis];
                float min = limit(dynamic, axis, true);
                float max = limit(dynamic, axis, false);
                if (dynamic.hinge && axis != 0) {
                    min = captured;
                    max = captured;
                }
                angle[i * 3 + axis] = captured;
                lower[i * 3 + axis] = KineticRagdollMath.limitLower(captured, min);
                upper[i * 3 + axis] = KineticRagdollMath.limitUpper(captured, max);
            }

            restDirection[i] = computeRestDirection(dynamic);
            staticChainRotation[i] = computeStaticChainRotation(dynamic);
        }
    }

    int ragdollId() {
        return ragdollId;
    }

    /**
     * 本帧的刚体位置缓冲（由状态自己持有）。
     *
     * <p>渲染器直接写这里，缺失的部位自然保留<b>这具布娃娃</b>上一帧的值，不会串到别的布娃娃。
     */
    Vector3f[] partPositions() {
        return partPosition;
    }

    /** 本帧的刚体旋转缓冲；语义见 {@link #partPositions()}。 */
    Quaternionf[] partRotations() {
        return partRotation;
    }

    GeoModel source() {
        return source;
    }

    GeoModelState renderState() {
        return renderState;
    }

    float widthScale() {
        return widthScale;
    }

    float heightScale() {
        return heightScale;
    }

    /** 本帧使用的贴图；能力仍可用时由渲染器每帧刷新。 */
    @Nullable
    ResourceLocation texture() {
        return texture;
    }

    void texture(@Nullable ResourceLocation value) {
        this.texture = value;
    }

    String modelId() {
        return modelId;
    }

    /** 根骨骼（躯干）枢轴，模型内部单位（1/16 格）。 */
    float rootPivotX() {
        return rig.rootPivotX;
    }

    float rootPivotY() {
        return rig.rootPivotY;
    }

    float rootPivotZ() {
        return rig.rootPivotZ;
    }

    boolean isAsleep() {
        return asleep;
    }

    private static float limit(KineticRagdollRig.Dynamic dynamic, int axis, boolean minimum) {
        return switch (axis) {
            case 0 -> minimum ? dynamic.minX : dynamic.maxX;
            case 1 -> minimum ? dynamic.minY : dynamic.maxY;
            default -> minimum ? dynamic.minZ : dynamic.maxZ;
        };
    }

    /**
     * 子骨骼静止方向：由该骨骼枢轴指向其子级枢轴。
     *
     * <p>模型骨骼的静止旋转为 0，内部坐标系与骨骼自身坐标系重合，所以直接用枢轴差。
     * 没有子级枢轴（披风）时按“向下”处理。
     */
    private Vector3f computeRestDirection(KineticRagdollRig.Dynamic dynamic) {
        var direction = new Vector3f(0f, -1f, 0f);
        int tip = dynamic.tipBone;
        if (tip >= 0) {
            var from = model.getSortedBones().get(dynamic.bone).getPivot();
            var to = model.getSortedBones().get(tip).getPivot();
            direction.set(to.x - from.x, to.y - from.y, to.z - from.z);
            if (direction.lengthSquared() > 1.0e-6f) {
                direction.normalize();
            } else {
                direction.set(0f, -1f, 0f);
            }
        }
        return direction;
    }

    private Quaternionf computeStaticChainRotation(KineticRagdollRig.Dynamic dynamic) {
        var result = new Quaternionf();
        if (dynamic.staticChain == null) {
            return result;
        }
        for (int bone : dynamic.staticChain) {
            int offset = bone * ATTR;
            result.mul(KineticRagdollMath.eulerToQuaternion(base[offset], base[offset + 1],
                    base[offset + 2], tmpC));
        }
        return result;
    }

    /**
     * 第一帧解出大关节常量偏移，使布娃娃出现的第一帧与死亡瞬间完全一致。
     *
     * <p>{@code L = (q_torso · C)⁻¹ · Q_part}；首帧令 {@code L} 等于捕获到的局部角度即可解出
     * {@code C = q_torso(0)⁻¹ · Q_part(0) · L_captured⁻¹}。任何坐标系约定差异都会被 {@code C} 吸收。
     */
    void ensureMacroOffsets(Quaternionf[] partRotation) {
        if (macroReady) {
            return;
        }
        var torso = partRotation[RagdollPart.TORSO.index];
        for (int part = 0; part < KineticRagdollRig.PART_COUNT; part++) {
            int offset = rig.partBone(part) * ATTR;
            KineticRagdollMath.solveMacroOffset(torso, partRotation[part],
                    base[offset], base[offset + 1], base[offset + 2], macroOffset[part]);
        }
        macroReady = true;
    }

    /**
     * 推进细分子骨骼。
     *
     * @param partPosition 6 个刚体的世界位置
     * @param partRotation 6 个刚体的世界朝向
     * @param seconds      本帧时间（秒）
     */
    void step(Vector3f[] partPosition, Quaternionf[] partRotation, float seconds, float groundY) {
        this.groundY = groundY;
        updateHistory(partPosition, partRotation, seconds);
        if (!historyReady || !macroReady) {
            // 首帧只记录历史：还没有可用的父级朝向，积分会算错方向。
            return;
        }

        accumulator += Math.min(seconds, MAX_FRAME_SECONDS);
        int steps = 0;
        while (accumulator >= FIXED_STEP && steps < MAX_SUBSTEPS) {
            integrate(FIXED_STEP, partRotation);
            accumulator -= FIXED_STEP;
            steps++;
        }
        if (steps == MAX_SUBSTEPS) {
            // 掉帧时丢弃积压，避免补步雪崩。
            accumulator = 0f;
        }
        asleep = steps == 0 && isSettled();
    }

    private void updateHistory(Vector3f[] partPosition, Quaternionf[] partRotation, float seconds) {
        // 休眠后重新被踢动时，帧间隔会很大；此时按新起点重新建立历史，
        // 否则用陈旧位置算出的速度与加速度会瞬间把子骨骼甩飞。
        if (seconds > HISTORY_RESET_SECONDS) {
            historyReady = false;
            java.util.Arrays.fill(velocity, 0f);
            accumulator = 0f;
        }
        float dt = Math.max(seconds, 1.0e-4f);
        for (int part = 0; part < KineticRagdollRig.PART_COUNT; part++) {
            if (!historyReady) {
                prevPosition[part].set(partPosition[part]);
                prevVelocity[part].set(0f, 0f, 0f);
                prevRotation[part].set(partRotation[part]);
                partAngularSpeed[part] = 0f;
                continue;
            }
            tmpV1.set(partPosition[part]).sub(prevPosition[part]).div(dt);
            acceleration[part].set(tmpV1).sub(prevVelocity[part]).div(dt);
            prevVelocity[part].set(tmpV1);
            prevPosition[part].set(partPosition[part]);

            tmpA.set(partRotation[part]).mul(tmpB.set(prevRotation[part]).conjugate());
            partAngularSpeed[part] = 2f * (float) Math.acos(Mth.clamp(Math.abs(tmpA.w), -1f, 1f)) / dt;
            prevRotation[part].set(partRotation[part]);
        }
        historyReady = true;
    }

    /** 单个固定步长：重力 + 惯性 + 回位 + 阻尼，再夹取到关节范围。 */
    private void integrate(float dt, Quaternionf[] partRotation) {
        var dynamics = rig.dynamics();
        for (int i = 0; i < dynamics.length; i++) {
            var dynamic = dynamics[i];
            Quaternionf parentWorld = parentWorld(dynamic, partRotation);
            if (parentWorld == null) {
                continue;
            }

            // 重力投影到父级坐标系。
            //
            // 这里**刻意不再**叠加父级线加速度（旧实现用相邻帧位置差分求加速度，再乘 inertia
            // 当作滞后项）。提供方在接触求解、休眠切换时会产生毫米级的瞬时修正，二阶层差会把
            // 它放大成十几弧度/秒的角速度——实机表现就是尸体的小腿/前臂在静止时抽搐。
            // 重力项本身就能让关节摆到自然下垂方向并停住，不需要这个噪声源。
            tmpA.set(parentWorld).invert();
            tmpV1.set(GRAVITY).rotate(tmpA);
            SubJointDynamics.gravityTorque(restDirection[i], tmpV1, dynamic.gravity, tmpV3);

            int base = i * 3;
            for (int axis = 0; axis < 3; axis++) {
                float current = angle[base + axis];
                // 没有回位弹簧：只有重力/惯性力矩与阻尼，关节像钟摆一样自然摆动并停在
                // 重力方向上，而不是被拉回死亡瞬间的姿势。
                float torque = tmpV3.get(axis)
                        - dynamic.damping * velocity[base + axis];

                float next = SubJointDynamics.stepAngularVelocity(velocity[base + axis], torque, dt);
                float updated = current + next * dt;

                // 到达关节范围边界时按完全非弹性处理：清零速度，避免贴边抖动。
                if (updated < lower[base + axis]) {
                    updated = lower[base + axis];
                    next = 0f;
                } else if (updated > upper[base + axis]) {
                    updated = upper[base + axis];
                    next = 0f;
                }
                velocity[base + axis] = next;
                angle[base + axis] = updated;
            }

            applyTorsoAvoidance(dynamic, i, parentWorld, dt);
            applyGroundAvoidance(dynamic, i, parentWorld);
        }
    }

    /**
     * 地面防穿透：末端关节低于地面时，给它一个把末端抬起来的角速度增量。
     *
     * <p>大关节由提供方的物理求解器负责落地，但 KINETIC 自己驱动的细分子骨骼（小腿、脚、前臂、
     * 手）不参与那套碰撞。没有回位弹簧之后它们会摆到重力方向，躺着时小腿/脚就会插进地面；
     * 这里用与 {@link #applyTorsoAvoidance} 同一套「力臂 × 推出方向」的力矩把它抬回地面之上。
     *
     * <p>地面高度由调用方每帧传入一次（取尸体所在位置的地表高度），没有世界信息时传入
     * {@code NaN}，本约束整体失效——那时宁可不管，也不要用一个猜出来的平面去掰关节。
     */
    private void applyGroundAvoidance(KineticRagdollRig.Dynamic dynamic, int index,
                                      Quaternionf parentWorld) {
        if (dynamic.tipBone < 0 || !jointsReady || Float.isNaN(groundY)) {
            return;
        }
        // 地表高于躯干说明这份高度不可信（例如尸体卡在方块里，或站在台阶下取到了上层的面）。
        // 这时让位给提供方的碰撞，不要用错误的地面去掰关节。
        if (groundY > lastTorsoCenter.y + GROUND_TRUST_MARGIN) {
            return;
        }
        tmpV1.set(tipLocal[index]);
        modelToWorld(tmpV1);
        float floor = groundY + GROUND_MARGIN;
        if (tmpV1.y >= floor) {
            return;
        }
        tmpV2.set(0f, floor - tmpV1.y, 0f);
        pushTip(dynamic, index, parentWorld, tmpV2);
    }

    /**
     * 躯干包围盒防穿透：末端关节进入躯干时，给它一个把末端推出去的角速度增量。
     *
     * <p>使用上一帧提取出的关节位置，因此有一帧延迟；正常布娃娃速度下位移远小于安全余量，
     * 不会造成可见穿插。只有子骨骼会受这一层约束，刚体骨骼由物理求解器自己的关节锥负责。
     */
    private void applyTorsoAvoidance(KineticRagdollRig.Dynamic dynamic, int index,
                                     Quaternionf parentWorld, float dt) {
        if (dynamic.tipBone < 0 || !jointsReady) {
            return;
        }
        tmpV1.set(tipLocal[index]);
        modelToWorld(tmpV1);
        if (!pushOutOfTorso(tmpV1, tmpV2)) {
            return;
        }
        pushTip(dynamic, index, parentWorld, tmpV2);
    }

    /** 把末端沿世界坐标方向 {@code push} 推开：绕 (力臂 × 推出方向) 施加角速度增量。 */
    private void pushTip(KineticRagdollRig.Dynamic dynamic, int index,
                         Quaternionf parentWorld, Vector3f push) {
        // 力臂 = 末端 - 关节。
        tmpV3.set(jointLocal[index]);
        modelToWorld(tmpV3);
        tmpV4.set(tmpV1).sub(tmpV3);
        float length = tmpV4.length();
        if (length < 0.02f) {
            return;
        }

        // 绕 (力臂 × 推出方向) 旋转即可把末端推离障碍。
        tmpV3.set(tmpV4).cross(push);
        if (tmpV3.lengthSquared() < 1.0e-8f) {
            return;
        }
        tmpV3.normalize();
        tmpA.set(parentWorld).invert();
        tmpV3.rotate(tmpA);

        float strength = Mth.clamp(AVOID_GAIN * push.length() / length, 0f, AVOID_MAX_SPEED);
        int base = index * 3;
        if (dynamic.hinge) {
            velocity[base] += tmpV3.x * strength;
        } else {
            velocity[base] += tmpV3.x * strength;
            velocity[base + 1] += tmpV3.y * strength;
            velocity[base + 2] += tmpV3.z * strength;
        }
    }

    /** 躯干包围盒推出向量（世界坐标）；返回 false 表示未穿透。 */
    private boolean pushOutOfTorso(Vector3f point, Vector3f out) {
        tmpA.set(lastTorsoRotation).invert();
        tmpV4.set(point).sub(lastTorsoCenter).rotate(tmpA);
        var half = TORSO_HALF;
        float dx = half.x + AVOID_MARGIN - Math.abs(tmpV4.x);
        float dy = half.y + AVOID_MARGIN - Math.abs(tmpV4.y);
        float dz = half.z + AVOID_MARGIN - Math.abs(tmpV4.z);
        if (dx <= 0f || dy <= 0f || dz <= 0f) {
            return false;
        }
        if (dx <= dy && dx <= dz) {
            out.set(Math.copySign(dx, tmpV4.x), 0f, 0f);
        } else if (dy <= dz) {
            out.set(0f, Math.copySign(dy, tmpV4.y), 0f);
        } else {
            out.set(0f, 0f, Math.copySign(dz, tmpV4.z));
        }
        out.rotate(lastTorsoRotation);
        return true;
    }

    /** 模型局部坐标（格）→ 世界坐标，沿用上一帧的根变换。 */
    private void modelToWorld(Vector3f point) {
        point.sub(rig.rootPivotX * MODEL_UNIT, rig.rootPivotY * MODEL_UNIT, rig.rootPivotZ * MODEL_UNIT);
        point.mul(widthScale, heightScale, widthScale);
        point.rotate(lastTorsoRotation);
        point.add(lastHips);
    }

    @Nullable
    private Quaternionf parentWorld(KineticRagdollRig.Dynamic dynamic, Quaternionf[] partRotation) {
        return switch (dynamic.parentKind) {
            case PART -> partRotation[dynamic.parentRef];
            case DYNAMIC -> worldRotation[rig.dynamics()[dynamic.parentRef].bone];
            case STATIC_CHAIN -> parentScratch.set(partRotation[dynamic.parentRef])
                    .mul(staticChainRotation[dynamic.index]);
        };
    }

    private boolean isSettled() {
        for (int part = 0; part < KineticRagdollRig.PART_COUNT; part++) {
            if (prevVelocity[part].lengthSquared() > SLEEP_LINEAR * SLEEP_LINEAR) {
                return false;
            }
            if (partAngularSpeed[part] > SLEEP_ANGULAR) {
                return false;
            }
        }
        // 只按速度判定：重力下垂会让子骨骼停在与静止目标不同的平衡角上，
        // 那仍是稳定状态，不该因为它偏离 rest 就永远不睡。
        for (float value : velocity) {
            if (Math.abs(value) > SLEEP_VELOCITY) {
                return false;
            }
        }
        return true;
    }

    /**
     * 把当前物理姿态写进骨骼属性数组。
     *
     * <p>大关节用 {@code L = (q_torso · C)⁻¹ · Q_part} 求局部旋转；子骨骼直接写自身欧拉角，
     * 父级链路由原生 FK 负责。未被驱动的通道（位置、缩放、隐藏、颜色）保留捕获姿态的值。
     */
    void buildPose(Vector3f[] partPosition, Quaternionf[] partRotation) {
        System.arraycopy(base, 0, attributes, 0, Math.min(base.length, attributes.length));

        var torsoRotation = partRotation[RagdollPart.TORSO.index];
        for (int part = 0; part < KineticRagdollRig.PART_COUNT; part++) {
            int bone = rig.partBone(part);
            // L = (q_torso · C)⁻¹ · Q_part
            KineticRagdollMath.macroLocal(torsoRotation, macroOffset[part], partRotation[part], tmpA);
            MathUtil.getEulerAnglesZYX(tmpA, euler);
            int offset = bone * ATTR;
            attributes[offset] = euler.x;
            attributes[offset + 1] = euler.y;
            attributes[offset + 2] = euler.z;
            worldRotation[bone].set(partRotation[part]);
        }

        var dynamics = rig.dynamics();
        for (int i = 0; i < dynamics.length; i++) {
            var dynamic = dynamics[i];
            int index = i * 3;
            int offset = dynamic.bone * ATTR;
            attributes[offset] = angle[index];
            attributes[offset + 1] = angle[index + 1];
            attributes[offset + 2] = angle[index + 2];

            Quaternionf parent = parentWorld(dynamic, partRotation);
            if (parent != null) {
                worldRotation[dynamic.bone].set(parent)
                        .mul(KineticRagdollMath.eulerToQuaternion(angle[index], angle[index + 1],
                                angle[index + 2], tmpC));
            }
        }

        lastTorsoCenter.set(partPosition[RagdollPart.TORSO.index]);
        lastTorsoRotation.set(torsoRotation);
        // 髋部 = 躯干中心沿躯干自身「下」方向偏移半个躯干高（与 RagdollBodyFactory 的 humanoidHip 同一定义）。
        lastHips.set(lastTorsoCenter);
        tmpV1.set(0f, -TORSO_HALF.y, 0f).rotate(torsoRotation);
        lastHips.add(tmpV1);

        for (int part = 0; part < KineticRagdollRig.PART_COUNT; part++) {
            builtPosition[part].set(partPosition[part]);
            builtRotation[part].set(partRotation[part]);
        }
        poseBuilt = true;
    }

    /**
     * 刚体位姿是否与上一次重建姿态时完全相同。
     *
     * <p>布娃娃躺稳后物理不再更新，此时姿态不会再变：配合 {@link #isAsleep()} 可以整帧跳过
     * 骨骼数组重建与原生 extract，直接复用上一次的模型状态。尸体堆积时这是主要的省算手段。
     */
    boolean poseUnchanged(Vector3f[] partPosition, Quaternionf[] partRotation) {
        if (!poseBuilt || !renderState.isValid()) {
            return false;
        }
        for (int part = 0; part < KineticRagdollRig.PART_COUNT; part++) {
            if (!builtPosition[part].equals(partPosition[part], STILL_POSITION)) {
                return false;
            }
            if (Math.abs(builtRotation[part].dot(partRotation[part])) < STILL_ROTATION) {
                return false;
            }
        }
        return true;
    }

    /** 根骨骼（髋部）的世界坐标，渲染时用来定位模型原点。 */
    float hipsX() {
        return lastHips.x;
    }

    float hipsY() {
        return lastHips.y;
    }

    float hipsZ() {
        return lastHips.z;
    }

    /** 提取原生模型状态。 */
    boolean extract() {
        return renderState.extract(model);
    }

    /** 释放原生模型状态。布娃娃消失或被替换时必须调用，否则每具尸体会漏一份 native 资源。 */
    void close() {
        renderState.close();
    }

    /** 读取末端与自身关节位置（模型局部空间），供下一帧防穿透使用。 */
    void readJointPositions() {
        var dynamics = rig.dynamics();
        var poses = renderState.getNativeState().getBonePoses();
        if (poses == null) {
            jointsReady = false;
            return;
        }
        for (int i = 0; i < dynamics.length; i++) {
            int bone = dynamics[i].bone;
            int tip = dynamics[i].tipBone;
            if (bone < 0 || bone >= poses.getBoneCount()) {
                jointsReady = false;
                return;
            }
            var matrix = poses.getPose(bone, matrixScratch);
            jointLocal[i].set(matrix.m30(), matrix.m31(), matrix.m32());
            if (tip >= 0 && tip < poses.getBoneCount()) {
                matrix = poses.getPose(tip, matrixScratch);
                tipLocal[i].set(matrix.m30(), matrix.m31(), matrix.m32());
            }
        }
        jointsReady = true;
    }
}
