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
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import org.jetbrains.annotations.Nullable;
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
        var location = new CatalogModelLocation(
                CatalogRootKind.BUILTIN, BuiltinModelIndex.INTRINSIC_DEFAULT_PATH);
        var expected = index.require(location.path());

        // 构建期已经把 misc/2_steve 转成容器（约 280 KB）。优先直接打开它：
        // 启动时不再解析 6 MB 动画 JSON，也就没有那份 POJO/proto 临时对象。
        var prebuilt = readPrebuiltContainer();
        if (prebuilt != null) {
            ManagedContainer handle = null;
            try {
                handle = ManagedContainer.openResidentDefault(prebuilt, location);
                var actual = handle.representation().modelId();
                if (expected.equals(actual)) {
                    Kinetic.LOGGER.info(
                            "Loaded prebuilt builtin default container bytes={}", prebuilt.length);
                    return handle;
                }
                Kinetic.LOGGER.warn(
                        "Prebuilt builtin default container hash mismatch: index={} prebuilt={}; regenerating",
                        expected, actual);
                handle.representation().close();
            } catch (IOException | RuntimeException error) {
                if (handle != null) {
                    handle.representation().close();
                }
                Kinetic.LOGGER.warn(
                        "Failed to open the prebuilt builtin default container; regenerating from raw resources",
                        error);
            }
        }

        var source = builtinRoot.resolve(BuiltinModelIndex.INTRINSIC_DEFAULT_PATH.value());
        final ModelParser.MemoryContainer generated;
        try (var vfs = new Directory(source)) {
            generated = ModelParser.parseBuiltinDefaultMemory(vfs);
        }
        try (generated) {
            if (!expected.equals(generated.modelHash())) {
                throw new IOException("Builtin intrinsic default model hash mismatch: index="
                        + expected + ", generated=" + generated.modelHash());
            }
            try (var bytes = generated.bytes()) {
                return ManagedContainer.openResidentDefault(bytes, location);
            }
        }
    }

    /** 构建期生成的默认容器；开发环境（直接 runClient、没跑过生成任务）返回 {@code null}。 */
    private static byte @Nullable [] readPrebuiltContainer() {
        var resource = "/assets/kinetic/builtin-default.kinetic";
        try (var in = BuiltinModelCatalog.class.getResourceAsStream(resource)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException error) {
            Kinetic.LOGGER.warn("Failed to read prebuilt builtin default container", error);
            return null;
        }
    }

    @Override
    public void close() {
        defaultHandle.representation().close();
    }
}
