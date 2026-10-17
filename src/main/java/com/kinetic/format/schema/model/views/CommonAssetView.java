package com.kinetic.format.schema.model.views;

import com.kinetic.format.schema.file.AssetFileView;
import com.kinetic.format.schema.file.ChunkDataSource;
import com.kinetic.proto.mixel.asset.strings.StringData;
import com.kinetic.proto.mixel.manifest.asset.Common;
import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

public class CommonAssetView {
    private final AssetFileView view;
    private final Map<String, SoundStreamView> sounds;
    private final int stringsBlobId;

    public CommonAssetView(Common commonAsset,
                           AssetFileView view) throws IOException {
        this.view = view;
        var byName = new LinkedHashMap<String, SoundStreamView>(commonAsset.sounds().size());
        var byStream = new LinkedHashMap<Integer, SoundStreamView>();
        for (var sound : commonAsset.sounds()) {
            var stream = SoundStreamView.create(sound, view);
            if (byName.putIfAbsent(stream.name(), stream) != null) {
                throw new IOException("Duplicate sound name: " + stream.name());
            }
            var previous = byStream.putIfAbsent(stream.streamId(), stream);
            if (previous != null && !previous.hasSameMedia(stream)) {
                throw new IOException("Conflicting descriptors for sound stream "
                        + Integer.toUnsignedLong(stream.streamId()));
            }
        }
        this.sounds = Map.copyOf(byName);
        this.stringsBlobId = commonAsset.stringsBlobId();
    }

    public Map<String, SoundStreamView> sounds() {
        return sounds;
    }

    public void validateSoundContent(BooleanSupplier cancelled,
                                     ChunkDataSource source) throws IOException {
        var validated = new HashSet<Integer>();
        for (var sound : sounds.values()) {
            if (!validated.add(sound.streamId())) {
                continue;
            }
            try (var ignored = sound.readVerified(cancelled, source)) {
                // Admission is the validation boundary for callers that do not claim M2.
            }
        }
    }

    public StringData readStringData(BooleanSupplier cancelled,
                                                          ChunkDataSource source)
            throws IOException {
        return view.readProtoBlob(cancelled, source, stringsBlobId,
                StringData::parseFrom);
    }
}
