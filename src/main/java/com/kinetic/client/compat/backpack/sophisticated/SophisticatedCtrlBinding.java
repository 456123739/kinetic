package com.kinetic.client.compat.backpack.sophisticated;

import com.kinetic.client.animation.molang.CtrlBinding;

public class SophisticatedCtrlBinding {
    static void addInnerBinding(CtrlBinding binding) {
        binding.livingEntityVar("has_sophisticated_backpack", ctx ->
                SophisticatedCompat.getBackpackItemStack(ctx.entity()) != null);
    }
}
