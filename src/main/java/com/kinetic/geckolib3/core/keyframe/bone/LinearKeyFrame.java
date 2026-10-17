package com.kinetic.geckolib3.core.keyframe.bone;

import com.kinetic.geckolib3.core.util.MathUtil;
import com.kinetic.molang.runtime.ExpressionEvaluator;
import org.joml.Vector3f;

public class LinearKeyFrame extends BoneKeyFrame {
    private final Vector3v endPoint;
    private final Vector3v postPoint;
    // 逐帧复用，避免每次求值分配临时向量。
    private final Vector3f beginScratch = new Vector3f();
    private final Vector3f endScratch = new Vector3f();
    private final Vector3f postScratch = new Vector3f();
    private final Vector3f resultScratch = new Vector3f();

    public LinearKeyFrame(float startTick, float totalTick, Vector3v beginPoint, Vector3v endPoint, Vector3v postPoint) {
        super(startTick, totalTick, beginPoint);
        this.endPoint = endPoint;
        this.postPoint = postPoint;
    }

    @Override
    public Vector3f getLerpPoint(ExpressionEvaluator<?> evaluator, float percentCompleted) {
        if (isBegin(percentCompleted)) {
            return beginPoint.eval(evaluator, beginScratch);
        }
        if (isEnd(percentCompleted)) {
            return postPoint.eval(evaluator, postScratch);
        }
        beginPoint.eval(evaluator, beginScratch);
        endPoint.eval(evaluator, endScratch);
        MathUtil.lerpValues(percentCompleted, beginScratch, endScratch, resultScratch);
        return resultScratch;
    }
}
