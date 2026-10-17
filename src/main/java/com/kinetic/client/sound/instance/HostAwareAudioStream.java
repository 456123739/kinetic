package com.kinetic.client.sound.instance;

public interface HostAwareAudioStream {
    /** Linearizes the actual channel attachment against stop/release. */
    boolean kinetic$tryHostAdopt();
}
