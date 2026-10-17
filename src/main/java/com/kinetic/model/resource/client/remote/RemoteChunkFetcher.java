package com.kinetic.model.resource.client.remote;

import com.kinetic.buffer.UniBuffer;
import com.kinetic.format.container.AssetContainerView;
import com.kinetic.model.domain.ModelFileIdentity;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Network-adapter seam used only when a verified remote representation is absent. */
@FunctionalInterface
public interface RemoteChunkFetcher {
    CompletableFuture<Void> fetch(
            ModelFileIdentity identity, List<AssetContainerView.ChunkInfo> chunks,
            ChunkReceiver receiver);

    @FunctionalInterface
    interface ChunkReceiver {
        void accept(AssetContainerView.ChunkInfo chunk, UniBuffer bytes) throws IOException;
    }
}
