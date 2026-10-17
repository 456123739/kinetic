package com.kinetic.network.protocol;

import com.kinetic.model.domain.Hash256;
import com.kinetic.proto.network.StarredModelsSnapshot;
import com.kinetic.util.ProtoBytes;

import java.util.Comparator;
import java.util.Set;

/** Builds the side-neutral payload used before any client-only control code is needed. */
public final class StarredModelSnapshots {
    public static final int MAX_MODEL_SET_SIZE = 16_384;

    private StarredModelSnapshots() {
    }

    public static StarredModelsSnapshot create(Set<Hash256> hashes) {
        var result = StarredModelsSnapshot.newBuilder();
        hashes.stream().sorted(Comparator.naturalOrder()).limit(MAX_MODEL_SET_SIZE)
                .forEach(hash -> result.addModelHashes(ProtoBytes.wrap(hash)));
        return result.build();
    }
}
