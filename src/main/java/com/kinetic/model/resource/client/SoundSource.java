package com.kinetic.model.resource.client;

import com.kinetic.format.schema.model.views.SoundStreamView;
import com.kinetic.model.domain.ModelFileIdentity;

import java.util.Objects;

/** Metadata-only locator for one sound in an exact activated representation. */
public record SoundSource(ModelFileIdentity representation, SoundStreamView stream) {
    public SoundSource {
        Objects.requireNonNull(representation, "representation");
        Objects.requireNonNull(stream, "stream");
    }
}
