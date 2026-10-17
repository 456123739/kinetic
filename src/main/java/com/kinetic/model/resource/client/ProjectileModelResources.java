package com.kinetic.model.resource.client;

import com.kinetic.client.controller.collections.ProjectileControllerCollection;
import com.kinetic.client.entity.CustomProjectileEntity;
import com.kinetic.geckolib3.core.builder.controller.AnimationControllerData;
import com.kinetic.geckolib3.geo.render.built.GeoModel;
import it.unimi.dsi.fastutil.objects.Object2ReferenceMap;

import java.util.function.Consumer;

public class ProjectileModelResources implements RenderTargetResources {
    private final GeoModel model;
    private final AnimationStore animations;
    private final Object2ReferenceMap<String, AnimationControllerData> controllers;
    private final Consumer<CustomProjectileEntity> controllerFactory;

    public ProjectileModelResources(GeoModel model, AnimationStore animations, Object2ReferenceMap<String, AnimationControllerData> controllers, CommonAsset assets) {
        this.model = model;
        this.animations = animations;
        this.controllers = controllers;
        this.controllerFactory = ProjectileControllerCollection.build(this, assets);
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

    public Consumer<CustomProjectileEntity> controllerFactory() {
        return controllerFactory;
    }
}
