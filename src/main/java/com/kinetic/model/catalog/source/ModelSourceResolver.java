package com.kinetic.model.catalog.source;

import com.kinetic.format.AssetLoadException;
import com.kinetic.format.parser.RawModelDiagnostic;
import com.kinetic.model.catalog.RawModelImporter;
import com.kinetic.model.catalog.content.DirectContainerAdmission;
import com.kinetic.model.catalog.snapshot.CatalogIndexEntry;
import com.kinetic.model.domain.ModelFileIdentity;
import com.kinetic.model.storage.ConvertedObjectStore;
import com.kinetic.model.storage.ConvertedSourceIndex;
import com.kinetic.model.storage.ConvertedSourceIndexStore;
import com.kinetic.model.storage.ManagedContainer;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class ModelSourceResolver {
    private final RawModelImporter importer;
    private final ConvertedSourceIndexStore indexes;
    private final ConvertedObjectStore objects;

    public ModelSourceResolver(RawModelImporter importer,
                               ConvertedSourceIndexStore indexes,
                               ConvertedObjectStore objects) {
        this.importer = Objects.requireNonNull(importer, "importer");
        this.indexes = Objects.requireNonNull(indexes, "indexes");
        this.objects = Objects.requireNonNull(objects, "objects");
    }

    public Resolution resolve(SourceObservation observation) throws CatalogBuildException {
        var location = new CatalogModelLocation(observation.key().root().rootKind(),
                observation.key().relativePath());
        if (observation.key().sourceKind() == ModelSourceKind.DIRECT_CONTAINER) {
            return validateDirect(observation, location);
        }
        if (observation.key().sourceKind() == ModelSourceKind.LEGACY_ARCHIVE) {
            // v3 加密容器是唯一会走 native 解密的加载路径；该层已移除。
            // 这里显式拒绝，绝不允许把加密字节当明文解析。
            throw new ModelSourceException(
                    "Encrypted legacy v3 .kinetic containers are not supported: native "
                            + "decryption was removed. Re-export the model as a plaintext "
                            + "raw directory/archive or a current .mxc container.");
        }
        if (observation.key().sourceKind() == ModelSourceKind.UNSUPPORTED_KINETIC) {
            throw new ModelSourceException("Unsupported .kinetic container");
        }

        var rawRelativePath = observation.key().root().rootKind().namespace()
                + "/" + observation.key().relativePath().value();
        try (var captured = importer.capture(observation.absolutePath())) {
            var indexed = indexes.find(rawRelativePath)
                    .filter(entry -> entry.modelId().equals(captured.modelId()));
            if (indexed.isPresent()) {
                var existing = objects.findIdentity(indexed.get());
                if (existing.isPresent()) {
                    var identity = existing.get();
                    var entry = new CatalogIndexEntry(identity,
                            location, objects.objectPath(identity.modelId(), identity.containerId()));
                    return new Resolution(entry, indexed, captured.diagnostics());
                }
            }

            Files.createDirectories(objects.temporaryRoot());
            var temporaryDirectory = Files.createTempDirectory(
                    objects.temporaryRoot(), "convert-");
            try {
                var compiled = importer.convert(captured, temporaryDirectory);
                var object = objects.commit(compiled, location);
                var entry = new ConvertedSourceIndex(object, rawRelativePath,
                        indexes.fullModVersion());
                var catalogEntry = new CatalogIndexEntry(
                        object, location,
                        objects.objectPath(object.modelId(), object.containerId()));
                return new Resolution(catalogEntry, Optional.of(entry),
                        compiled.diagnostics());
            } finally {
                deleteTree(temporaryDirectory);
            }
        } catch (ModelSourceException | CatalogInfrastructureException failure) {
            throw failure;
        } catch (IOException | SecurityException failure) {
            throw new CatalogInfrastructureException(
                    "Failed to access converted model storage", failure);
        } catch (RuntimeException failure) {
            throw new ModelSourceException("Failed to capture or convert raw model", failure);
        }
    }

    public MaterializedResolution resolveMaterialized(SourceObservation observation)
            throws CatalogBuildException, IOException {
        if (observation.key().sourceKind() == ModelSourceKind.DIRECT_CONTAINER) {
            var location = new CatalogModelLocation(observation.key().root().rootKind(),
                    observation.key().relativePath());
            try {
                var content = ManagedContainer.openDirect(
                        observation.absolutePath(), location);
                try {
                    DirectContainerAdmission.requireEmbeddedPreview(content);
                    return new MaterializedResolution(new CatalogIndexEntry(
                            content.representation().identity(), location,
                            observation.absolutePath()), content,
                            Optional.empty(), List.of());
                } catch (IOException | RuntimeException | Error failure) {
                    content.representation().close();
                    throw failure;
                }
            } catch (AssetLoadException failure) {
                if (failure.reason() == AssetLoadException.Reason.ACCESS) {
                    throw new CatalogInfrastructureException(
                            "Failed to access direct model container", failure);
                }
                throw new ModelSourceException(
                        "Direct model container is invalid", failure);
            } catch (FileSystemException | SecurityException failure) {
                throw new CatalogInfrastructureException(
                        "Failed to access direct model container", failure);
            } catch (IOException failure) {
                throw new ModelSourceException(
                        "Direct model container is invalid", failure);
            } catch (RuntimeException failure) {
                throw new ModelSourceException(
                        "Direct model container is invalid", failure);
            }
        }
        var resolved = resolve(observation);
        var content = ManagedContainer.openIndexed(resolved.entry());
        try {
            return new MaterializedResolution(resolved.entry(), content,
                    resolved.convertedIndexEntry(), resolved.diagnostics());
        } catch (RuntimeException | Error failure) {
            content.representation().close();
            throw failure;
        }
    }

    private Resolution validateDirect(SourceObservation observation,
                                      CatalogModelLocation location)
            throws CatalogBuildException {
        try {
            var content = ManagedContainer.openDirect(observation.absolutePath(), location);
            try {
                DirectContainerAdmission.requireEmbeddedPreview(content);
                return new Resolution(new CatalogIndexEntry(
                        content.representation().identity(), location, observation.absolutePath()),
                        Optional.empty(), List.of());
            } finally {
                content.representation().close();
            }
        } catch (AssetLoadException failure) {
            if (failure.reason() == AssetLoadException.Reason.ACCESS) {
                throw new CatalogInfrastructureException(
                        "Failed to access direct model container", failure);
            }
            throw new ModelSourceException("Direct model container is invalid", failure);
        } catch (FileSystemException | SecurityException failure) {
            throw new CatalogInfrastructureException(
                    "Failed to access direct model container", failure);
        } catch (IOException failure) {
            throw new ModelSourceException("Direct model container is invalid", failure);
        } catch (RuntimeException failure) {
            throw new ModelSourceException("Direct model container is invalid", failure);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure)
                    throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public void replaceIndex(Set<String> rootNamespaces,
                             Collection<ConvertedSourceIndex> entries)
            throws CatalogInfrastructureException {
        try {
            indexes.replaceScopes(rootNamespaces, entries);
        } catch (IOException failure) {
            throw new CatalogInfrastructureException(
                    "Failed to replace converted-source index", failure);
        }
    }

    public record Resolution(CatalogIndexEntry entry,
                             Optional<ConvertedSourceIndex> convertedIndexEntry,
                             List<RawModelDiagnostic> diagnostics) {
        public Resolution {
            Objects.requireNonNull(entry, "entry");
            Objects.requireNonNull(convertedIndexEntry, "convertedIndexEntry");
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public record MaterializedResolution(
            CatalogIndexEntry entry, ManagedContainer content,
            Optional<ConvertedSourceIndex> convertedIndexEntry,
            List<RawModelDiagnostic> diagnostics) {
        public MaterializedResolution {
            Objects.requireNonNull(entry, "entry");
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(convertedIndexEntry, "convertedIndexEntry");
            diagnostics = List.copyOf(diagnostics);
        }
    }

}
