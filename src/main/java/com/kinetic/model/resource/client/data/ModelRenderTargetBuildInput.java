package com.kinetic.model.resource.client.data;

import com.kinetic.format.schema.model.views.ModelInfoView;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public record ModelRenderTargetBuildInput(String renderTargetId, RenderTargetData target,
                               CommonAssetData assets, @NotNull ModelInfoView info) {
    public ModelRenderTargetBuildInput {
        if (Objects.requireNonNull(renderTargetId, "renderTargetId").isBlank()) {
            throw new IllegalArgumentException("Render target id cannot be blank");
        }
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(assets, "assets");
        Objects.requireNonNull(info, "info");
    }
}
