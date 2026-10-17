package com.kinetic.client.entity;

import com.kinetic.molang.runtime.Struct;
import org.jetbrains.annotations.Nullable;

public interface IRoamingEntity {
    @Nullable Struct getRoamingStruct();
}
