package com.kinetic.model.catalog.snapshot;

import com.kinetic.model.catalog.source.CatalogModelLocation;

import com.kinetic.model.domain.Hash256;
import com.kinetic.model.domain.ModelFileIdentity;

import java.nio.file.Path;
import java.util.Objects;

/** Reopenable exact identity discovered without materializing model content. */
public record CatalogIndexEntry(ModelFileIdentity identity,
                                CatalogModelLocation location, Path backingFile) {
    public CatalogIndexEntry {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(location, "location");
        backingFile = Objects.requireNonNull(backingFile, "backingFile")
                .toAbsolutePath().normalize();
    }

    public Hash256 modelId() {
        return identity.modelId();
    }

    public Hash256 containerId() {
        return identity.containerId();
    }
}
