package com.kinetic.geckolib3.core.keyframe.point;

import com.kinetic.geckolib3.core.molang.context.AnimationContext;
import com.kinetic.geckolib3.core.keyframe.bone.BoneKeyFrame;
import com.kinetic.geckolib3.core.molang.context.MolangContext;
import com.kinetic.molang.runtime.ExpressionEvaluator;
import org.joml.Vector3f;

public class KeyFramePoint extends AnimationPoint {
    public BoneKeyFrame keyframe;
    // 复用模式下用于替代 AnimationPoint 中不可变的 currentTick / context。
    private float tick;
    private AnimationContext context;
    // 复用的 lastLerpResult 缓冲；update 时把 lastLerpResult 置空，等价于每帧新建一个点。
    private final Vector3f reusableLerpResult = new Vector3f();

    public KeyFramePoint(float currentTick, BoneKeyFrame keyframe, AnimationContext context) {
        super(currentTick, keyframe.getTotalTick(), context);
        this.keyframe = keyframe;
        this.tick = currentTick;
        this.context = context;
    }

    /**
     * 供 {@code AnimationPlayer} 逐帧复用，避免每帧分配新的点对象。
     */
    public KeyFramePoint() {
        super(0, 0, null);
    }

    /**
     * 复用同一个实例并更新到新的 tick / 关键帧。
     */
    public KeyFramePoint update(float currentTick, BoneKeyFrame keyframe, AnimationContext context) {
        this.keyframe = keyframe;
        this.tick = currentTick;
        this.context = context;
        // 与每帧新建实例保持一致：轮询之前 lastLerpResult 必须为空。
        this.lastLerpResult = null;
        return this;
    }

    @Override
    public float getPercentCompleted() {
        var totalTick = keyframe.getTotalTick();
        return totalTick == 0 ? 1 : (tick / totalTick);
    }

    @Override
    protected void setupAnimationContext(ExpressionEvaluator<MolangContext<?>> evaluator) {
        evaluator.entity().setAnimationContext(context);
    }

    @Override
    public Vector3f getLerpPoint(ExpressionEvaluator<MolangContext<?>> evaluator) {
        setupAnimationContext(evaluator);
        var result = keyframe.getLerpPoint(evaluator, getPercentCompleted());
        if (lastLerpResult == null) {
            reusableLerpResult.set(result);
            lastLerpResult = reusableLerpResult;
        } else {
            lastLerpResult.set(result);
        }
        return result;
    }
}
