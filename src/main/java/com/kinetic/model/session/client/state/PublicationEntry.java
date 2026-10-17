package com.kinetic.model.session.client.state;

import com.kinetic.model.catalog.snapshot.CatalogAccess;
import com.kinetic.model.domain.Hash256;
import com.kinetic.model.domain.HierarchyPath;
import com.kinetic.model.domain.ModelFileIdentity;

import java.util.Objects;

/** Lean server authority for one exact representation. */
public record PublicationEntry(ModelFileIdentity identity,
                               HierarchyPath path, CatalogAccess access) {
    public PublicationEntry {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(access, "access");
    }

    public Hash256 modelId() {
        return identity.modelId();
    }

    public Hash256 containerId() {
        return identity.containerId();
    }
}
