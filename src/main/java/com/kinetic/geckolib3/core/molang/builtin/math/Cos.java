package com.kinetic.geckolib3.core.molang.builtin.math;

import com.kinetic.geckolib3.core.util.MathUtil;
import com.kinetic.molang.runtime.ExecutionContext;
import com.kinetic.molang.runtime.Function;
import net.minecraft.util.Mth;

public class Cos implements Function {
    @Override
    public Object evaluate(ExecutionContext<?> context, ArgumentCollection arguments) {
        return Mth.cos(arguments.getAsFloat(context, 0) / 180f * MathUtil.PI);
    }

    @Override
    public boolean validateArgumentSize(int size) {
        return size == 1;
    }
}
