package com.kinetic.geckolib3.core.controller;

import com.kinetic.client.animation.molang.CtrlBinding;
import com.kinetic.client.animation.predicate.IAnimationPredicate;
import com.kinetic.geckolib3.core.AnimationState;
import com.kinetic.geckolib3.core.PlayState;
import com.kinetic.geckolib3.core.builder.LoopType;
import com.kinetic.geckolib3.core.controller.transition.IBlendTransition;
import com.kinetic.geckolib3.core.controller.transition.LinearBlendTransition;
import com.kinetic.geckolib3.core.event.predicate.AnimationEvent;
import com.kinetic.geckolib3.core.keyframe.AnimationVec3;
import com.kinetic.geckolib3.core.keyframe.BoneAnimationQueue;
import com.kinetic.geckolib3.core.keyframe.point.BeginningTransitionPoint;
import com.kinetic.geckolib3.core.keyframe.point.EndingTransitionPoint;
import com.kinetic.geckolib3.core.molang.context.ControllerContext;
import com.kinetic.geckolib3.core.molang.context.MolangContext;
import com.kinetic.geckolib3.core.molang.value.IValue;
import com.kinetic.geckolib3.core.snapshot.BoneTopLevelSnapshot;
import com.kinetic.geckolib3.core.util.MathUtil;
import com.kinetic.geckolib3.model.AnimatableEntity;
import com.kinetic.molang.runtime.ExpressionEvaluator;
import it.unimi.dsi.fastutil.objects.Object2ReferenceMap;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public class CodedAnimationController<T extends AnimatableEntity<?>> implements IAnimationController<T> {
    private final String name;
    private final IAnimationPredicate<T> animationPredicate;
    private final AnimationPlayer animationPlayer;
    private final boolean blendRotation;
    private final ControllerContext ctx;
    private final float defaultTransitionTicks;
    private final SingleBoneAnimationQueue boneQueueAdapter = new SingleBoneAnimationQueue();
    @Nullable
    private IValue molangPredicate;
    private boolean pause;

    /**
     * 实例化硬编码动画控制器，每个控制器同一时间只能播放一个动画 <br>
     * 你可以为一个实体附加多个动画控制器 <br>
     * 比如一个控制器控制实体大小，另一个控制移动，攻击等等
     *
     * @param animatableEntity      实体
     * @param name                  动画控制器名称
     * @param transitionLengthTicks 动画过渡时间（tick）
     */
    public CodedAnimationController(T animatableEntity, String name, float transitionLengthTicks,
                                    IAnimationPredicate<T> animationPredicate) {
        this(animatableEntity, name, transitionLengthTicks, animationPredicate, false);
    }

    @Deprecated
    public CodedAnimationController(T animatableEntity, String name, float transitionLengthTicks,
                                    IAnimationPredicate<T> animationPredicate, boolean blendRotation) {
        this.name = name;
        this.animationPredicate = animationPredicate;
        this.animationPlayer = new AnimationPlayer(animatableEntity, transitionLengthTicks, true);
        this.defaultTransitionTicks = transitionLengthTicks;
        this.blendRotation = blendRotation;
        this.ctx = new ControllerContext(false);
    }

    @Override
    public void process(AnimationEvent<T> event, ExpressionEvaluator<MolangContext<?>> evaluator, boolean allowEmitting) {
        event.setCodedAnimationController(this);
        PlayState playState = evalMolangPredicate(evaluator);
        if (playState == null) {
            playState = this.animationPredicate.test(event, evaluator);
        }
        event.setCodedAnimationController(null);

        if (playState == PlayState.CONTINUE) {
            this.animationPlayer.process(event.renderTicks, evaluator, allowEmitting);
            this.pause = false;
        } else if (playState == PlayState.STOP) {
            var state = this.animationPlayer.getState();
            if (state == AnimationState.BEGINNING_TRANSITION || state == AnimationState.RUNNING) {
                this.animationPlayer.stop(event.renderTicks);
                this.animationPlayer.indicateReload();
            }
            if (state == AnimationState.ENDING_TRANSITION) {
                this.animationPlayer.process(event.renderTicks, evaluator, allowEmitting);
            }
            this.pause = false;
        } else if (playState == PlayState.PAUSE) {
            this.animationPlayer.process(event.renderTicks, evaluator, false);
            this.animationPlayer.resetBoneAnimationQueues();
            this.pause = true;
        }

        // 最后给 MoLang 上下文设置当前动画状态
        event.getAnimatableEntity().setCodedAnimationStates(this.name, this.animationPlayer.getState());
    }

    @Nullable
    private PlayState evalMolangPredicate(ExpressionEvaluator<MolangContext<?>> evaluator) {
        if (this.molangPredicate == null) {
            return null;
        }

        this.ctx.setAnyAnimationFinished(this.animationPlayer.currentAnimFinished());
        this.ctx.setAllAnimationsFinished(this.animationPlayer.currentAnimFinished());

        evaluator.entity().setControllerContext(this.ctx);
        evaluator.entity().setAnimationContext(this.animationPlayer.getAnimationContext());
        evaluator.entity().setAllowEmitting(true);

        var state = this.molangPredicate.evalAsInt(evaluator);

        evaluator.entity().setAllowEmitting(false);
        evaluator.entity().setAnimationContext(null);
        evaluator.entity().setControllerContext(null);

        return switch (state) {
            case CtrlBinding.STATE_CONTINUE -> PlayState.CONTINUE;
            case CtrlBinding.STATE_PAUSE -> PlayState.PAUSE;
            case CtrlBinding.STATE_STOP -> PlayState.STOP;
            default -> null;
        };
    }

    @Override
    public void updateModel(List<BoneTopLevelSnapshot> modelBones, Object2ReferenceMap<String, List<IValue>> eventHandlers) {
        this.animationPlayer.updateModel(modelBones);
        this.animationPlayer.setBeginningTransition(new LinearBlendTransition(defaultTransitionTicks));
        this.molangPredicate = null;
        var predictEventName = this.name.replace(".", "_ctrl_");
        var handlers = eventHandlers.get(predictEventName);
        if (handlers != null && !handlers.isEmpty()) {
            this.molangPredicate = handlers.get(0);
        }
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    @SuppressWarnings("DataFlowIssue")
    public String getStateName() {
        // 硬编码控制器没有状态，返回自己名称+正在播放的动画
        if (animationPlayer.getState() == AnimationState.IDLE) {
            return "Coded";
        } else {
            return "Coded -> " + animationPlayer.getCurrentAnim().name;
        }
    }

    @Override
    @Nullable
    public String getCurrentAnimationName() {
        var animation = this.animationPlayer.getCurrentAnim();
        return animation == null ? null : animation.name;
    }

    public void setAnimation(String animationName) {
        this.animationPlayer.setAnimation(animationName, null);
    }

    public void setAnimation(String animationName, @Nullable LoopType loopType) {
        this.animationPlayer.setAnimation(animationName, loopType);
    }

    public void setBeginningTransitionLength(float sec) {
        if (this.animationPlayer.getBeginningTransitionLength() != sec) {
            this.animationPlayer.setBeginningTransition(new LinearBlendTransition(sec));
        }
    }

    public void setBeginningTransition(IBlendTransition blendTransition) {
        this.animationPlayer.setBeginningTransition(blendTransition);
    }

    public void finalizeAnimationContext(ExpressionEvaluator<MolangContext<?>> evaluator) {
        this.animationPlayer.finalizeAnimationContext(evaluator);
    }

    @Override
    public void visitBoneAnimationQueues(Consumer<IBoneAnimationQueue> visitor) {
        if (!this.pause) {
            for (var queue : this.animationPlayer.getActiveBoneAnimQueues()) {
                visitor.accept(this.boneQueueAdapter.wrap(queue));
            }
        }
    }

    public void reset() {
        this.animationPlayer.reset();
    }

    public void clear() {
        this.animationPlayer.clear();
        this.molangPredicate = null;
    }

    public void indicateReload() {
        this.animationPlayer.indicateReload();
    }

    public void stop(float renderTicks) {
        this.animationPlayer.stop(renderTicks);
    }

    public boolean isAnimFinished() {
        return this.animationPlayer.currentAnimFinished();
    }

    public void stopPlayingSounds() {
        this.animationPlayer.stopPlayingSounds();
    }

    @Override
    @Deprecated
    public boolean blendRotation() {
        // TODO: 仅临时缓解，未完全修复过渡动画混合问题。
        return blendRotation && animationPlayer.getState() == AnimationState.RUNNING;
    }

    /**
     * 单骨骼适配器；整个控制器只保留一个实例，逐帧通过 {@link #wrap} 切换目标队列，避免每帧分配。
     */
    private static final class SingleBoneAnimationQueue implements IBoneAnimationQueue {
        private BoneAnimationQueue queue;

        private SingleBoneAnimationQueue wrap(BoneAnimationQueue queue) {
            this.queue = queue;
            return this;
        }

        @Override
        public BoneTopLevelSnapshot getSnapshot() {
            return queue.topLevelSnapshot;
        }

        @Override
        public Optional<AnimationVec3> pollRotationPoint(ExpressionEvaluator<MolangContext<?>> evaluator) {
            var result = pollRotationPoint(evaluator, new AnimationVec3());
            return result == null ? Optional.empty() : Optional.of(result);
        }

        @Override
        public AnimationVec3 pollRotationPoint(ExpressionEvaluator<MolangContext<?>> evaluator, AnimationVec3 dst) {
            var point = this.queue.rotation;

            if (point == null) {
                return null;
            }

            dst.endingTransitionPercentProgress = 1f;
            if (point instanceof EndingTransitionPoint endingPoint) {
                dst.set(point.getLerpPoint(evaluator));
                dst.setEndingTransitionPercentProgressIfLess(endingPoint.getPercentCompleted());
                var weight = queue.getBlendWeight();
                if (weight != 1) {
                    dst.mul(weight);
                }
            } else if (point instanceof BeginningTransitionPoint beginningPoint) {
                var transitionDst = beginningPoint.getTransitionDst(evaluator)
                        .mul(queue.getBlendWeight());
                MathUtil.lerpRotationValues(beginningPoint.getTransitionPercentProgress(),
                        beginningPoint.getTransitionOffset(),
                        transitionDst,
                        queue.topLevelSnapshot.bone.getInitialRotation(),
                        transitionDst);
                dst.set(transitionDst);
                dst.setEndingTransitionPercentProgressIfLess(0);
            } else {
                dst.set(point.getLerpPoint(evaluator));
                var weight = queue.getBlendWeight();
                if (weight != 1) {
                    dst.mul(weight);
                }
                dst.setEndingTransitionPercentProgressIfLess(0);
            }

            return dst;
        }

        @Override
        public Optional<AnimationVec3> pollPositionPoint(ExpressionEvaluator<MolangContext<?>> evaluator) {
            var result = pollPositionPoint(evaluator, new AnimationVec3());
            return result == null ? Optional.empty() : Optional.of(result);
        }

        @Override
        public AnimationVec3 pollPositionPoint(ExpressionEvaluator<MolangContext<?>> evaluator, AnimationVec3 dst) {
            var point = this.queue.position;

            if (point == null) {
                return null;
            }

            dst.endingTransitionPercentProgress = 1f;
            dst.set(point.getLerpPoint(evaluator));
            var weight = queue.getBlendWeight();
            if (point instanceof EndingTransitionPoint endingPoint) {
                dst.setEndingTransitionPercentProgressIfLess(endingPoint.getPercentCompleted());
            } else {
                if (point instanceof BeginningTransitionPoint beginningPoint) {
                    weight = MathUtil.lerpValues(beginningPoint.getTransitionPercentProgress(), 1, weight);
                }
                dst.setEndingTransitionPercentProgressIfLess(0);
            }

            if (weight != 1) {
                dst.mul(weight);
            }
            return dst;
        }

        @Override
        public Optional<AnimationVec3> pollScalePoint(ExpressionEvaluator<MolangContext<?>> evaluator) {
            var result = pollScalePoint(evaluator, new AnimationVec3());
            return result == null ? Optional.empty() : Optional.of(result);
        }

        @Override
        public AnimationVec3 pollScalePoint(ExpressionEvaluator<MolangContext<?>> evaluator, AnimationVec3 dst) {
            var point = this.queue.scale;

            if (point == null) {
                return null;
            }

            dst.endingTransitionPercentProgress = 1f;
            dst.set(point.getLerpPoint(evaluator));
            var weight = queue.getBlendWeight();
            if (point instanceof EndingTransitionPoint endingPoint) {
                dst.setEndingTransitionPercentProgressIfLess(endingPoint.getPercentCompleted());
            } else {
                if (point instanceof BeginningTransitionPoint beginningPoint) {
                    weight = MathUtil.lerpValues(beginningPoint.getTransitionPercentProgress(), 1, weight);
                }
                dst.setEndingTransitionPercentProgressIfLess(0);
            }

            if (weight != 1) {
                MathUtil.computeWeightedScale(dst, weight, dst);
            }
            return dst;
        }
    }
}
