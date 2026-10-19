package com.kinetic.client.compat.tacz.lod;

import com.kinetic.model.storage.ModelHashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;

/**
 * 枪包内容的指纹：用来判断"这个枪包自上次预热以来有没有变过"。
 *
 * <h2>为什么不能用枪包自己的元数据</h2>
 *
 * <p>{@code gunpack.meta.json} 里的 {@code namespace}/{@code name}/{@code dependencies} 在枪包更新后
 * 通常原样保留——作者改的是模型和贴图。用元数据当身份，更新枪包后会被判成"没变"，于是新模型
 * 永远等不到预热。所以指纹必须来自<b>文件本身</b>。
 *
 * <h2>指纹怎么取</h2>
 *
 * <ul>
 *   <li><b>压缩包</b>（{@code .zip} 枪包）：直接对整个文件做 blake3。内容变了字节就变了，最准确。</li>
 *   <li><b>目录枪包</b>：对全部文件的 {@code (相对路径, 大小, 修改时间)} 排序后做一次 blake3。
 *       不读文件内容，只做 stat；一个 1000 文件、33 MB 的枪包约几毫秒。任何一次写入都会改变
 *       大小或修改时间，因此能发现就地更新。</li>
 * </ul>
 *
 * <h2>判错的后果是有界的</h2>
 *
 * <p>这个指纹<b>只决定要不要预热</b>，不决定渲染什么。按需路径（{@code TaczGunLodBuilder.build}）
 * 永远重新算枪 id + 模型内容 + 贴图内容的缓存键，所以即使指纹误判为"没变"而跳过了预热，
 * 渲染出来的模型依然正确，代价只是那一把枪晚一点才被烘焙（退回改动前的按需异步行为）。
 * 指纹误判为"变了"则只是白扫一遍。两种情况都不会让画面出错。
 */
public final class GunLodPackFingerprint {
    /**
     * 目录枪包参与指纹的最大文件数。
     *
     * <p>超过就返回 {@code null}（= 无法证明未变），调用方回落到逐枪扫描。避免个别巨型枪包
     * 让启动期的后台线程做几万次 stat。
     */
    static final int MAX_ENTRIES = 20_000;

    private GunLodPackFingerprint() {
    }

    /**
     * @param root 枪包根路径（目录或 zip 文件）
     * @return 指纹；{@code root} 为 null、不可读、或条目过多时返回 {@code null}
     */
    public static @Nullable String of(@Nullable Path root) {
        if (root == null) {
            return null;
        }
        try {
            if (Files.isRegularFile(root)) {
                return "zip:" + ModelHashing.blake3(root);
            }
            if (!Files.isDirectory(root)) {
                return null;
            }
            var entries = new ArrayList<String>();
            try (Stream<Path> stream = Files.walk(root)) {
                var iterator = stream.iterator();
                while (iterator.hasNext()) {
                    var path = iterator.next();
                    if (!Files.isRegularFile(path)) {
                        continue;
                    }
                    if (entries.size() >= MAX_ENTRIES) {
                        return null;
                    }
                    entries.add(root.relativize(path).toString().replace('\\', '/')
                            + '|' + Files.size(path)
                            + '|' + Files.getLastModifiedTime(path).toMillis());
                }
            }
            if (entries.isEmpty()) {
                return null;
            }
            Collections.sort(entries);
            var builder = new StringBuilder(entries.size() * 48);
            for (var entry : entries) {
                builder.append(entry).append('\n');
            }
            return "dir:" + ModelHashing.blake3(builder.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException failure) {
            // 读不到就当作"无法证明未变"：调用方会老老实实扫一遍。
            return null;
        }
    }
}
