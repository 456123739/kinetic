package com.kinetic.geckolib3.core.molang.variable.entity;

import com.kinetic.geckolib3.core.molang.context.IContext;
import com.kinetic.geckolib3.core.molang.variable.IValueEvaluator;
import com.kinetic.geckolib3.core.molang.variable.LambdaVariable;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;

public class ThrowableItemProjectileVariable extends LambdaVariable<ThrowableItemProjectile> {
    public ThrowableItemProjectileVariable(IValueEvaluator<?, IContext<ThrowableItemProjectile>> evaluator) {
        super(evaluator);
    }

    @Override
    protected boolean validateContext(IContext<?> context) {
        return context.entity() instanceof ThrowableItemProjectile;
    }
}
