package com.kinetic.model.catalog.content;

import com.kinetic.format.schema.file.ChunkDataSource;
import com.kinetic.format.schema.model.ModelFileView;
import com.kinetic.model.domain.Hash256;
import com.kinetic.model.domain.ModelRepresentation;

/** Immutable content projection exposed to resource loading. */
public interface ModelContent {
    ModelRepresentation representation();

    default Hash256 modelId() {
        return representation().identity().modelId();
    }

    default ModelFileView modelFile() {
        return representation().view();
    }

    ChunkDataSource chunks();
}
