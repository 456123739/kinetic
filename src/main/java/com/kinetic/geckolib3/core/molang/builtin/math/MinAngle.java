package com.kinetic.geckolib3.core.molang.builtin.math;

import com.kinetic.molang.runtime.ExecutionContext;
import com.kinetic.molang.runtime.Function;

public class MinAngle implements Function {
    @Override
    public Object evaluate(ExecutionContext<?> context, ArgumentCollection arguments) {
        float angle = arguments.getAsFloat(context, 0) % 360;
        if (angle >= 180) {
            return angle - 360;
        } else if (angle < -180) {
            return angle + 360;
        } else {
            return angle;
        }
    }

    @Override
    public boolean validateArgumentSize(int size) {
        return size == 1;
    }
}
