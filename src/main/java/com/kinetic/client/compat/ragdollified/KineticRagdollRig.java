package com.kinetic.client.compat.ragdollified;

import com.kinetic.geckolib3.core.molang.util.StringPool;
import com.kinetic.geckolib3.model.AnimatedGeoBone;
import com.kinetic.geckolib3.model.AnimatedGeoModel;
import com.raiiiden.ragdollified.RagdollPart;
import it.unimi.dsi.fastutil.ints.Int2ReferenceMap;
import it.unimi.dsi.fastutil.objects.ReferenceList;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * KINETIC 骨骼与 Ragdollified 刚体之间的映射表、关节活动范围与次级动力学参数。
 *
 * <p>Ragdollified 的玩家骨架只有 6 个刚体（{@link RagdollPart}），而 KINETIC 的 Steve/Alex 有 61 根骨骼。
 * 因此这里做两层映射：
 * <ul>
 *   <li><b>刚体骨骼</b>：6 个刚体的世界朝向直接驱动躯干/头/双臂/双腿 6 根“大关节”骨骼；</li>
 *   <li><b>细分子骨骼</b>：肘、腕、膝、踝、披风等由 KINETIC 侧的阻尼弹簧模拟，受关节角度限制约束，
 *       既不冻结成僵直姿态，也不会突然回弹。</li>
 * </ul>
 *
 * <p><b>角度约定</b>：骨骼属性数组用弧度，且 X/Y 轴相对 Blockbench 的角度取反（见模型解析层）。
 * 本表内的限制值以“属性约定”的度数书写，解析时转弧度。肘的屈曲为 +X、膝的屈曲为 -X，
 * 依据是子骨骼枢轴位于父骨骼枢轴下方、且模型正面为 -Z 这一几何关系。
 *
 * <p><b>为什么大关节不需要限制表</b>：Ragdollified 的物理求解器已经按它的 {@code humanoidJointLimits}
 * 配置约束了颈/髋/肩的活动锥，刚体之间不会互相穿透。这里只约束由 KINETIC 自己驱动的细分子骨骼，
 * 并额外加一层针对躯干包围盒的防穿透安全网。
 */
public final class KineticRagdollRig {
    /** 玩家骨架的刚体数量，与 {@link RagdollPart} 一致。 */
    public static final int PART_COUNT = 6;

    /** 刚体 index → KINETIC 骨骼名。顺序必须与 {@link RagdollPart#index} 对齐。 */
    private static final String[] PART_BONE_NAMES = new String[PART_COUNT];

    static {
        PART_BONE_NAMES[RagdollPart.TORSO.index] = "AllBody";
        PART_BONE_NAMES[RagdollPart.HEAD.index] = "Head";
        PART_BONE_NAMES[RagdollPart.LEFT_LEG.index] = "LeftLeg";
        PART_BONE_NAMES[RagdollPart.RIGHT_LEG.index] = "RightLeg";
        PART_BONE_NAMES[RagdollPart.LEFT_ARM.index] = "LeftArm";
        PART_BONE_NAMES[RagdollPart.RIGHT_ARM.index] = "RightArm";
    }

    /** 子骨骼父级类型。 */
    public enum ParentKind {
        /** 父级是某个刚体骨骼，其世界朝向就是该刚体的朝向。 */
        PART,
        /** 父级是另一个受驱动的子骨骼。 */
        DYNAMIC,
        /** 父级是某个刚体骨骼之下的静态骨骼链（例如披风挂在 {@code UpperBody} 上）。 */
        STATIC_CHAIN
    }

    /**
     * 子骨骼描述。
     *
     * @param hinge   铰链关节：只允许绕自身 X 轴转动，Y/Z 锁定在死亡瞬间的角度
     * @param tipBone 用于防穿透检测的“末端”骨骼（其枢轴即关节位置），没有则为 {@code null}
     */
    private record BoneSpec(String name, String parentName, boolean hinge,
                            float minX, float maxX, float minY, float maxY, float minZ, float maxZ,
                            float damping, float gravity, float inertia,
                            float tipRadius, @Nullable String tipBone) {
    }

    /**
     * 子骨骼表。
     *
     * <p><b>没有回位弹簧</b>：关节只受重力、父级惯性、阻尼与活动范围约束，因此肢体像钟摆一样
     * 自然摆动并停在重力方向上，而不是被拉回死亡瞬间的姿势。{@code gravity} 本身就是恢复力矩，
     * 阻尼比 ζ=c/(2√gravity) 约 0.6~0.75：摆动一两次就停下，既不像刚体一样僵硬，也不会长时间晃。
     */
    private static final BoneSpec[] SPECS = {
            // 前臂：肘铰链。屈曲 = +X（手朝身体前方抬起），过伸限制在 -12°。
            new BoneSpec("RightForeArm", "RightArm", true,
                    -12f, 145f, 0f, 0f, 0f, 0f,
                    8.0f, 34f, 2.2f, 0.09f, "RightHand"),
            new BoneSpec("LeftForeArm", "LeftArm", true,
                    -12f, 145f, 0f, 0f, 0f, 0f,
                    8.0f, 34f, 2.2f, 0.09f, "LeftHand"),
            // 手掌：球窝关节，只能小幅度摆动。
            new BoneSpec("RightHand", "RightForeArm", false,
                    -65f, 65f, -25f, 25f, -45f, 45f,
                    13f, 18f, 1.2f, 0.07f, null),
            new BoneSpec("LeftHand", "LeftForeArm", false,
                    -65f, 65f, -25f, 25f, -45f, 45f,
                    13f, 18f, 1.2f, 0.07f, null),
            // 小腿：膝铰链。屈曲 = -X（脚跟朝身后收起），反向几乎不能动。
            new BoneSpec("RightLowerLeg", "RightLeg", true,
                    -150f, 4f, 0f, 0f, 0f, 0f,
                    8.5f, 36f, 2.2f, 0.09f, "RightFoot"),
            new BoneSpec("LeftLowerLeg", "LeftLeg", true,
                    -150f, 4f, 0f, 0f, 0f, 0f,
                    8.5f, 36f, 2.2f, 0.09f, "LeftFoot"),
            // 脚：踝关节，背屈 +X / 跖屈 -X。
            new BoneSpec("RightFoot", "RightLowerLeg", false,
                    -45f, 32f, -14f, 14f, -18f, 18f,
                    14f, 16f, 1.0f, 0.08f, null),
            new BoneSpec("LeftFoot", "LeftLowerLeg", false,
                    -45f, 32f, -14f, 14f, -18f, 18f,
                    14f, 16f, 1.0f, 0.08f, null),
            // 披风：挂在 UpperBody 上，主要向后飘；不能向前穿进躯干。
            new BoneSpec("cape", "UpperBody", false,
                    -95f, 10f, -22f, 22f, -38f, 38f,
                    4.5f, 26f, 2.6f, 0.10f, null),
    };

    /** 披风父级静态链：自所属刚体骨骼（躯干）向下，不含刚体骨骼本身。 */
    private static final String[] CAPE_CHAIN = {"UpBody", "UpperBody"};

    public final int boneCount;

    /** 刚体 index → 骨骼下标。 */
    private final int[] partBone = new int[PART_COUNT];

    /** 根骨骼（躯干）的枢轴，模型内部单位（1/16 格）。 */
    public final float rootPivotX;
    public final float rootPivotY;
    public final float rootPivotZ;

    private final Dynamic[] dynamics;

    private KineticRagdollRig(int boneCount, float rootPivotX, float rootPivotY, float rootPivotZ,
                          Dynamic[] dynamics) {
        this.boneCount = boneCount;
        this.rootPivotX = rootPivotX;
        this.rootPivotY = rootPivotY;
        this.rootPivotZ = rootPivotZ;
        this.dynamics = dynamics;
    }

    public int partBone(int partIndex) {
        return partBone[partIndex];
    }

    public Dynamic[] dynamics() {
        return dynamics;
    }

    /** 一根受次级动力学驱动的子骨骼。 */
    public static final class Dynamic {
        public final String name;
        /** 在本表 {@link #dynamics()} 中的下标。 */
        public final int index;
        public final int bone;
        /** 该子骨骼所属的刚体骨骼（沿父级链上溯得到），用于取父级线加速度。 */
        public final int macroPart;
        public final ParentKind parentKind;
        /** {@code PART} → 刚体 index；{@code DYNAMIC} → 子骨骼下标；{@code STATIC_CHAIN} → 所属刚体 index。 */
        public final int parentRef;
        /** {@code STATIC_CHAIN} 时，自所属刚体骨骼向下到父级的静态骨骼下标。 */
        public final int[] staticChain;
        public final boolean hinge;
        public final float minX, maxX, minY, maxY, minZ, maxZ;
        public final float damping, gravity, inertia;
        public final float tipRadius;
        /** 防穿透检测用的末端骨骼下标，-1 表示不检测。 */
        public final int tipBone;

        private Dynamic(String name, int index, int bone, int macroPart,
                        ParentKind parentKind, int parentRef, int[] staticChain,
                        boolean hinge, float minX, float maxX, float minY, float maxY, float minZ, float maxZ,
                        float damping, float gravity, float inertia,
                        float tipRadius, int tipBone) {
            this.name = name;
            this.index = index;
            this.bone = bone;
            this.macroPart = macroPart;
            this.parentKind = parentKind;
            this.parentRef = parentRef;
            this.staticChain = staticChain;
            this.hinge = hinge;
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
            this.minZ = minZ;
            this.maxZ = maxZ;
            this.damping = damping;
            this.gravity = gravity;
            this.inertia = inertia;
            this.tipRadius = tipRadius;
            this.tipBone = tipBone;
        }
    }

    /**
     * 在给定模型上解析本表。
     *
     * @return 解析结果；6 根刚体骨骼任一缺失（例如自定义模型换了骨架）时返回 {@code null}，
     *         调用方据此回退到 Ragdollified 自身的原版人形渲染。
     */
    @Nullable
    public static KineticRagdollRig resolve(AnimatedGeoModel model) {
        Int2ReferenceMap<AnimatedGeoBone> map = model.getBoneMap();
        ReferenceList<AnimatedGeoBone> sorted = model.getSortedBones();
        int boneCount = sorted.size();

        int[] partBone = new int[PART_COUNT];
        for (int part = 0; part < PART_COUNT; part++) {
            var bone = lookup(map, PART_BONE_NAMES[part]);
            if (bone == null) {
                return null;
            }
            partBone[part] = sorted.indexOf(bone);
        }

        var rootPivot = lookup(map, PART_BONE_NAMES[RagdollPart.TORSO.index]).getPivot();

        List<Dynamic> resolved = new ArrayList<>(SPECS.length);
        for (var spec : SPECS) {
            var bone = lookup(map, spec.name());
            if (bone == null) {
                continue;
            }

            ParentKind kind;
            int parentRef;
            int[] chain = null;

            if (isPartBoneName(spec.parentName())) {
                kind = ParentKind.PART;
                parentRef = partIndexOfName(spec.parentName());
            } else if (isDynamicName(spec.parentName())) {
                // 父级必须已经解析完成，否则说明表顺序有误。
                parentRef = resolvedIndexOfName(resolved, spec.parentName());
                if (parentRef < 0) {
                    continue;
                }
                kind = ParentKind.DYNAMIC;
            } else if ("cape".equals(spec.name()) && lookup(map, spec.parentName()) != null) {
                kind = ParentKind.STATIC_CHAIN;
                parentRef = RagdollPart.TORSO.index;
                chain = resolveChain(map, sorted, CAPE_CHAIN);
                if (chain == null) {
                    continue;
                }
            } else {
                // 父级无法确定：宁可不驱动这根骨骼，也不要让它乱转。
                continue;
            }

            int tipBone = -1;
            if (spec.tipBone() != null) {
                var tip = lookup(map, spec.tipBone());
                if (tip != null) {
                    tipBone = sorted.indexOf(tip);
                }
            }

            int macroPart = kind == ParentKind.DYNAMIC ? resolved.get(parentRef).macroPart : parentRef;
            resolved.add(new Dynamic(spec.name(), resolved.size(), sorted.indexOf(bone), macroPart,
                    kind, parentRef, chain,
                    spec.hinge(),
                    (float) Math.toRadians(spec.minX()), (float) Math.toRadians(spec.maxX()),
                    (float) Math.toRadians(spec.minY()), (float) Math.toRadians(spec.maxY()),
                    (float) Math.toRadians(spec.minZ()), (float) Math.toRadians(spec.maxZ()),
                    spec.damping(), spec.gravity(), spec.inertia(),
                    spec.tipRadius(), tipBone));
        }

        return new KineticRagdollRig(boneCount, rootPivot.x, rootPivot.y, rootPivot.z,
                resolved.toArray(new Dynamic[0]));
    }

    @Nullable
    private static int[] resolveChain(Int2ReferenceMap<AnimatedGeoBone> map,
                                      ReferenceList<AnimatedGeoBone> sorted, String[] names) {
        int[] chain = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            var bone = lookup(map, names[i]);
            if (bone == null) {
                return null;
            }
            chain[i] = sorted.indexOf(bone);
        }
        return chain;
    }

    private static boolean isPartBoneName(String name) {
        for (var candidate : PART_BONE_NAMES) {
            if (candidate.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static int partIndexOfName(String name) {
        for (int i = 0; i < PART_BONE_NAMES.length; i++) {
            if (PART_BONE_NAMES[i].equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isDynamicName(String name) {
        for (var spec : SPECS) {
            if (spec.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static int resolvedIndexOfName(List<Dynamic> resolved, String name) {
        for (int i = 0; i < resolved.size(); i++) {
            if (resolved.get(i).name.equals(name)) {
                return i;
            }
        }
        return -1;
    }

    @Nullable
    private static AnimatedGeoBone lookup(Int2ReferenceMap<AnimatedGeoBone> map, String name) {
        return map.get(StringPool.computeIfAbsent(name));
    }

    private KineticRagdollRig() {
        throw new AssertionError();
    }
}
