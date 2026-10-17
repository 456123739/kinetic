package com.kinetic.client.controller;

import com.kinetic.client.entity.CustomEntity;
import com.kinetic.model.resource.client.CommonAsset;
import com.kinetic.geckolib3.core.controller.IAnimationController;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;

import java.util.Objects;
import java.util.function.Consumer;

public class AnimationControllerCollection<T extends CustomEntity<?>, TModel> {
    private final ReferenceArrayList<ControllerDiscovery<T, TModel>> discoveries = new ReferenceArrayList<>();
    private boolean initialized;

    public synchronized void initialize(Runnable initializer) {
        Objects.requireNonNull(initializer, "initializer");
        if (initialized) {
            return;
        }
        initializer.run();
        initialized = true;
    }

    public Consumer<T> build(TModel model, CommonAsset assets) {
        var factories = new ReferenceArrayList<ControllerFactory<T>>(discoveries.size());
        for (var discovery : discoveries) {
            factories.add(discovery.process(model, assets));
        }
        return animatable -> {
            Consumer<IAnimationController<T>> consumer = animatable::addAnimationController;
            for (var factory : factories) {
                factory.create(animatable, consumer);
            }
        };
    }

    public ControllerDiscovery<T, TModel> add(ControllerDiscovery<T, TModel> factory) {
        Objects.requireNonNull(factory, "factory");
        discoveries.add(factory);
        return factory;
    }
}
