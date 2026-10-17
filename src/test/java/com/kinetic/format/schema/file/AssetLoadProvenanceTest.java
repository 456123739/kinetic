package com.kinetic.format.schema.file;

import com.kinetic.buffer.ArrayBuffer;
import com.kinetic.buffer.BufferType;
import com.kinetic.format.AssetLoadException;
import com.kinetic.format.container.AssetContainerView;
import com.kinetic.format.container.ChunkDecoding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AssetLoadProvenanceTest {
    @TempDir
    Path temp;

    @Test
    void missingFileIsAccessFailure() {
        var source = new FileChunkDataSource(temp.resolve("missing.ysm"));
        var failure = assertThrows(AssetLoadException.class, () ->
                source.readPayload(chunk(1), BufferType.ARRAY));

        assertEquals(AssetLoadException.Reason.ACCESS, failure.reason());
    }

    @Test
    void invalidChunkSizeIsContentFailure() {
        try (var bytes = ArrayBuffer.move(new byte[]{1})) {
            var failure = assertThrows(AssetLoadException.class, () ->
                    ChunkDecoding.validateDirectPayload(bytes, chunk(2)));

            assertEquals(AssetLoadException.Reason.CONTENT, failure.reason());
        }
    }

    private static AssetContainerView.ChunkInfo chunk(int size) {
        return new AssetContainerView.ChunkInfo(
                "test", "", 0, size, size, 0, 0, 0, null);
    }
}
