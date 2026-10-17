package com.kinetic.model.resource.client;

import com.kinetic.model.domain.Hash256;

import java.util.Objects;

public record ResourceRequest(Hash256 modelId, String targetId,
                              String requestedTexture, BakeProfile bakeProfile) {
    public ResourceRequest {
        Objects.requireNonNull(modelId, "modelId");
        targetId = Objects.requireNonNull(targetId, "targetId");
        if (targetId.isBlank()) {
            throw new IllegalArgumentException("Render target id cannot be blank");
        }
        requestedTexture = Objects.requireNonNullElse(requestedTexture, "");
        Objects.requireNonNull(bakeProfile, "bakeProfile");
    }
}
