package com.kinetic.client.animation.molang.functions;

import com.kinetic.client.animation.molang.struct.Vec3fStruct;
import com.kinetic.geckolib3.core.molang.context.IContext;
import com.kinetic.geckolib3.core.molang.function.entity.EntityFunction;
import com.kinetic.geckolib3.core.molang.util.StringPool;
import com.kinetic.geckolib3.core.processor.BoneView;
import com.kinetic.molang.runtime.ExecutionContext;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.NotNull;

public abstract class BoneParamFunction extends EntityFunction {
    @Override
    public boolean validateArgumentSize(int size) {
        return size == 1;
    }

    @Override
    protected Object eval(ExecutionContext<IContext<Entity>> context, ArgumentCollection arguments) {
        var boneName = arguments.getAsPooledString(context, 0);
        if (boneName == StringPool.EMPTY) {
            return null;
        }

        var bone = context.entity().animatableEntity().getBone(boneName);
        if (bone == null) {
            return null;
        }

        return getParam(bone);
    }

    protected abstract Vec3fStruct getParam(@NotNull BoneView bone);
}
