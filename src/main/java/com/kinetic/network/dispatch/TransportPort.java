package com.kinetic.network.dispatch;

import com.kinetic.network.frame.OutboundFrame;

public interface TransportPort {
    boolean isOpen();

    boolean isWritable();

    long highWatermarkBytes();

    long pendingBytes();

    SendResult trySend(OutboundFrame frame);
}
