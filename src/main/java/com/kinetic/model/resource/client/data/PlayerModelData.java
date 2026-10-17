package com.kinetic.model.resource.client.data;

import com.kinetic.model.resource.client.AnimationStore;
import com.kinetic.model.resource.client.PlayerModelVariant;
import com.kinetic.geckolib3.file.AnimationControllerFile;
import com.kinetic.util.FifoHashMap;

import java.util.List;
import java.util.Objects;

public record PlayerModelData(FifoHashMap<String, PlayerModelVariant> variants,
                              AnimationStore animations, AnimationStore firstPersonAnimations,
                              List<AnimationControllerFile> animationControllerFiles)
        implements RenderTargetData {
    public PlayerModelData {
        Objects.requireNonNull(variants, "variants");
        Objects.requireNonNull(animations, "animations");
        Objects.requireNonNull(firstPersonAnimations, "firstPersonAnimations");
        animationControllerFiles = List.copyOf(animationControllerFiles);
    }
}
