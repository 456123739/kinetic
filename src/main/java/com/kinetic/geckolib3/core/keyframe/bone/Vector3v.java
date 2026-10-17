package com.kinetic.geckolib3.core.keyframe.bone;

import com.kinetic.geckolib3.core.molang.value.IValue;
import com.kinetic.molang.runtime.ExpressionEvaluator;
import org.joml.Vector3f;

public class Vector3v {
    private final IValue x;
    private final IValue y;
    private final IValue z;

    public Vector3v(IValue x, IValue y, IValue z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Vector3f eval(ExpressionEvaluator<?> evaluator) {
        return new Vector3f(x.evalAsFloat(evaluator),
                y.evalAsFloat(evaluator),
                z.evalAsFloat(evaluator));
    }

    /**
     * 无分配版本：把结果写进调用方提供的 {@code dst}。
     */
    public Vector3f eval(ExpressionEvaluator<?> evaluator, Vector3f dst) {
        return dst.set(x.evalAsFloat(evaluator),
                y.evalAsFloat(evaluator),
                z.evalAsFloat(evaluator));
    }
}
