package com.kinetic.util;

import com.kinetic.model.resource.client.ModelRenderTarget;
import net.minecraft.network.chat.Component;

public final class NameUtil {
    public static Component getModeName(ModelRenderTarget model, String modelId) {
        return Component.literal(model.getDisplayName(modelId));
    }
}
