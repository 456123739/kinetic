package com.kinetic.model.resource.client.data;

import com.kinetic.model.resource.client.AnimationStore;
import com.kinetic.geckolib3.file.AnimationControllerFile;
import com.kinetic.geckolib3.geo.render.built.GeoModel;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

public record VehicleModelData(GeoModel geoModel, AnimationStore animations,
                               @Nullable AnimationControllerFile controllerFile) implements RenderTargetData {
    public VehicleModelData {
        Objects.requireNonNull(geoModel, "geoModel");
        Objects.requireNonNull(animations, "animations");
    }
}
