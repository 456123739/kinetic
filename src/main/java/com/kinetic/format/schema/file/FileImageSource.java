package com.kinetic.format.schema.file;

import com.kinetic.buffer.ArrayBuffer;
import com.kinetic.model.domain.Hash256;
import com.kinetic.model.storage.ModelHashing;
import com.kinetic.natives.image.Image;
import com.kinetic.natives.image.ImageSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Reads and validates an encoded image stored as a standalone file. */
public final class FileImageSource implements ImageSource {
    private final Path file;
    private final int expectedSize;
    private final Hash256 expectedHash;
    private final String expectedEncoding;

    public FileImageSource(Path file, int expectedSize, Hash256 expectedHash,
                           String expectedEncoding) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        if (expectedSize < 0) {
            throw new IllegalArgumentException("Expected image size cannot be negative");
        }
        this.expectedSize = expectedSize;
        this.expectedHash = Objects.requireNonNull(expectedHash, "expectedHash");
        this.expectedEncoding = Objects.requireNonNull(expectedEncoding, "expectedEncoding");
    }

    @Override
    public Image open() throws IOException {
        var bytes = Files.readAllBytes(file);
        if (bytes.length != expectedSize) {
            throw new IOException("Image file size changed: " + file);
        }
        if (!ModelHashing.blake3(bytes).equals(expectedHash)) {
            throw new IOException("Image file content hash changed: " + file);
        }
        try (var data = ArrayBuffer.move(bytes)) {
            var image = Image.probe(data);
            if (!image.format().name().equalsIgnoreCase(expectedEncoding)) {
                image.close();
                throw new IOException("Image file encoding changed: " + file);
            }
            return image;
        }
    }

    @Override
    public String toString() {
        return file.toString();
    }
}
