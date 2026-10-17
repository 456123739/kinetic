package com.kinetic.util;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.stream.Stream;

/**
 * 原子写小文件：唯一临时文件 → fsync → 原子改名。
 *
 * <p>给"缓存索引 / 附属元数据"这类**进程随时可能被强杀**的持久化用。要求是两条：
 *
 * <ol>
 *   <li><b>目标文件永远不会是半成品。</b>内容先写进同目录的唯一临时文件并 fsync，再原子改名。
 *       被强杀只会留下一个临时文件，目标文件要么是旧的完整内容、要么是新的完整内容。</li>
 *   <li><b>并发写不会互相撕。</b>临时文件名带 pid 与随机后缀，两个写者不会写同一个临时文件。
 *       代价是极端情况下会留下孤儿临时文件，由 {@link #sweepStaleTemps} 按年龄回收。</li>
 * </ol>
 *
 * <p>这里保证的是"进程被强杀"这一档。掉电/内核崩溃需要文件系统与硬件层面的保证，不在本类的
 * 覆盖范围内——本类只做到 fsync 文件内容。
 */
public final class AtomicFileWrites {
    /** 临时文件多久算过期。远大于任何一次写入的耗时，因此不会误删正在写的临时文件。 */
    static final long STALE_TEMP_MILLIS = 3_600_000L;

    private AtomicFileWrites() {
    }

    /**
     * 原子地把 {@code bytes} 写成 {@code target}。
     *
     * <p>父目录不存在时会创建。失败会抛 {@link IOException}，调用方决定是否降级。
     */
    public static void write(Path target, byte[] bytes) throws IOException {
        var parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        var temporary = target.resolveSibling(target.getFileName() + ".tmp-"
                + ProcessHandle.current().pid() + "-" + java.util.UUID.randomUUID());
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                var buffer = java.nio.ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            move(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * 同目录下、名字以 {@code prefix} 开头且以 {@code .tmp-} 结尾、且超过
     * {@link #STALE_TEMP_MILLIS} 没动过的文件删掉。
     *
     * <p>只按年龄回收：正在写的临时文件刚被创建，绝不会被误删；而两个游戏实例共用同一个目录时，
     * 也不会把对方正在写的文件删掉。
     */
    public static void sweepStaleTemps(Path directory, String prefix) {
        if (directory == null || prefix == null || prefix.isEmpty()) {
            return;
        }
        try (Stream<Path> children = Files.list(directory)) {
            var deadline = System.currentTimeMillis() - STALE_TEMP_MILLIS;
            for (var child : children.toList()) {
                var name = child.getFileName().toString();
                if (!name.startsWith(prefix) || !name.contains(".tmp-")) {
                    continue;
                }
                try {
                    if (Files.isRegularFile(child)
                            && Files.getLastModifiedTime(child).toMillis() < deadline) {
                        Files.deleteIfExists(child);
                    }
                } catch (IOException | RuntimeException ignored) {
                    // 单个文件删不掉不影响其它文件。
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // 目录读不到就什么都不清理。
        }
    }

    /**
     * 优先原子改名；文件系统不支持时退回普通改名。
     *
     * <p>退回路径在"改名途中被强杀"这个极窄的窗口里可能留下半成品，但调用方读取时都会校验
     * 内容（长度、指纹、哈希），所以半成品只会被当成"没有"，不会被当成"有效"。
     */
    private static void move(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
