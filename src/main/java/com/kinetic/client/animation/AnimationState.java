package com.kinetic.client.animation;

import com.kinetic.geckolib3.core.builder.LoopType;
import com.kinetic.geckolib3.core.event.predicate.AnimationEvent;
import com.kinetic.geckolib3.model.AnimatableEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;

import java.util.Locale;
import java.util.function.BiPredicate;

public class AnimationState<TE extends LivingEntity, AE extends AnimatableEntity<TE>> {
    private final String animationName;
    /** 预先转成小写，避免优先级查询里反复 {@code toLowerCase}。 */
    private final String lowercaseName;
    private final LoopType loopType;
    private final int priority;
    private final BiPredicate<TE, AnimationEvent<AE>> predicate;

    public AnimationState(String animationName, LoopType loopType, int priority, BiPredicate<TE, AnimationEvent<AE>> predicate) {
        this.animationName = animationName;
        this.lowercaseName = animationName == null ? null : animationName.toLowerCase(Locale.ROOT);
        this.loopType = loopType;
        this.priority = Mth.clamp(priority, Priority.HIGHEST, Priority.LOWEST);
        this.predicate = predicate;
    }

    public BiPredicate<TE, AnimationEvent<AE>> getPredicate() {
        return predicate;
    }

    public String getAnimationName() {
        return animationName;
    }

    public String getLowercaseName() {
        return lowercaseName;
    }

    public LoopType getLoopType() {
        return loopType;
    }

    public int getPriority() {
        return priority;
    }
}
