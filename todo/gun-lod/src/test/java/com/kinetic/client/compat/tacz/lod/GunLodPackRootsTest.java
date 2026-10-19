package com.kinetic.client.compat.tacz.lod;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 枪包根目录解析。
 *
 * <p>命名空间取自枪包自己声明的 {@code assets/<ns>/}，不读 {@code gunpack.meta.json}——那个文件可能
 * 缺失、也可能与资源目录名不一致，而资源管理器只认 {@code assets/<ns>/}。解析不出来的命名空间会
 * 让调用方回落到逐枪扫描，属于"更慢但不错"，所以这里的断言只关心能解析出来的那部分。
 */
class GunLodPackRootsTest {

    @Test
    void resolvesNamespaceFromDirectoryLayout(@TempDir Path gameDir) throws Exception {
        var pack = gameDir.resolve("tacz/eos");
        Files.createDirectories(pack.resolve("assets/eos/geo_models/gun"));
        Files.createDirectories(pack.resolve("assets/eos/textures"));
        Files.writeString(pack.resolve("gunpack.meta.json"), "{\"namespace\":\"ignored\"}",
                StandardCharsets.UTF_8);

        var roots = GunLodPackRoots.resolve(gameDir);

        assertEquals(pack, roots.get("eos"), "命名空间来自 assets/<ns>/，不是 meta 文件");
        assertEquals(1, roots.size());
    }

    @Test
    void resolvesEveryNamespaceProvidedByOnePack(@TempDir Path gameDir) throws Exception {
        var pack = gameDir.resolve("tacz/multi");
        Files.createDirectories(pack.resolve("assets/first/geo_models"));
        Files.createDirectories(pack.resolve("assets/second/geo_models"));

        var roots = GunLodPackRoots.resolve(gameDir);

        assertEquals(pack, roots.get("first"));
        assertEquals(pack, roots.get("second"));
    }

    @Test
    void resolvesNamespaceFromZipPack(@TempDir Path gameDir) throws Exception {
        var packs = gameDir.resolve("tacz");
        Files.createDirectories(packs);
        var zip = packs.resolve("zipped.zip");
        try (var out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("assets/zipped/geo_models/gun/a.json"));
            out.write("{}".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("assets/zipped/textures/uv.png"));
            out.write(new byte[]{1, 2, 3});
            out.closeEntry();
        }

        var roots = GunLodPackRoots.resolve(gameDir);

        assertEquals(zip, roots.get("zipped"));
    }

    @Test
    void ignoresPacksWithoutAssetsAndMissingDirectories(@TempDir Path gameDir) throws Exception {
        Files.createDirectories(gameDir.resolve("tacz/broken"));
        Files.writeString(gameDir.resolve("tacz/loose-file.txt"), "x", StandardCharsets.UTF_8);

        assertTrue(GunLodPackRoots.resolve(gameDir).isEmpty());
        assertTrue(GunLodPackRoots.resolve(gameDir.resolve("nowhere")).isEmpty());
        assertTrue(GunLodPackRoots.resolve(null).isEmpty());
    }

    @Test
    void aBrokenPackDoesNotHideAHealthyOne(@TempDir Path gameDir) throws Exception {
        Files.createDirectories(gameDir.resolve("tacz/broken"));
        Files.writeString(gameDir.resolve("tacz/corrupt.zip"), "not a zip",
                StandardCharsets.UTF_8);
        var healthy = gameDir.resolve("tacz/good");
        Files.createDirectories(healthy.resolve("assets/good/geo_models"));

        var roots = GunLodPackRoots.resolve(gameDir);

        assertEquals(healthy, roots.get("good"));
        assertNull(roots.get("broken"));
        assertFalse(roots.containsKey("corrupt"));
    }
}
