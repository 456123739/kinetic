package com.kinetic.client.compat.tacz.lod;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 枪包指纹：必须<b>对内容敏感</b>，否则枪包更新后会被判成"没变"、新模型永远等不到预热。
 *
 * <p>这正是不能用 {@code gunpack.meta.json} 当身份的原因——作者更新枪包时改的是模型和贴图，
 * 元数据通常原样保留。所以断言集中在"文件动了指纹就得变"。
 */
class GunLodPackFingerprintTest {

    @Test
    void unchangedPackKeepsTheSameFingerprint(@TempDir Path temp) throws Exception {
        var pack = pack(temp);
        var first = GunLodPackFingerprint.of(pack);
        assertNotNull(first);
        assertEquals(first, GunLodPackFingerprint.of(pack), "没动过就必须给出同一个指纹");
    }

    @Test
    void editingFileContentChangesTheFingerprint(@TempDir Path temp) throws Exception {
        var pack = pack(temp);
        var before = GunLodPackFingerprint.of(pack);

        Files.writeString(pack.resolve("assets/eos/geo_models/gun/a.json"),
                "{\"content\":\"updated and longer\"}", StandardCharsets.UTF_8);

        assertNotEquals(before, GunLodPackFingerprint.of(pack), "改了内容必须换指纹");
    }

    @Test
    void rewritingFileWithTheSameLengthStillChangesTheFingerprint(@TempDir Path temp)
            throws Exception {
        var pack = pack(temp);
        var file = pack.resolve("assets/eos/textures/uv.png");
        Files.writeString(file, "AAAA", StandardCharsets.UTF_8);
        var before = GunLodPackFingerprint.of(pack);

        // 长度完全一样，只有修改时间变：枪包作者原地替换贴图就是这个形态。
        Files.writeString(file, "BBBB", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.fromMillis(
                Files.getLastModifiedTime(file).toMillis() + 5_000L));

        assertNotEquals(before, GunLodPackFingerprint.of(pack), "同长度的替换也必须被发现");
    }

    @Test
    void addingOrRemovingFilesChangesTheFingerprint(@TempDir Path temp) throws Exception {
        var pack = pack(temp);
        var before = GunLodPackFingerprint.of(pack);

        Files.writeString(pack.resolve("assets/eos/geo_models/gun/new_gun.json"), "{}",
                StandardCharsets.UTF_8);
        var added = GunLodPackFingerprint.of(pack);
        assertNotEquals(before, added, "新增文件必须换指纹");

        Files.delete(pack.resolve("assets/eos/geo_models/gun/new_gun.json"));
        assertNotEquals(added, GunLodPackFingerprint.of(pack), "删除文件必须换指纹");
    }

    @Test
    void zipPackIsFingerprintedByItsBytes(@TempDir Path temp) throws Exception {
        var zip = temp.resolve("pack.zip");
        writeZip(zip, "one");
        var before = GunLodPackFingerprint.of(zip);
        assertNotNull(before);

        writeZip(zip, "two");
        assertNotEquals(before, GunLodPackFingerprint.of(zip), "压缩包换了就必须换指纹");
    }

    @Test
    void unreadableRootsAreReportedAsUnknown(@TempDir Path temp) throws Exception {
        assertNull(GunLodPackFingerprint.of(null), "null 必须表示无法证明未变");
        assertNull(GunLodPackFingerprint.of(temp.resolve("does-not-exist")));
        Files.createDirectories(temp.resolve("empty-dir"));
        assertNull(GunLodPackFingerprint.of(temp.resolve("empty-dir")),
                "空目录无法证明任何东西，必须返回 null 而不是一个恒定的指纹");
        assertTrue(GunLodPackFingerprint.of(pack(temp)).startsWith("dir:"), "目录指纹带类型前缀");
    }

    private static Path pack(Path temp) throws Exception {
        var pack = temp.resolve("eos");
        Files.createDirectories(pack.resolve("assets/eos/geo_models/gun"));
        Files.createDirectories(pack.resolve("assets/eos/textures"));
        Files.writeString(pack.resolve("gunpack.meta.json"), "{\"namespace\":\"eos\"}",
                StandardCharsets.UTF_8);
        Files.writeString(pack.resolve("assets/eos/geo_models/gun/a.json"), "{\"a\":1}",
                StandardCharsets.UTF_8);
        Files.writeString(pack.resolve("assets/eos/textures/uv.png"), "AAAA",
                StandardCharsets.UTF_8);
        return pack;
    }

    private static void writeZip(Path zip, String payload) throws Exception {
        try (var out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("gunpack.meta.json"));
            out.write("{\"namespace\":\"eos\"}".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("assets/eos/geo_models/gun/a.json"));
            out.write(payload.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    @Test
    void directoryAndZipFingerprintsAreDistinguishable(@TempDir Path temp) throws Exception {
        // 前缀不同，所以即使哈希碰撞也不会把目录和压缩包搞混。
        assertFalse(GunLodPackFingerprint.of(pack(temp)).startsWith("zip:"));
    }
}
