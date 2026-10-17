package com.kinetic.network.protocol;

import com.kinetic.proto.network.EntityRef;

/** Encodes current-level entity routing without advertising stable player identity. */
public final class EntityRefEncoder {
    private EntityRefEncoder() {
    }

    public static EntityRef encode(int entityId) {
        return EntityRef.newBuilder()
                .setEntityId(entityId).build();
    }
}
