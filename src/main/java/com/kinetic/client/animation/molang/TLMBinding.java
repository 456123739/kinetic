package com.kinetic.client.animation.molang;

import com.kinetic.client.compat.touhoulittlemaid.client.TlmClientCompat;
import com.kinetic.geckolib3.core.molang.binding.ContextBinding;
import com.kinetic.util.LazyValue;

/**
 * 为了兼容女仆新增的 molang，即使女仆模组未安装，也应该存在，否则动画会报错
 */
public class TLMBinding extends ContextBinding {
    public static final LazyValue<TLMBinding> INSTANCE = new LazyValue<>(TLMBinding::new);

    public TLMBinding() {
        TlmClientCompat.addBinding(this);
    }
}