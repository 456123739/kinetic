package com.kinetic.client.controller;

import com.kinetic.client.entity.CustomEntity;
import com.kinetic.geckolib3.core.controller.IAnimationController;

import java.util.function.Consumer;

public interface ControllerFactory<T extends CustomEntity<?>> {
    void create(T animatable, Consumer<IAnimationController<T>> consumer);
}
