package com.kinetic.natives.legacy;

import com.kinetic.buffer.NativeBuffer;
import com.kinetic.buffer.UniBuffer;
import com.kinetic.natives.buffer.BufferArgument;

import java.io.EOFException;
import java.io.IOException;
import java.lang.ref.Reference;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

final class NativeLegacyImporter {
    private NativeLegacyImporter() {
    }

    static NativeLegacyProtocol.Response invoke(Path source) {
        Objects.requireNonNull(source, "source");
        try {
            var size = Files.size(source);
            if (size > UniBuffer.MAX_SIZE) {
                return new NativeLegacyProtocol.Failure(
                        NativeLegacyStatus.RESOURCE_LIMIT,
                        "Legacy source exceeds the Java buffer limit");
            }
            try (var bytes = NativeBuffer.allocate(Math.toIntExact(size));
                 var channel = FileChannel.open(source, StandardOpenOption.READ)) {
                var destination = bytes.nio();
                while (destination.hasRemaining()) {
                    if (channel.read(destination) < 0) {
                        throw new EOFException("Legacy source was truncated while reading");
                    }
                }
                return invoke(bytes);
            }
        } catch (IOException | SecurityException failure) {
            return new NativeLegacyProtocol.Failure(
                    NativeLegacyStatus.SOURCE_IO,
                    "Unable to read legacy source");
        }
    }

    private static NativeLegacyProtocol.Response invoke(NativeBuffer source) {
        var input = BufferArgument.packInput(source);
        try {
            return NativeLegacyProtocol.accept(nImport(input.obj(), input.flags()));
        } finally {
            Reference.reachabilityFence(source);
        }
    }

    static void release(long handle) {
        nRelease(handle);
    }

    private static native NativeLegacyImportResult nImport(
            Object source, long sourceFlags);

    private static native void nRelease(long handle);
}
