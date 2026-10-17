package com.kinetic.model.catalog.snapshot;

import com.kinetic.model.domain.Hash256;
import com.kinetic.model.domain.HierarchyPath;

import java.util.Objects;

/** Public catalog projection; representation identity and content stay private. */
public record CatalogEntry(Hash256 modelId, HierarchyPath path,
                           CatalogAccess access, CatalogPresentation presentation) {
    public CatalogEntry {
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(presentation, "presentation");
    }
}
