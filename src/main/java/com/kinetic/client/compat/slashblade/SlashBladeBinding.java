package com.kinetic.client.compat.slashblade;

import com.kinetic.client.animation.molang.CtrlBinding;

public class SlashBladeBinding {
    static void addInnerBinding(CtrlBinding binding) {
        binding.livingEntityVar("slashblade_animation", SlashBladeAnimation::getAnimationName);
    }
}
