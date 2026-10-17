package com.kinetic.model.resource.client.remote;

import com.kinetic.buffer.UniBuffer;
import com.kinetic.model.domain.ModelFileIdentity;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Network seam for one owned, continuous remote metadata prefix. */
@FunctionalInterface
public interface RemoteMetadataFetcher {
    CompletableFuture<Void> fetchMetadata(
            List<ModelFileIdentity> identities, MetadataReceiver receiver);

    @FunctionalInterface
    interface MetadataReceiver {
        void accept(ModelFileIdentity identity, UniBuffer bytes) throws IOException;
    }
}
