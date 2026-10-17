package com.kinetic.client.animation.predicate;

import com.kinetic.accessor.IArrowExtraInfo;
import com.kinetic.client.entity.CustomProjectileEntity;
import com.kinetic.geckolib3.core.PlayState;
import com.kinetic.geckolib3.core.event.predicate.AnimationEvent;
import com.kinetic.molang.runtime.ExpressionEvaluator;
import net.minecraft.world.entity.projectile.Projectile;

import static com.kinetic.client.animation.predicate.IAnimationPredicate.playAnimation;

public class ProjectileMainPredicate implements IAnimationPredicate<CustomProjectileEntity> {
    public static final String[] ANIM_LIST = new String[]{"water", "ground", "fly", "fire"};

    @Override
    public PlayState test(AnimationEvent<CustomProjectileEntity> event, ExpressionEvaluator<?> evaluator) {
        Projectile projectile = event.getAnimatableEntity().getEntity();
        if (projectile == null) {
            return PlayState.STOP;
        }
        if (projectile.isInWater()) {
            return playAnimation(event, "water");
        }
        if (projectile.isOnFire()) {
            return playAnimation(event, "fire");
        }
        if (projectile instanceof IArrowExtraInfo info && info.isInGround()) {
            return playAnimation(event, "ground");
        } else {
            return playAnimation(event, "air");
        }
    }
}
