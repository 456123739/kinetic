package com.kinetic.model.resource.client.audio;

interface CachedAudio extends AutoCloseable {
    int size();

    boolean pcm();

    CachedAudio acquire();

    @Override
    void close();
}
