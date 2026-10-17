package com.kinetic.geckolib3.core.molang.variable;

import com.kinetic.geckolib3.core.molang.context.IContext;
import com.kinetic.molang.runtime.ExecutionContext;
import com.kinetic.molang.runtime.Variable;

public abstract class ContextVariable<TEntity> implements Variable {
    protected boolean validateContext(IContext<?> context) {
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public final Object evaluate(ExecutionContext<?> context) {
        Object entity = context.entity();
        if (entity instanceof IContext && validateContext((IContext<?>) entity)) {
            return evaluate((IContext<TEntity>) entity);
        } else {
            // 如果类型不匹配，返回 0 （false）
            return 0;
        }
    }

    public abstract Object evaluate(IContext<TEntity> context);
}
