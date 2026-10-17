package com.kinetic.network.dispatch;

import com.kinetic.network.frame.OutboundFrame;

public interface LogicalPacket extends AutoCloseable {
    int fragmentCount();

    long estimateFrameBytes(int index);

    OutboundFrame buildFragment(int index);

    boolean isCancelled();

    @Override
    void close();
}
