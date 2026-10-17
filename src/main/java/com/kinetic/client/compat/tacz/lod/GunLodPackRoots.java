package com.kinetic.client.compat.tacz.lod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import org.jetbrains.annotations.Nullable;

/**
 * 枪包根目录解析：资源命名空间 → 枪包根路径。
 *
 * <p>TaCZ 把枪包放在 {@code <gamedir>/tacz/} 下，每个枪包是一个目录或一个 {@code .zip}。
 * 命名空间不取自 {@code gunpack.meta.json}（它可能没有、也可能与目录名不一致），而是取自枪包
 * 自己声明的资源目录：{@code assets/<namespace>/} 下的第一层名字就是它提供的命名空间。
 * 这正好是资源管理器认的那套规则，不需要引用 TaCZ 的任何类型。
 *
 * <p>找不到根路径的命名空间（例如打包在模组 jar 里的官方枪包）不会被解析出来，调用方对它们
 * 回落到逐枪扫描——那类枪包通常只有几十把，代价可以忽略。
 */
public final class GunLodPackRoots {
    /** TaCZ 扫描枪包的目录名，取自 {@code GunPackLoader} 的字节码（{@code GAMEDIR/tacz}）。 */
    static final String PACK_DIRECTORY = "tacz";

    private GunLodPackRoots() {
    }

    /**
     * @param gameDirectory 游戏根目录
     * @return 命名空间 → 枪包根路径；目录不存在时返回空表
     */
    public static Map<String, Path> resolve(@Nullable Path gameDirectory) {
        var result = new HashMap<String, Path>();
        if (gameDirectory == null) {
            return result;
        }
        var packs = gameDirectory.resolve(PACK_DIRECTORY);
        if (!Files.isDirectory(packs)) {
            return result;
        }
        try (Stream<Path> children = Files.list(packs)) {
            for (var child : children.toList()) {
                try {
                    for (var namespace : namespacesOf(child)) {
                        // 同一个命名空间被多个枪包声明时保留第一个；重复声明本来就是枪包冲突。
                        result.putIfAbsent(namespace, child);
                    }
                } catch (IOException | RuntimeException ignored) {
                    // 单个坏枪包不能影响其它枪包。
                }
            }
        } catch (IOException | RuntimeException ignored) {
            return result;
        }
        return result;
    }

    /** 一个枪包提供的全部资源命名空间。 */
    static List<String> namespacesOf(Path pack) throws IOException {
        if (Files.isDirectory(pack)) {
            return namespacesOfDirectory(pack);
        }
        if (Files.isRegularFile(pack) && pack.getFileName().toString().endsWith(".zip")) {
            return namespacesOfZip(pack);
        }
        return List.of();
    }

    private static List<String> namespacesOfDirectory(Path pack) throws IOException {
        var assets = pack.resolve("assets");
        if (!Files.isDirectory(assets)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(assets)) {
            return children.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> !name.isEmpty())
                    .toList();
        }
    }

    private static List<String> namespacesOfZip(Path pack) throws IOException {
        try (var zip = new ZipFile(pack.toFile())) {
            var namespaces = new java.util.LinkedHashSet<String>();
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var name = entries.nextElement().getName();
                // 只认 assets/<ns>/... 这一层。
                if (!name.startsWith("assets/")) {
                    continue;
                }
                var rest = name.substring("assets/".length());
                var slash = rest.indexOf('/');
                if (slash > 0) {
                    namespaces.add(rest.substring(0, slash));
                }
            }
            return List.copyOf(namespaces);
        }
    }
}
