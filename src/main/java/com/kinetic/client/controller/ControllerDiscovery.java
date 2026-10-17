package com.kinetic.client.controller;

import com.kinetic.client.entity.CustomEntity;
import com.kinetic.model.resource.client.CommonAsset;

import java.util.function.Predicate;

public interface ControllerDiscovery<T extends CustomEntity<?>, TModel> {
    ControllerFactory<T> process(TModel model, CommonAsset assets);

    default ControllerDiscovery<T, TModel> withCondition(Predicate<T> test) {
        return (model, asset) -> {
            var collection = process(model, asset);
            return (animatable, consumer) -> {
                if (test.test(animatable)) {
                    collection.create(animatable, consumer);
                }
            };
        };
    }
}
