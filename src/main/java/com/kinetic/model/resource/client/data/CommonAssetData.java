package com.kinetic.model.resource.client.data;

import com.kinetic.geckolib3.core.molang.value.IValue;
import com.kinetic.model.resource.client.SoundSource;

import java.util.Map;
import java.util.Objects;

public record CommonAssetData(Map<String, SoundSource> sounds, Map<String, IValue> userFunctions) {
    public CommonAssetData {
        Objects.requireNonNull(sounds, "sounds");
        Objects.requireNonNull(userFunctions, "userFunctions");
        sounds = Map.copyOf(sounds);
        userFunctions = Map.copyOf(userFunctions);
    }
}
