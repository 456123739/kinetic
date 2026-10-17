package com.kinetic.client.animation.molang;

import com.kinetic.geckolib3.core.molang.context.IContext;
import com.kinetic.molang.runtime.ExecutionContext;
import com.kinetic.molang.runtime.Variable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class UserFunctionArgument implements Variable {
    public static final UserFunctionArgument INSTANCE = new UserFunctionArgument();

    @Override
    public @Nullable Object evaluate(@NotNull ExecutionContext<?> context) {
        if (context.entity() instanceof IContext<?> ctx) {
            return ctx.userFunctionArgs();
        }
        return null;
    }
}
