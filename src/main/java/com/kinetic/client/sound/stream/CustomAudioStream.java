package com.kinetic.client.sound.stream;

import net.minecraft.client.sounds.AudioStream;

public interface CustomAudioStream extends AudioStream {
    boolean isClosed();
}
