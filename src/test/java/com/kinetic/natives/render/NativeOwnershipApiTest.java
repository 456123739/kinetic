package com.kinetic.natives.render;

import com.kinetic.buffer.NativeBuffer;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeOwnershipApiTest {
    @Test
    void bakedModelTextureEntryPointsRequireAnOwner() {
        var entryPoints = Arrays.stream(NativeBakedModel.class.getMethods())
                .filter(method -> method.getName().equals("bake")
                        || method.getName().equals("tryBake"))
                .toList();

        assertFalse(entryPoints.stream().anyMatch(method ->
                Arrays.stream(method.getParameterTypes())
                        .anyMatch(type -> type == long.class)));
        assertTrue(entryPoints.stream().anyMatch(method ->
                Arrays.stream(method.getParameterTypes())
                        .anyMatch(type -> type == NativeBuffer.class)));
    }
}
