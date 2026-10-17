package com.kinetic.client.animation.molang.variable;

import com.kinetic.geckolib3.core.molang.context.IContext;
import com.kinetic.geckolib3.core.molang.variable.IValueEvaluator;
import net.minecraft.world.entity.player.Player;

public class FirstPersonModHideVariable implements IValueEvaluator<Boolean, IContext<Player>> {
    @Override
    public Boolean eval(IContext<Player> ctx) {
        return ctx.animationEvent().getRenderContext().firstPersonMod();
    }
}
