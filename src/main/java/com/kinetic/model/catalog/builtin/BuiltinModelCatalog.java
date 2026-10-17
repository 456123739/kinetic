package com.kinetic.model.catalog.builtin;

import com.kinetic.Kinetic;
import com.kinetic.format.parser.ModelParser;
import com.kinetic.format.vfs.Directory;
import com.kinetic.model.catalog.source.CatalogModelLocation;
import com.kinetic.model.catalog.source.CatalogRootKind;
import com.kinetic.model.catalog.source.ModelCatalogSources;
import com.kinetic.model.domain.ModelPath;
import com.kinetic.model.storage.ManagedContainer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Objects;

/** Process-stable builtin catalog and its resident intrinsic-default backing. */
public final class BuiltinModelCatalog implements AutoCloseable {
    private final BuiltinModelIndex contract;
    private final ManagedContainer defaultHandle;

    private BuiltinModelCatalog(BuiltinModelIndex contract) {
        this.contract = Objects.requireNonNull(contract, "contract");
        var source = ModelCatalogSources.builtin();
        try {
            defaultHandle = generateDefault(source.path(), contract);
        } catch (IOException error) {
            Kinetic.LOGGER.error("Failed to load the builtin intrinsic default model", error);
            throw new UncheckedIOException(
                    "Failed to build the builtin intrinsic default model", error);
        }
        Kinetic.LOGGER.info(
                "Loaded resident builtin intrinsic default model path={} hash={}",
                defaultHandle.location().path(), defaultHandle.representation().modelId());
    }

    public static BuiltinModelCatalog open(BuiltinModelIndex contract) {
        return new BuiltinModelCatalog(contract);
    }

    public BuiltinModelIndex contract() {
        return contract;
    }

    public ManagedContainer defaultContent() {
        return defaultHandle;
    }

    private static ManagedContainer generateDefault(Path builtinRoot,
                                                     BuiltinModelIndex index)
            throws IOException {
        var source = builtinRoot.resolve(BuiltinModelIndex.INTRINSIC_DEFAULT_PATH.value());
        final ModelParser.MemoryContainer generated;
        try (var vfs = new Directory(source)) {
            generated = ModelParser.parseBuiltinDefaultMemory(vfs);
        }
        try (generated) {
            var location = new CatalogModelLocation(
                    CatalogRootKind.BUILTIN, BuiltinModelIndex.INTRINSIC_DEFAULT_PATH);
            var expected = index.require(location.path());
            if (!expected.equals(generated.modelHash())) {
                throw new IOException("Builtin intrinsic default model hash mismatch: index="
                        + expected + ", generated=" + generated.modelHash());
            }
            try (var bytes = generated.bytes()) {
            return ManagedContainer.openResidentDefault(bytes, location);
            }
        }
    }

    @Override
    public void close() {
        defaultHandle.representation().close();
    }
}
