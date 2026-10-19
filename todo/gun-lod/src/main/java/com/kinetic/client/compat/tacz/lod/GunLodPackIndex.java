package com.kinetic.client.compat.tacz.lod;

import com.kinetic.util.AtomicFileWrites;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * "这个枪包已经整包预热过"的记录。
 *
 * <p>没有它，每次启动都要把上千把枪的模型与贴图重新读一遍、重新算一遍内容哈希，才能知道
 * 哪些已经热了。有了它，只要枪包内容的指纹没变，整包直接跳过——一次文件都不读。
 *
 * <p>记录本身很小：{@code 命名空间=指纹}，一个枪包一行。它放在烘焙缓存的 profile 目录下，
 * 因此 {@code CACHE_ABI} 或原生能力变化时会跟着换目录，不会拿旧记录去套新格式。
 *
 * <p><b>它只是性能提示，不是权威。</b>判错只会导致"该预热没预热"或"白扫一遍"，
 * 不会让渲染用错模型——按需路径永远重新算真实的缓存键，见 {@link GunLodPackFingerprint}。
 */
public final class GunLodPackIndex {
    /** 文件名；与 baked 缓存同目录。 */
    static final String FILE_NAME = "gun-lod-packs.txt";

    /**
     * 读入时的体积上限。
     *
     * <p>真实记录是"一个枪包一行"，几百个枪包也就几十 KB。给一个宽松但有限的上限，
     * 是为了让磁盘上万一出现一个同名巨文件时不会把堆读爆——超限当作空记录，只会重扫一遍。
     */
    static final long MAX_BYTES = 4L * 1024 * 1024;

    private final Path file;
    private final Map<String, String> complete = new HashMap<>();

    private GunLodPackIndex(Path file) {
        this.file = file;
    }

    /** 读入记录；文件不存在或损坏时返回空记录（等价于"什么都没预热过"）。 */
    public static GunLodPackIndex load(@Nullable Path file) {
        var index = new GunLodPackIndex(file);
        if (file == null) {
            return index;
        }
        // 回收上一次被强杀留下的临时文件（只删够老的，不会碰到正在写的）。
        AtomicFileWrites.sweepStaleTemps(file.getParent(), file.getFileName().toString());
        try {
            // 这份记录一个枪包一行、正常只有几百字节。先卡住上限，避免磁盘上出现一个同名巨文件时
            // 把它整个读进堆里；超限一律当作"什么都没预热过"。
            if (Files.size(file) > MAX_BYTES) {
                return index;
            }
            for (var line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                var trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                var separator = trimmed.indexOf('=');
                if (separator <= 0 || separator == trimmed.length() - 1) {
                    continue;
                }
                index.complete.put(trimmed.substring(0, separator), trimmed.substring(separator + 1));
            }
        } catch (IOException | RuntimeException ignored) {
            // 读不到就当空：最多重扫一遍。
        }
        return index;
    }

    /**
     * 该命名空间是否已经在这个指纹下整包预热过。
     *
     * <p>{@code fingerprint} 为 {@code null}（拿不到枪包内容身份）时恒为 false——
     * 无法证明未变就必须老老实实扫。
     */
    public synchronized boolean isComplete(String namespace, @Nullable String fingerprint) {
        if (namespace == null || fingerprint == null) {
            return false;
        }
        return fingerprint.equals(complete.get(namespace));
    }

    /** 记下"这个命名空间在指纹下已经整包预热过"。 */
    public synchronized void markComplete(String namespace, String fingerprint) {
        if (namespace == null || fingerprint == null) {
            return;
        }
        if (!fingerprint.equals(complete.put(namespace, fingerprint))) {
            save();
        }
    }

    /** 已记录的枪包数——供诊断使用。 */
    public synchronized int size() {
        return complete.size();
    }

    /**
     * 原子写回：唯一临时文件 + fsync + 原子改名。
     *
     * <p>被强杀最多丢掉这一次记录（下次启动重扫一遍），绝不会把文件写成半成品；旧内容在改名
     * 成功之前一直完好，所以"已经记录过的枪包"不会因为一次中断而丢失。
     */
    public synchronized void save() {
        if (file == null) {
            return;
        }
        var builder = new StringBuilder(complete.size() * 80);
        builder.append("# kinetic gun LOD prewarm index: namespace=fingerprint\n");
        for (var entry : complete.entrySet()) {
            builder.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        try {
            AtomicFileWrites.write(file, builder.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException ignored) {
            // 写不进去只是失去一次优化。
        }
    }
}
