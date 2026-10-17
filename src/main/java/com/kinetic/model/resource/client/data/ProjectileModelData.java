package com.kinetic.model.resource.client.data;

import com.kinetic.model.resource.client.AnimationStore;
import com.kinetic.geckolib3.file.AnimationControllerFile;
import com.kinetic.geckolib3.geo.render.built.GeoModel;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

public record ProjectileModelData(GeoModel geoModel, AnimationStore animations,
                                  @Nullable AnimationControllerFile controllerFile) implements RenderTargetData {
    public ProjectileModelData {
        Objects.requireNonNull(geoModel, "geoModel");
        Objects.requireNonNull(animations, "animations");
    }
}
