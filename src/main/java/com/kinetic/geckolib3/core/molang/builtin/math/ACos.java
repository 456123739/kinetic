package com.kinetic.geckolib3.core.molang.builtin.math;

import com.kinetic.molang.runtime.ExecutionContext;
import com.kinetic.molang.runtime.Function;

public class ACos implements Function {
    @Override
    public Object evaluate(ExecutionContext<?> context, ArgumentCollection arguments) {
        return Math.acos(arguments.getAsDouble(context, 0));
    }

    @Override
    public boolean validateArgumentSize(int size) {
        return size == 1;
    }
}
