package com.kinetic.model.resource.client.audio;

import com.kinetic.format.media.SupportedAudioProbe;
import com.kinetic.model.domain.Hash256;
import com.kinetic.model.domain.ModelFileIdentity;
import com.kinetic.model.resource.client.SoundSource;

import java.util.Objects;

record AudioCacheKey(ModelFileIdentity representation, int streamId,
                     SupportedAudioProbe.Encoding encoding, int channels,
                     long sampleRate, long frames, Hash256 logicalHash) {
    AudioCacheKey {
        Objects.requireNonNull(representation, "representation");
        Objects.requireNonNull(encoding, "encoding");
        Objects.requireNonNull(logicalHash, "logicalHash");
    }

    static AudioCacheKey from(SoundSource source) {
        var stream = source.stream();
        return new AudioCacheKey(source.representation(), stream.streamId(),
                stream.encoding(), stream.channels(), stream.sampleRate(),
                stream.frames(), stream.locator().logicalHash());
    }
}
