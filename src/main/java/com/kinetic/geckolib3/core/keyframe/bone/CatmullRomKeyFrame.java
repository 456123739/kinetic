package com.kinetic.geckolib3.core.keyframe.bone;

import com.kinetic.geckolib3.core.util.MathUtil;
import com.kinetic.molang.runtime.ExpressionEvaluator;
import org.joml.Vector3f;

public class CatmullRomKeyFrame extends BoneKeyFrame {
    private final Vector3v leftPoint;
    private final Vector3v endPoint;
    private final Vector3v rightPoint;
    private final Vector3v postPoint;
    // 每个采样位置独立的临时向量：即使两个 Vector3v 是同一个实例，也不会互相覆盖。
    private final Vector3f leftScratch = new Vector3f();
    private final Vector3f beginScratch = new Vector3f();
    private final Vector3f endScratch = new Vector3f();
    private final Vector3f rightScratch = new Vector3f();
    private final Vector3f postScratch = new Vector3f();
    private final Vector3f resultScratch = new Vector3f();

    public CatmullRomKeyFrame(float startTick, float totalTick, Vector3v leftPoint, Vector3v current, Vector3v endPoint, Vector3v postRight, Vector3v postPoint) {
        super(startTick, totalTick, current);
        this.leftPoint = leftPoint;
        this.endPoint = endPoint;
        this.rightPoint = postRight;
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
        leftPoint.eval(evaluator, leftScratch);
        beginPoint.eval(evaluator, beginScratch);
        endPoint.eval(evaluator, endScratch);
        rightPoint.eval(evaluator, rightScratch);
        return MathUtil.catmullRom(percentCompleted, leftScratch, beginScratch, endScratch, rightScratch, resultScratch);
    }
}
