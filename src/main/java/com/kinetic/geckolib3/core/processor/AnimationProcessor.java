package com.kinetic.geckolib3.core.processor;

import com.kinetic.Kinetic;
import com.kinetic.client.animation.SlideLock;
import com.kinetic.client.sound.instance.SoundInstanceManager;
import com.kinetic.geckolib3.core.controller.IAnimationController;
import com.kinetic.geckolib3.core.controller.IBoneAnimationQueue;
import com.kinetic.geckolib3.core.event.predicate.AnimationEvent;
import com.kinetic.geckolib3.core.keyframe.AnimationVec3;
import com.kinetic.geckolib3.core.manager.AnimationData;
import com.kinetic.geckolib3.core.molang.context.MolangContext;
import com.kinetic.geckolib3.core.molang.storage.IForeignVariableStorage;
import com.kinetic.geckolib3.core.molang.storage.MolangMemory;
import com.kinetic.geckolib3.core.molang.util.StringPool;
import com.kinetic.geckolib3.core.molang.value.IValue;
import com.kinetic.geckolib3.core.snapshot.BoneTopLevelSnapshot;
import com.kinetic.geckolib3.core.util.MathUtil;
import com.kinetic.geckolib3.model.AnimatableEntity;
import com.kinetic.geckolib3.model.AnimatedGeoModel;
import com.kinetic.molang.runtime.ExpressionEvaluator;
import com.kinetic.molang.runtime.Struct;
import it.unimi.dsi.fastutil.ints.Int2ReferenceMaps;
import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ReferenceMap;
import it.unimi.dsi.fastutil.objects.Object2ReferenceMaps;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

public class AnimationProcessor<TEntity extends Entity> {
    private static final int ROAMING_STRUCT_NAME = StringPool.computeIfAbsent("roaming");
    /**
     * 跑酷控制器在 {@code PlayerControllerCollection} 里的名字。
     *
     * <p>滑铲锁定的骨骼快照必须在这个控制器处理完之后抄、在所有控制器跑完之后写回，
     * 所以这里按名字定位它。控制器名是注册时写死的常量，不会随模型变化。
     */
    private static final String PARCOOL_CONTROLLER = "player.parcool";

    private final AnimatableEntity<TEntity> animatable;
    private final ReferenceArrayList<BoneTopLevelSnapshot> modelBones = new ReferenceArrayList<>();
    private Object2ReferenceMap<String, List<IValue>> eventHandlers = Object2ReferenceMaps.emptyMap();
    private final Int2ReferenceOpenHashMap<BoneTopLevelSnapshot> modelBonesMap = new Int2ReferenceOpenHashMap<>();
    private final ArrayDeque<BoneTopLevelSnapshot> activeModelBones = new ArrayDeque<>();      // 即使更新开销大也比链表更优

    private final MolangMemory molangMemory = new MolangMemory();
    private final SoundInstanceManager globalSoundManager = new SoundInstanceManager();
    private final RandomSource random = new XoroshiroRandomSource(RandomSupport.generateUniqueSeed());
    // molang 执行任务的生产和消费可能在不同线程上
    private final ConcurrentLinkedQueue<MolangExecutionTask> pendingMolangTask = new ConcurrentLinkedQueue<>();

    private float lastTrimTime = 0;
    private boolean modelDirty = false;

    // 逐帧复用的访客与临时向量，避免每个控制器/每根骨骼分配闭包和中间对象。
    private final Consumer<IBoneAnimationQueue> boneQueueVisitor = this::visitBoneQueue;
    private final AnimationVec3 boneQueueScratch = new AnimationVec3();
    private ExpressionEvaluator<MolangContext<?>> currentEvaluator;
    private float currentRenderTicks;
    private boolean currentBlendRotation;

    // 滑铲锁定：锁定骨骼在跑酷控制器之后的快照，以及逐帧复用的回写缓冲。
    private final ReferenceArrayList<BoneTopLevelSnapshot> slideLockedBones = new ReferenceArrayList<>();
    private float[] slideLockedTransform = EMPTY_TRANSFORM;
    private boolean[] slideLockedCaptured = EMPTY_CAPTURED;
    private static final float[] EMPTY_TRANSFORM = new float[0];
    private static final boolean[] EMPTY_CAPTURED = new boolean[0];

    public AnimationProcessor(AnimatableEntity<TEntity> animatable) {
        this.animatable = animatable;
    }

    @SuppressWarnings("unchecked")
    public void tickAnimation(AnimationEvent<AnimatableEntity<TEntity>> event, boolean shouldTick, boolean allowEmitting) {
        var molangContext = new MolangContext<>(event.getAnimatableEntity().getEntity(), event,
                                                molangMemory, random, globalSoundManager);

        ExpressionEvaluator<MolangContext<?>> evaluator = ExpressionEvaluator.evaluator(molangContext);
        var renderTicks = event.renderTicks;

        if (renderTicks - lastTrimTime >= 1200) {
            globalSoundManager.trim();
            lastTrimTime = renderTicks;
        } else if (lastTrimTime > renderTicks) {
            lastTrimTime = renderTicks;
        }

        preProcess(evaluator);

        AnimationData manager = this.animatable.getAnimationData();
        boolean slideLocked = false;
        for (IAnimationController<AnimatableEntity<TEntity>> controller : manager.getAnimationControllers()) {
            if (this.modelDirty) {
                controller.updateModel(this.modelBones, eventHandlers);
            }
            if (shouldTick) {
                // 将当前控制器设置为动画测试事件
                // 处理动画并向点队列添加新值
                try {
                    controller.process(event, evaluator, allowEmitting);
                } catch (RuntimeException failure) {
                    // 单个控制器的谓词或动画数据出错，不应该让整帧渲染崩掉：跳过这一帧的更新，
                    // 其余控制器照常跑（该控制器的姿势冻结在上一帧）。日志按 tick 限流；
                    // 只吞 RuntimeException，Error（OOM/栈溢出）必须继续冒泡。
                    if (renderTicks - lastControllerErrorTicks > 1200.0f) {
                        lastControllerErrorTicks = renderTicks;
                        Kinetic.LOGGER.error("Animation controller '{}' failed; skipping this frame",
                                controller.getName(), failure);
                    }
                }
            }
            // 解决一个历史遗留问题而保留的动画混合
            @Deprecated boolean blendRotation = controller.blendRotation();
            // 遍历每个骨骼，并对属性进行插值计算
            this.currentEvaluator = evaluator;
            this.currentRenderTicks = renderTicks;
            this.currentBlendRotation = blendRotation;
            controller.visitBoneAnimationQueues(this.boneQueueVisitor);
            // 跑酷控制器刚把滑铲姿态写进快照：此时抄下来，等所有频道跑完再写回。
            // 必须在 controller.process 之后读状态——滑动锁定就是在本帧的谓词里刷新的。
            if (PARCOOL_CONTROLLER.equals(controller.getName())) {
                slideLocked = SlideLock.isLocked(this.animatable.getEntity());
                if (slideLocked) {
                    slideLocked = captureSlideLock();
                }
            }
        }

        if (slideLocked) {
            restoreSlideLock();
        }

        this.modelDirty = false;

        // 追踪哪些骨骼应用了动画，并最终将没有动画的骨骼过渡到默认值
        var activeBoneIterator = activeModelBones.iterator();
        while (activeBoneIterator.hasNext()) {
            var snapshot = activeBoneIterator.next();
            var active = false;

            // 处理旋转尾过渡
            if (snapshot.isCurrentlyRunningRotationAnimation) {
                active = true;
                snapshot.isCurrentlyRunningRotationAnimation = false;
                snapshot.rotationOffset = null;
            } else {
                if (snapshot.rotationOffset == null) {
                    snapshot.rotationOffset = new Vector3f(snapshot.rotation);
                }
                var progress = (renderTicks - snapshot.lastRotationUpdateTime) / manager.getResetSpeed();
                if (progress < 1f) {
                    active = true;
                    MathUtil.lerpRotationValues(progress, snapshot.rotationOffset, MathUtil.ZERO, snapshot.bone.getInitialRotation(), snapshot.rotation);
                } else {
                    snapshot.rotation.set(MathUtil.ZERO);
                }
            }

            // 处理位移尾过渡
            if (snapshot.isCurrentlyRunningPositionAnimation) {
                active = true;
                snapshot.isCurrentlyRunningPositionAnimation = false;
                snapshot.positionOffset = null;
            } else {
                if (snapshot.positionOffset == null) {
                    snapshot.positionOffset = new Vector3f(snapshot.position);
                }
                var progress = (renderTicks - snapshot.lastPositionUpdateTime) / manager.getResetSpeed();
                if (progress < 1f) {
                    active = true;
                    MathUtil.lerpValues(progress, snapshot.positionOffset, MathUtil.ZERO, snapshot.position);
                } else {
                    snapshot.position.set(0, 0, 0);
                }
            }

            // 处理缩放尾过渡，终点为 1,1,1
            if (snapshot.isCurrentlyRunningScaleAnimation) {
                active = true;
                snapshot.isCurrentlyRunningScaleAnimation = false;
                snapshot.scaleOffset = null;
            } else {
                if (snapshot.scaleOffset == null) {
                    snapshot.scaleOffset = new Vector3f(snapshot.scale);
                }
                var progress = (renderTicks - snapshot.lastScaleUpdateTime) / manager.getResetSpeed();
                if (progress < 1f) {
                    active = true;
                    MathUtil.lerpValues(progress, snapshot.scaleOffset, MathUtil.ONE, snapshot.scale);
                } else {
                    snapshot.scale.set(1, 1, 1);
                }
            }

            snapshot.commit();
            if (!active) {
                snapshot.hasAnimation = false;
                activeBoneIterator.remove();
            }
        }

        postProcess(evaluator);
    }

    /**
     * 单个骨骼动画队列的逐帧处理；由 {@link #boneQueueVisitor} 复用调用，避免闭包分配。
     */
    private void visitBoneQueue(IBoneAnimationQueue boneAnimation) {
        var evaluator = this.currentEvaluator;
        var renderTicks = this.currentRenderTicks;
        BoneTopLevelSnapshot snapshot = boneAnimation.getSnapshot();
        if (!snapshot.hasAnimation) {
            snapshot.hasAnimation = true;
            activeModelBones.add(snapshot);
        }

        var rot = boneAnimation.pollRotationPoint(evaluator, boneQueueScratch);
        if (rot != null) {
            @Deprecated var pointData = snapshot.cachedPointData;
            if (!snapshot.isCurrentlyRunningRotationAnimation) {
                snapshot.isCurrentlyRunningRotationAnimation = true;
                snapshot.rotation.set(0, 0, 0);
            }
            snapshot.lastRotationUpdateTime = renderTicks;
            if (currentBlendRotation) {
                // 此处假设旧版 blendRotation 只有高并行动画在用，并且该类型动画永不结束，所以尾过渡进度永远为 0。实际上也理应如此
                pointData.add(rot);
                snapshot.rotation.set(pointData);
            } else {
                rot.applyRotation(snapshot.rotation, snapshot.bone.getInitialRotation());
                pointData.set(snapshot.rotation);
            }
        }

        var position = boneAnimation.pollPositionPoint(evaluator, boneQueueScratch);
        if (position != null) {
            if (!snapshot.isCurrentlyRunningPositionAnimation) {
                snapshot.isCurrentlyRunningPositionAnimation = true;
                snapshot.position.set(0, 0, 0);
            }
            snapshot.lastPositionUpdateTime = renderTicks;
            position.apply(snapshot.position);
        }

        var scale = boneAnimation.pollScalePoint(evaluator, boneQueueScratch);
        if (scale != null) {
            if (!snapshot.isCurrentlyRunningScaleAnimation) {
                snapshot.isCurrentlyRunningScaleAnimation = true;
                snapshot.scale.set(1, 1, 1);
            }
            snapshot.lastScaleUpdateTime = renderTicks;
            scale.apply(snapshot.scale);
        }
    }

    /**
     * 抄下锁定骨骼此刻的旋转/位移/缩放。
     *
     * <p>只在跑酷控制器处理完之后调用，因此拿到的一定是滑铲动画的数值；随后 TaCZ 的
     * hold/fire、原版的 swing/use 无论怎么写这些骨骼，都会在 {@link #restoreSlideLock()} 里被覆盖。
     *
     * @return 是否抄到了至少一根骨骼（没有就跑酷控制器这帧没产出滑铲姿态）
     */
    private boolean captureSlideLock() {
        int boneCount = slideLockedBones.size();
        if (boneCount == 0) {
            return false;
        }
        float[] dst = this.slideLockedTransform;
        boolean[] mask = this.slideLockedCaptured;
        boolean any = false;
        for (int i = 0; i < boneCount; i++) {
            BoneTopLevelSnapshot snapshot = slideLockedBones.get(i);
            boolean captured = snapshot.hasAnimation;
            mask[i] = captured;
            if (!captured) {
                continue;
            }
            any = true;
            int base = i * 9;
            dst[base] = snapshot.rotation.x;
            dst[base + 1] = snapshot.rotation.y;
            dst[base + 2] = snapshot.rotation.z;
            dst[base + 3] = snapshot.position.x;
            dst[base + 4] = snapshot.position.y;
            dst[base + 5] = snapshot.position.z;
            dst[base + 6] = snapshot.scale.x;
            dst[base + 7] = snapshot.scale.y;
            dst[base + 8] = snapshot.scale.z;
        }
        return any;
    }

    /** 把 {@link #captureSlideLock()} 抄下来的数值写回去，并阻止尾过渡把它们淡出。 */
    private void restoreSlideLock() {
        int boneCount = slideLockedBones.size();
        float[] src = this.slideLockedTransform;
        boolean[] mask = this.slideLockedCaptured;
        for (int i = 0; i < boneCount; i++) {
            if (!mask[i]) {
                continue;
            }
            BoneTopLevelSnapshot snapshot = slideLockedBones.get(i);
            int base = i * 9;
            snapshot.rotation.set(src[base], src[base + 1], src[base + 2]);
            snapshot.position.set(src[base + 3], src[base + 4], src[base + 5]);
            snapshot.scale.set(src[base + 6], src[base + 7], src[base + 8]);
            // 标记"这一帧有动画"，尾过渡就不会把这些骨骼往初始姿势拉。
            snapshot.isCurrentlyRunningRotationAnimation = true;
            snapshot.isCurrentlyRunningPositionAnimation = true;
            snapshot.isCurrentlyRunningScaleAnimation = true;
        }
    }

    @Nullable
    public BoneView getBone(int boneName) {
        BoneTopLevelSnapshot bone = modelBonesMap.get(boneName);
        return bone != null ? bone.bone : null;
    }

    public void clearModel() {
        this.modelBonesMap.clear();
        this.activeModelBones.clear();
        this.modelBones.clear();
        this.molangMemory.initialize(null);
        this.eventHandlers = Object2ReferenceMaps.emptyMap();
        this.pendingMolangTask.clear();
        this.globalSoundManager.stopAllPlayingSounds();
    }

    public void loadModel(AnimatedGeoModel model, Object2ReferenceMap<String, List<IValue>> eventHandlers) {
        clearModel();
        if (!model.getSortedBones().isEmpty()) {
            this.modelBones.ensureCapacity(model.getSortedBones().size());
            this.modelBones.add(null);
            var rootName = model.getSortedBones().get(0).getPooledName();
            Int2ReferenceMaps.fastForEach(model.getBoneMap(), entry -> {
                BoneTopLevelSnapshot snapshot = new BoneTopLevelSnapshot(entry.getValue());
                this.modelBonesMap.put(entry.getValue().getPooledName(), snapshot);
                if (entry.getIntKey() == rootName) {
                    this.modelBones.set(0, snapshot);
                } else {
                    this.modelBones.add(snapshot);
                }
            });
        }
        rebuildSlideLockedBones();
        this.modelDirty = true;
        this.eventHandlers = eventHandlers;
    }

    /** 模型换掉之后重新挑出滑铲锁定的骨骼，并重整回写缓冲。 */
    private void rebuildSlideLockedBones() {
        this.slideLockedBones.clear();
        for (BoneTopLevelSnapshot snapshot : this.modelBones) {
            if (snapshot != null && SlideLock.isLockedBone(snapshot.name)) {
                this.slideLockedBones.add(snapshot);
            }
        }
        int size = this.slideLockedBones.size();
        this.slideLockedTransform = size == 0 ? EMPTY_TRANSFORM : new float[size * 9];
        this.slideLockedCaptured = size == 0 ? EMPTY_CAPTURED : new boolean[size];
    }

    public void putRemoteStruct(@Nullable Struct remoteStruct) {
        if (remoteStruct != null) {
            molangMemory.setScoped(ROAMING_STRUCT_NAME, remoteStruct);
        }
    }

    public boolean isModelEmpty() {
        return modelBones.isEmpty();
    }

    private void preProcess(ExpressionEvaluator<MolangContext<?>> evaluator) {
        for (var iter = pendingMolangTask.iterator(); iter.hasNext(); ) {
            var task = iter.next();
            if (task.pre) {
                executeMolangTask(task, evaluator);
                iter.remove();
            }
        }
    }

    private void postProcess(ExpressionEvaluator<MolangContext<?>> evaluator) {
        for (var iter = pendingMolangTask.iterator(); iter.hasNext(); ) {
            var task = iter.next();
            if (!task.pre) {
                executeMolangTask(task, evaluator);
                iter.remove();
            }
        }
    }

    /** 控制器异常日志的上次记录时刻（渲染 tick），避免坏掉的谓词每帧刷屏。 */
    private float lastControllerErrorTicks = Float.NEGATIVE_INFINITY;

    private void executeMolangTask(MolangExecutionTask task, ExpressionEvaluator<MolangContext<?>> evaluator) {
        String result;
        try {
            evaluator.entity().setAllowEmitting(task.allowEmitting());
            var ret = task.exp().eval(evaluator);
            if (task.resultCallback() == null) {
                return;
            }
            if (ret == null) {
                result = "null";
            } else if (ret instanceof String) {
                result = "'" + ret + "'";
            } else {
                result = ret.toString();
            }
        } catch (Throwable e) {
            result = "Error: " + e.getMessage();
        } finally {
            evaluator.entity().setAllowEmitting(false);
        }
        task.resultCallback().accept(result);
    }

    public void enqueueMolangTask(IValue value, boolean allowEmitting, boolean pre, @Nullable Consumer<String> resultConsumer) {
        pendingMolangTask.add(new MolangExecutionTask(value, allowEmitting, pre, resultConsumer));
    }

    // 获取公共变量存储（暂未实现）
    public IForeignVariableStorage getPublicVariableStorage() {
        return this.molangMemory;
    }

    public void visitScopedVariableNames(Consumer<String> visitor) {
        this.molangMemory.visitScopedVariableNames(visitor);
    }

    private record MolangExecutionTask(IValue exp, boolean allowEmitting, boolean pre, Consumer<String> resultCallback) {}
}
