package com.kinetic.util;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 原子写的两条契约：目标文件永不是半成品，以及失败时不破坏旧内容。
 *
 * <p>这里能验证的是"写完之后的可见状态"；"被强杀的那一刻"无法在单测里制造，靠的是实现上的
 * 结构（唯一临时文件 + fsync + 原子改名）与下面的失败路径断言。
 */
class AtomicFileWritesTest {

    @Test
    void writesExactContentAndLeavesNoTemporaryBehind(@TempDir Path temp) throws Exception {
        var target = temp.resolve("a.txt");
        AtomicFileWrites.write(target, "hello".getBytes(StandardCharsets.UTF_8));

        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(target));
        try (var children = Files.list(temp)) {
            assertTrue(children.allMatch(path -> path.getFileName().toString().equals("a.txt")),
                    "写完不该留下临时文件");
        }
    }

    @Test
    void createsParentDirectories(@TempDir Path temp) throws Exception {
        var target = temp.resolve("nested/deep/a.txt");
        AtomicFileWrites.write(target, new byte[]{1, 2, 3});
        assertTrue(Files.isRegularFile(target));
    }

    @Test
    void aFailedWriteKeepsTheOldContentIntact(@TempDir Path temp) throws Exception {
        var target = temp.resolve("a.txt");
        AtomicFileWrites.write(target, "old".getBytes(StandardCharsets.UTF_8));

        // 把目标变成一个非空目录：改名必然失败，从而模拟"写到一半出错"。
        Files.delete(target);
        Files.createDirectory(target);
        Files.writeString(target.resolve("blocker"), "x");

        assertThrows(Exception.class,
                () -> AtomicFileWrites.write(target, "new".getBytes(StandardCharsets.UTF_8)));
        // 旧状态没有被破坏（这里是一个目录，不是被截断的文件）。
        assertTrue(Files.isDirectory(target));
        assertTrue(Files.exists(target.resolve("blocker")));
    }

    @Test
    void overwriteReplacesTheWholeContent(@TempDir Path temp) throws Exception {
        var target = temp.resolve("a.txt");
        AtomicFileWrites.write(target, "a-much-longer-old-content".getBytes(StandardCharsets.UTF_8));
        AtomicFileWrites.write(target, "new".getBytes(StandardCharsets.UTF_8));

        assertArrayEquals("new".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(target),
                "覆盖必须整体替换，不能残留旧尾巴");
    }

    @Test
    void sweepRemovesOnlyOldTemporariesOfOurOwnPrefix(@TempDir Path temp) throws Exception {
        var stale = temp.resolve("index.txt.tmp-123-abc");
        var fresh = temp.resolve("index.txt.tmp-456-def");
        var other = temp.resolve("other.txt.tmp-789-ghi");
        var real = temp.resolve("index.txt");
        Files.writeString(stale, "x");
        Files.writeString(fresh, "x");
        Files.writeString(other, "x");
        Files.writeString(real, "keep me");

        Files.setLastModifiedTime(stale, FileTime.fromMillis(
                System.currentTimeMillis() - AtomicFileWrites.STALE_TEMP_MILLIS - 60_000L));

        AtomicFileWrites.sweepStaleTemps(temp, "index.txt");

        assertFalse(Files.exists(stale), "过期的自家临时文件要被回收");
        assertTrue(Files.exists(fresh), "刚创建的临时文件可能是别人正在写的，不能删");
        assertTrue(Files.exists(other), "前缀不匹配的文件不能动");
        assertTrue(Files.exists(real), "目标文件不能动");
    }

    @Test
    void sweepOnAMissingDirectoryIsHarmless(@TempDir Path temp) {
        AtomicFileWrites.sweepStaleTemps(temp.resolve("nope"), "index.txt");
        AtomicFileWrites.sweepStaleTemps(null, "index.txt");
        AtomicFileWrites.sweepStaleTemps(temp, null);
        AtomicFileWrites.sweepStaleTemps(temp, "");
    }
}
