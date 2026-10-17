package com.kinetic.testutil;

import com.kinetic.natives.render.NativeBakedModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class NativeLibraryLoadTest {
    @Test
    void loadsNativeLibraryBeforeTestsRun() {
        assertDoesNotThrow(() -> NativeBakedModel.capability());
    }
}
