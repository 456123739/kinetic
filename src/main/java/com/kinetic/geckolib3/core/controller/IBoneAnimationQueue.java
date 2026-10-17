package com.kinetic.geckolib3.core.controller;

import com.kinetic.geckolib3.core.keyframe.AnimationVec3;
import com.kinetic.geckolib3.core.molang.context.MolangContext;
import com.kinetic.geckolib3.core.snapshot.BoneTopLevelSnapshot;
import com.kinetic.molang.runtime.ExpressionEvaluator;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

public interface IBoneAnimationQueue {
    BoneTopLevelSnapshot getSnapshot();

    Optional<AnimationVec3> pollRotationPoint(ExpressionEvaluator<MolangContext<?>> evaluator);

    Optional<AnimationVec3> pollPositionPoint(ExpressionEvaluator<MolangContext<?>> evaluator);

    Optional<AnimationVec3> pollScalePoint(ExpressionEvaluator<MolangContext<?>> evaluator);

    /**
     * 无分配版本：把结果写进调用方提供的 {@code dst}，没有可用的动画点时返回 {@code null}。
     *
     * <p>默认实现回退到 {@link Optional} 版本，供尚未迁移的实现使用。
     */
    @Nullable
    default AnimationVec3 pollRotationPoint(ExpressionEvaluator<MolangContext<?>> evaluator, AnimationVec3 dst) {
        return copyOrNull(pollRotationPoint(evaluator), dst);
    }

    /**
     * 无分配版本：把结果写进调用方提供的 {@code dst}，没有可用的动画点时返回 {@code null}。
     *
     * <p>默认实现回退到 {@link Optional} 版本，供尚未迁移的实现使用。
     */
    @Nullable
    default AnimationVec3 pollPositionPoint(ExpressionEvaluator<MolangContext<?>> evaluator, AnimationVec3 dst) {
        return copyOrNull(pollPositionPoint(evaluator), dst);
    }

    /**
     * 无分配版本：把结果写进调用方提供的 {@code dst}，没有可用的动画点时返回 {@code null}。
     *
     * <p>默认实现回退到 {@link Optional} 版本，供尚未迁移的实现使用。
     */
    @Nullable
    default AnimationVec3 pollScalePoint(ExpressionEvaluator<MolangContext<?>> evaluator, AnimationVec3 dst) {
        return copyOrNull(pollScalePoint(evaluator), dst);
    }

    @Nullable
    private static AnimationVec3 copyOrNull(Optional<AnimationVec3> point, AnimationVec3 dst) {
        if (point.isEmpty()) {
            return null;
        }
        var value = point.get();
        dst.set(value);
        dst.endingTransitionPercentProgress = value.endingTransitionPercentProgress;
        return dst;
    }
}
