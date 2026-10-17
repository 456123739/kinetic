package com.kinetic.client.animation.molang.functions;

import com.kinetic.geckolib3.core.molang.context.IContext;
import com.kinetic.geckolib3.core.molang.function.ContextFunction;
import com.kinetic.geckolib3.core.molang.util.StringPool;
import com.kinetic.molang.runtime.ExecutionContext;

public class Defer extends ContextFunction<Object> {
    @Override
    protected Object eval(ExecutionContext<IContext<Object>> ctx, ArgumentCollection arguments) {
        if (ctx.entity().allowEmitting()) {
            var animCtx = ctx.entity().animationContext();
            if (animCtx != null) {
                var name = arguments.getAsPooledString(ctx, 0);
                if (name != StringPool.EMPTY) {
                    animCtx.defer(ctx, name, arguments, 1);
                }
            }
        }
        return null;
    }
}
