package com.kinetic.config;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlParser;
import com.kinetic.Kinetic;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 动画优先级：默认值来自随模组发布的 {@code serverconfig/kinetic_animation_priority.toml}，
 * 运行期生效值来自 {@link ServerConfig#ANIMATION_PRIORITIES}（Forge SERVER 配置，
 * 会由 Forge 的配置同步机制下发到客户端）。
 *
 * <p>键支持逻辑别名（{@code attack}/{@code parcool_slide}/{@code tacz_crawl} …）与精确动画 ID；
 * 以 {@code :} 结尾的键表示前缀匹配。数值越大越优先。
 */
public final class AnimationPriorityConfig {
    /** 随模组发布的默认优先级表路径（相对模组文件根）。 */
    private static final String DEFAULT_RESOURCE_DIRECTORY = "serverconfig";
    private static final String DEFAULT_RESOURCE_NAME = "kinetic_animation_priority.toml";

    /**
     * 逻辑别名到实际动画标识的映射。
     *
     * <p>实际标识都是从源码/动画文件里核对过的：受击是 {@code attacked}（AnimationRegister），
     * Parcool 滑铲是 {@code parcool:sliding}（parcool.animation.json + SlidingAnimator），
     * TACZ 爬行是 {@code tac:climb:}/{@code tac:climbing:}（TacCompatInner 的 pose==SWIMMING 分支）。
     */
    private static final Map<String, String[]> ALIASES = Map.of(
            "attack", new String[]{"attacked"},
            "walk", new String[]{"walk"},
            "idle", new String[]{"idle"},
            "sneak", new String[]{"sneak", "sneaking"},
            "parcool_slide", new String[]{"parcool:sliding"},
            "tacz_crawl", new String[]{"tac:climb:", "tac:climbing:"});

    /**
     * 未配置时 {@code parcool:sliding} 与 {@code tac:climb:} 的内置优先级，
     * 与随模组发布的 {@code kinetic_animation_priority.toml} 默认值一致。
     */
    public static final int DEFAULT_SLIDE_PRIORITY = 1200;
    public static final int DEFAULT_CRAWL_PRIORITY = 700;

    private static volatile Lookup lookup = Lookup.EMPTY;
    private static volatile List<String> defaultEntries = List.of();
    private static volatile boolean refreshed;

    /**
     * 一次刷新产出的完整查询表；整体替换，保证前缀表与覆盖表来自同一次刷新。
     */
    private record Lookup(Map<String, Integer> overrides, List<Map.Entry<String, Integer>> prefixEntries) {
        private static final Lookup EMPTY = new Lookup(Map.of(), List.of());
    }

    private AnimationPriorityConfig() {
    }

    /**
     * 从当前生效的 {@link ServerConfig#ANIMATION_PRIORITIES} 重建查询表。
     *
     * <p>配置尚未加载时静默保持默认值，下一次查询会重试。
     */
    public static synchronized void refreshFromServerConfig() {
        try {
            var value = ServerConfig.ANIMATION_PRIORITIES;
            if (value == null) {
                return;
            }
            refresh(value.get());
            refreshed = true;
        } catch (RuntimeException notLoadedYet) {
            // 配置尚未加载（或已被卸载）：保持上一次的表，下次查询再试。
        }
    }

    private static void ensureLoaded() {
        if (!refreshed) {
            refreshFromServerConfig();
        }
    }

    /**
     * 读取随模组发布的默认优先级表，产出 {@code "key=value"} 列表。
     *
     * <p>解析失败时返回内置兜底值，保证配置初始化不会因资源问题中断。
     */
    public static synchronized List<String> defaultEntries() {
        if (!defaultEntries.isEmpty()) {
            return defaultEntries;
        }
        var parsed = readShippedDefaults();
        defaultEntries = parsed.isEmpty() ? fallbackEntries() : parsed;
        return defaultEntries;
    }

    /** 用运行期生效的配置值重建查询表。在服务端配置加载/重载后调用。 */
    public static synchronized void refresh(List<? extends String> entries) {
        var result = new LinkedHashMap<String, Integer>();
        if (entries != null) {
            for (var entry : entries) {
                var parsed = parseEntry(entry);
                if (parsed != null) {
                    result.put(parsed.getKey().toLowerCase(Locale.ROOT), parsed.getValue());
                }
            }
        }
        var table = Map.copyOf(result);
        var prefixes = new ArrayList<Map.Entry<String, Integer>>();
        for (var entry : table.entrySet()) {
            if (entry.getKey().endsWith(":")) {
                prefixes.add(entry);
            }
        }
        lookup = new Lookup(table, List.copyOf(prefixes));
        Kinetic.LOGGER.info("Animation priority overrides loaded: {}", table.size());
    }

    /**
     * 跑酷滑铲是否优先于 TACZ 爬行。
     *
     * <p>Minecraft 的 {@code Pose.SWIMMING} 同时被游泳、爬行和跑酷滑铲复用。持枪与开火两条
     * 频道都在这个姿态上做分叉，只有本条成立时才能把姿态让给跑酷滑铲，否则会播成 TACZ
     * 爬行——玩家会摆出趴地姿势、下半身陷进地面。判断集中在优先级表里，两个频道共用同一
     * 结论，避免一边改了一边没改。
     */
    public static boolean slideOutranksCrawl() {
        return priorityFor("parcool:sliding", DEFAULT_SLIDE_PRIORITY)
                > priorityFor("tac:climb:", DEFAULT_CRAWL_PRIORITY);
    }

    /**
     * 某个动画标识的优先级。
     *
     * <p>未配置时返回 {@code fallback}（调用方传入的内置优先级），因此“不配置”与改动前行为完全一致。
     */
    public static int priorityFor(String animationName, int fallback) {
        if (animationName == null) {
            ensureLoaded();
            return fallback;
        }
        return priorityForLowercase(animationName.toLowerCase(Locale.ROOT), fallback);
    }

    /**
     * 与 {@link #priorityFor(String, int)} 等价，但要求调用方传入已经小写的动画标识，
     * 避免热路径上重复 {@code toLowerCase}。
     */
    public static int priorityForLowercase(String lowercaseName, int fallback) {
        ensureLoaded();
        var current = lookup;
        var table = current.overrides();
        if (lowercaseName == null || table.isEmpty()) {
            return fallback;
        }
        var direct = table.get(lowercaseName);
        if (direct != null) {
            return direct;
        }
        // 前缀键（以 ':' 结尾）匹配带参数的动画 ID，例如 "tac:climb:" 匹配 "tac:climb:rifle"
        for (var entry : current.prefixEntries()) {
            if (lowercaseName.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        // 逻辑别名
        for (var alias : ALIASES.entrySet()) {
            var configured = table.get(alias.getKey());
            if (configured == null) {
                continue;
            }
            for (var target : alias.getValue()) {
                if (target.endsWith(":")) {
                    if (lowercaseName.startsWith(target)) {
                        return configured;
                    }
                } else if (target.equalsIgnoreCase(lowercaseName)) {
                    return configured;
                }
            }
        }
        return fallback;
    }

    private static Map.Entry<String, Integer> parseEntry(String raw) {
        if (raw == null) {
            return null;
        }
        var text = raw.trim();
        if (text.isEmpty() || text.startsWith("#")) {
            return null;
        }
        var separator = text.lastIndexOf('=');
        if (separator <= 0 || separator == text.length() - 1) {
            Kinetic.LOGGER.warn("Ignoring malformed animation priority entry: {}", raw);
            return null;
        }
        var key = text.substring(0, separator).trim().replace("\"", "");
        var value = text.substring(separator + 1).trim();
        try {
            return Map.entry(key, Integer.parseInt(value));
        } catch (NumberFormatException invalid) {
            Kinetic.LOGGER.warn("Ignoring non-numeric animation priority: {}", raw);
            return null;
        }
    }

    private static List<String> readShippedDefaults() {
        final Path resource;
        try {
            var modFile = ModList.get() == null ? null
                    : ModList.get().getModFileById(Kinetic.MOD_ID);
            if (modFile == null) {
                return List.of();
            }
            resource = modFile.getFile()
                    .findResource(DEFAULT_RESOURCE_DIRECTORY, DEFAULT_RESOURCE_NAME);
        } catch (RuntimeException unavailable) {
            Kinetic.LOGGER.warn("Shipped animation priority table is unavailable", unavailable);
            return List.of();
        }
        if (resource == null || !Files.isRegularFile(resource)) {
            return List.of();
        }
        try (InputStream input = Files.newInputStream(resource);
             var reader = new BufferedReader(new java.io.InputStreamReader(
                     input, StandardCharsets.UTF_8))) {
            return flatten(reader);
        } catch (IOException | RuntimeException failure) {
            Kinetic.LOGGER.warn("Failed to read the shipped animation priority table", failure);
            return List.of();
        }
    }

    /**
     * 解析 {@code [priorities]} 段，产出 {@code "key=value"} 列表。
     *
     * <p>包可见以便单测直接用随模组发布的 TOML 验证解析结果。
     */
    @SuppressWarnings("unchecked")
    static List<String> flatten(BufferedReader reader) {
        Config config = new TomlParser().parse(reader);
        var section = config.get("priorities");
        if (!(section instanceof Config priorities)) {
            return List.of();
        }
        var result = new ArrayList<String>();
        for (var entry : priorities.entrySet()) {
            var value = entry.getValue();
            if (value instanceof Number number) {
                result.add(entry.getKey() + "=" + number.intValue());
            } else if (value instanceof String text) {
                result.add(entry.getKey() + "=" + text.trim());
            }
        }
        return List.copyOf(result);
    }

    private static List<String> fallbackEntries() {
        // 与内置档位权重一一对应，保证“未读到资源文件”时行为与改动前一致。
        return List.of(
                "attack=600",
                "walk=400",
                "idle=200",
                "sneak=600",
                "parcool_slide=1200",
                "tacz_crawl=700");
    }

    /** 当前生效的覆盖表（只读），用于诊断与自查。 */
    public static Map<String, Integer> currentOverrides() {
        return lookup.overrides();
    }

    @Nullable
    static String describe() {
        return Objects.toString(lookup.overrides(), null);
    }
}
