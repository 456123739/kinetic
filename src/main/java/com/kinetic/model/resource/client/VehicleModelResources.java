package com.kinetic.model.resource.client;

import com.kinetic.client.controller.collections.VehicleControllerCollection;
import com.kinetic.client.entity.CustomVehicleEntity;
import com.kinetic.geckolib3.core.builder.controller.AnimationControllerData;
import com.kinetic.geckolib3.geo.render.built.GeoModel;
import it.unimi.dsi.fastutil.objects.Object2ReferenceMap;

import java.util.function.Consumer;

public class VehicleModelResources implements RenderTargetResources {
    private final GeoModel model;
    private final AnimationStore animations;
    private final Object2ReferenceMap<String, AnimationControllerData> controllers;
    private final Consumer<CustomVehicleEntity> controllerFactory;

    public VehicleModelResources(GeoModel model, AnimationStore animations, Object2ReferenceMap<String, AnimationControllerData> controllers, CommonAsset assets) {
        this.model = model;
        this.animations = animations;
        this.controllers = controllers;
        this.controllerFactory = VehicleControllerCollection.build(this, assets);
    }

    public GeoModel model() {
        return model;
    }

    public AnimationStore animations() {
        return animations;
    }

    public Object2ReferenceMap<String, AnimationControllerData> controllers() {
        return controllers;
    }

    public Consumer<CustomVehicleEntity> controllerFactory() {
        return controllerFactory;
    }
}
