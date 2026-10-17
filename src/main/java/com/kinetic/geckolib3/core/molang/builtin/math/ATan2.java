package com.kinetic.geckolib3.core.molang.builtin.math;

import com.kinetic.molang.runtime.ExecutionContext;
import com.kinetic.molang.runtime.Function;
import net.minecraft.util.Mth;

public class ATan2 implements Function {
    @Override
    public Object evaluate(ExecutionContext<?> context, ArgumentCollection arguments) {
        return Mth.atan2(arguments.getAsDouble(context, 0),
                arguments.getAsDouble(context, 1));
    }

    @Override
    public boolean validateArgumentSize(int size) {
        return size == 2;
    }
}
