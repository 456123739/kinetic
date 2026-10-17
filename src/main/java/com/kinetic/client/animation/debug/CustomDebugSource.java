package com.kinetic.client.animation.debug;

import com.kinetic.geckolib3.core.molang.context.DebugSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class CustomDebugSource implements DebugSource {
    public static final CustomDebugSource INSTANCE = new CustomDebugSource();

    private CustomDebugSource() {
    }

    @Override
    public void print(String message, Object...args) {
        print(Component.literal(String.format(message, args)));
    }

    @Override
    public void print(Component message) {
        if(Minecraft.getInstance().player == null) {
            return;
        }
        Minecraft.getInstance().execute(() -> {
            Minecraft.getInstance().player.sendSystemMessage(Component.translatable("message.kinetic.model.debug_animation.output")
                            .append(message));
        });
    }
}
