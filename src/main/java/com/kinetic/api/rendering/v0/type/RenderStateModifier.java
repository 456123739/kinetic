package com.kinetic.api.rendering.v0.type;

import com.kinetic.api.internal.annotation.ParallelInvoke;
import com.kinetic.geckolib3.geo.GeoRenderData;

public interface RenderStateModifier<E, R extends GeoRenderData> {
    @ParallelInvoke("entity")
    void apply(E target, R geoRenderData);
}
