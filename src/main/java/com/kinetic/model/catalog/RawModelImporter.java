package com.kinetic.model.catalog;

import com.kinetic.format.parser.DefaultAnimationFilter;
import com.kinetic.format.parser.CapturedModel;
import com.kinetic.format.parser.ModelParser;
import com.kinetic.format.parser.RawCompileResult;
import com.kinetic.format.vfs.Directory;
import com.kinetic.natives.NativeArchive;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class RawModelImporter {
    private final DefaultAnimationFilter defaultAnimations;

    public RawModelImporter(DefaultAnimationFilter defaultAnimations) {
        this.defaultAnimations = Objects.requireNonNull(defaultAnimations, "defaultAnimations");
    }

    public CapturedModel capture(Path source) {
        if (Files.isDirectory(source)) {
            try (var vfs = new Directory(source)) {
                return ModelParser.capture(vfs);
            }
        }
        try (var vfs = new NativeArchive(source.toString())) {
            return ModelParser.capture(vfs);
        }
    }

    public RawCompileResult convert(CapturedModel captured, Path outputDirectory) {
        Objects.requireNonNull(captured, "captured");
        var compiled = ModelParser.compile(
                captured.data().asVirtualFileSystem(), outputDirectory, defaultAnimations);
        if (!captured.modelId().equals(compiled.modelHash())) {
            throw new IllegalStateException(
                    "Captured and compiled model identities do not match");
        }
        if (!captured.diagnostics().equals(compiled.diagnostics())) {
            throw new IllegalStateException(
                    "Captured and compiled model diagnostics do not match");
        }
        return compiled;
    }
}
