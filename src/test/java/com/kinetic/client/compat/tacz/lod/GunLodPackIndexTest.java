package com.kinetic.client.compat.tacz.lod;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 枪包预热记录的持久化。
 *
 * <p>这份记录的作用是"整包复用"：指纹没变就不读模型、不读贴图、不算哈希。它跨进程存活，
 * 所以读回必须精确；而它只是性能提示，所以任何损坏都只能降级成"重扫一遍"，不能抛错。
 */
class GunLodPackIndexTest {

    @Test
    void survivesAReload(@TempDir Path temp) {
        var file = temp.resolve("gun-lod-packs.txt");
        var index = GunLodPackIndex.load(file);
        assertFalse(index.isComplete("eos", "dir:abc"), "空记录里什么都不算完成");

        index.markComplete("eos", "dir:abc");
        index.markComplete("tacz", "zip:def");

        var reloaded = GunLodPackIndex.load(file);
        assertTrue(reloaded.isComplete("eos", "dir:abc"));
        assertTrue(reloaded.isComplete("tacz", "zip:def"));
        assertEquals(2, reloaded.size());
    }

    @Test
    void aChangedFingerprintIsNotComplete(@TempDir Path temp) {
        var file = temp.resolve("gun-lod-packs.txt");
        var index = GunLodPackIndex.load(file);
        index.markComplete("eos", "dir:abc");

        assertFalse(index.isComplete("eos", "dir:changed"), "指纹变了就必须重扫");
        assertFalse(index.isComplete("other", "dir:abc"), "别的命名空间不受影响");
    }

    @Test
    void markingAgainReplacesTheOldFingerprint(@TempDir Path temp) {
        var file = temp.resolve("gun-lod-packs.txt");
        var index = GunLodPackIndex.load(file);
        index.markComplete("eos", "dir:old");
        index.markComplete("eos", "dir:new");

        var reloaded = GunLodPackIndex.load(file);
        assertFalse(reloaded.isComplete("eos", "dir:old"));
        assertTrue(reloaded.isComplete("eos", "dir:new"));
        assertEquals(1, reloaded.size());
    }

    @Test
    void nullFingerprintNeverCountsAsComplete(@TempDir Path temp) {
        var file = temp.resolve("gun-lod-packs.txt");
        var index = GunLodPackIndex.load(file);
        index.markComplete("eos", "dir:abc");

        // 拿不到枪包内容身份时不能假定它没变。
        assertFalse(index.isComplete("eos", null));
        index.markComplete("eos", null);
        assertEquals(1, index.size());
    }

    @Test
    void corruptOrMissingFileDegradesToEmpty(@TempDir Path temp) throws Exception {
        assertFalse(GunLodPackIndex.load(temp.resolve("missing.txt")).isComplete("eos", "x"));

        var file = temp.resolve("broken.txt");
        Files.writeString(file, "not a valid line\n=no-namespace\nnamespace=\nok=fine\n",
                StandardCharsets.UTF_8);
        var index = GunLodPackIndex.load(file);
        assertTrue(index.isComplete("ok", "fine"), "合法行仍要读进来");
        assertFalse(index.isComplete("", "no-namespace"));
        assertFalse(index.isComplete("namespace", ""));
    }

    @Test
    void saveWithoutAPathIsHarmless() {
        var index = GunLodPackIndex.load(null);
        index.markComplete("eos", "dir:abc");
        assertTrue(index.isComplete("eos", "dir:abc"));
    }

    @Test
    void aTruncatedFileNeverClaimsCompleteness(@TempDir Path temp) throws Exception {
        // 模拟"写到一半被强杀"。原子写让目标文件不可能出现这种内容，但真出现时也不能被骗：
        // 截断只会产生"真实指纹的一个前缀"，而真实指纹是 64 位十六进制加类型前缀，
        // 前缀永远不等于完整值，所以这个包照样会被重扫。
        var file = temp.resolve("gun-lod-packs.txt");
        Files.writeString(file, "eos=dir:abcdefghij\ntacz=zip:0123456789",
                StandardCharsets.UTF_8);
        try (var channel = java.nio.channels.FileChannel.open(file,
                java.nio.file.StandardOpenOption.WRITE)) {
            channel.truncate("eos=dir:abcde".length());
        }

        var index = GunLodPackIndex.load(file);
        assertFalse(index.isComplete("eos", "dir:abcdefghij"),
                "半截指纹绝不能匹配上完整指纹——这正是不能把没扫完的包记成已预热的原因");
        assertFalse(index.isComplete("tacz", "zip:0123456789"), "被截掉的行自然读不到");
    }

    @Test
    void aLineTruncatedBeforeTheSeparatorIsDropped(@TempDir Path temp) throws Exception {
        var file = temp.resolve("gun-lod-packs.txt");
        Files.writeString(file, "eos", StandardCharsets.UTF_8);
        assertFalse(GunLodPackIndex.load(file).isComplete("eos", "dir:abc"));
    }

    @Test
    void anEmptyValueLineIsIgnored(@TempDir Path temp) throws Exception {
        var file = temp.resolve("gun-lod-packs.txt");
        Files.writeString(file, "eos=\n", StandardCharsets.UTF_8);
        assertFalse(GunLodPackIndex.load(file).isComplete("eos", ""));
    }

    @Test
    void staleTemporariesNextToTheIndexAreReclaimed(@TempDir Path temp) throws Exception {
        var file = temp.resolve("gun-lod-packs.txt");
        var stale = temp.resolve("gun-lod-packs.txt.tmp-1-abc");
        Files.writeString(stale, "half written");
        Files.setLastModifiedTime(stale, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() - 7_200_000L));

        GunLodPackIndex.load(file);

        assertFalse(Files.exists(stale), "上次被强杀留下的临时文件要被回收");
    }

    @Test
    void anOversizedFileIsTreatedAsEmpty(@TempDir Path temp) throws Exception {
        // 正常记录一个枪包一行、几百字节。给一个宽松上限，是为了磁盘上万一出现同名巨文件时
        // 不把堆读爆——这里用"第一行是合法记录、后面全是填充"来证明它真的被整体忽略了。
        var file = temp.resolve("gun-lod-packs.txt");
        var builder = new StringBuilder("eos=dir:abcdef\n");
        while (builder.length() < GunLodPackIndex.MAX_BYTES + 1024) {
            builder.append("padding-padding-padding-padding\n");
        }
        Files.writeString(file, builder.toString(), StandardCharsets.UTF_8);
        assertTrue(Files.size(file) > GunLodPackIndex.MAX_BYTES, "测试文件必须真的超限");

        var index = GunLodPackIndex.load(file);

        assertFalse(index.isComplete("eos", "dir:abcdef"), "超限文件必须整体忽略");
        assertEquals(0, index.size());
    }
}
