package com.kinetic.geckolib3.core.controller.transition;

public interface IBlendTransition {
    float get(float tick);

    /**
     * Tick
     */
    float length();

    default IBlendTransition startNew() {
        return this;
    }
}
