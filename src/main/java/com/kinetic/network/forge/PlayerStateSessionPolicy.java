package com.kinetic.network.forge;

import com.kinetic.network.protocol.NegotiatedSessionPolicy;
import com.kinetic.network.protocol.PlayerStateReportPolicy;

final class PlayerStateSessionPolicy {
    private PlayerStateSessionPolicy() {
    }

    static NegotiatedSessionPolicy create(boolean syncRoaming) {
        return new NegotiatedSessionPolicy(
                PlayerStateReportPolicy.gameServer(syncRoaming));
    }
}
