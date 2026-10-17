package com.kinetic.model.catalog.client.entry;

import com.kinetic.model.catalog.snapshot.CatalogAccess;
import com.kinetic.model.domain.Hash256;
import com.kinetic.model.domain.HierarchyPath;

import java.util.Objects;

/** Path-only disabled presentation; exact representation identity remains private. */
public record ClientFailedCatalogEntry(Hash256 modelId, HierarchyPath path,
                                       CatalogAccess access, String error) {
    public ClientFailedCatalogEntry {
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(access, "access");
        error = Objects.requireNonNull(error, "error");
    }

    public String displayPath() {
        return path.value();
    }
}
