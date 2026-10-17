package com.kinetic.model.resource.client;

import com.kinetic.util.Closeable;

public interface ResourceLease extends Closeable {
    AcquireResult poll();

    boolean isCurrent(ResourceRequest request);

    /** Releases this handle's exact interest only while its Flight is Pending. */
    void cancelPending();
}
