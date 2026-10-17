package com.kinetic.client.compat.tacz.lod;

import com.kinetic.format.parser.GeoBuilder;
import com.kinetic.format.parser.pojo.model.GeoModel;
import com.kinetic.model.domain.Hash256;
import com.kinetic.model.resource.client.render.BakedModelCache;
import com.kinetic.model.storage.ModelHashing;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

/**
 * Stage 1 + 2 of the TaCZ gun LOD pipeline: turns a TaCZ gun id into a render-ready
 * {@link GunLodHandle} (decimated geometry + baked native model + texture + scale).
 *
 * <p><b>Classloader safety.</b> TaCZ is a compile-only dependency. Every reference to a
 * {@code com.tacz.*} type is confined to the body of {@link #build(ResourceLocation)} /
 * {@link #prewarm(ResourceLocation)} / {@link #prepare(ResourceLocation)}; no TaCZ type appears in a
 * signature, field or static initialiser of this class, so it can only be linked when TaCZ is
 * actually installed. Callers must gate the call behind {@code TACZCompat.isInstalled()}. The pure
 * decimation logic lives in the nested {@link Decimator} class, and the TaCZ-free assembly in
 * {@link GunLodFactory}, so both can be loaded (and unit tested) with TaCZ absent.
 *
 * <h2>两条入口，共用同一份准备工作</h2>
 *
 * <ul>
 *   <li>{@link #build} —— 渲染路径按需构建：先问磁盘缓存（命中则<b>不解析模型、不解码贴图</b>），
 *       未命中才走"解析 → 减面 → 原生烘焙 → 落盘"。</li>
 *   <li>{@link #prewarm} —— 后台预热：走同样的冷路径，但只落盘、<b>不读回原生模型</b>，
 *       因此预热 1000 把枪的常驻原生内存是零。见 {@link GunLodFactory#bakeOnly}。</li>
 * </ul>
 *
 * <p>两者的准备工作（贴图定位、读字节、算内容哈希、算缓存键）完全一致，抽在 {@link #prepare}。
 */
public final class TaczGunLodBuilder {
    /**
     * TaCZ 存放 Bedrock 模型文件的资源目录。
     *
     * <p>取自 TaCZ 自己的 {@code ClientAssetsManager} 字节码：它用
     * {@code new JsonDataManager<>(BedrockModelPOJO.class, GSON, "geo_models", ...)} 注册模型加载器，
     * 而 {@code JsonDataManager} 走的是 {@code FileToIdConverter}（即 {@code <dir>/<id>.json}）。
     * 因此 {@code display.model = eos:gun/xxx_geo} 对应
     * {@code assets/eos/geo_models/gun/xxx_geo.json}。
     *
     * <p>这个常量只用于<b>读模型内容算缓存键</b>，读不到时会退回序列化 POJO，不会影响正确性。
     */
    private static final String MODEL_DIRECTORY = "geo_models";

    private TaczGunLodBuilder() {
    }

    /**
     * Builds a decimated, baked LOD handle for the given gun id.
     *
     * <p>优先命中磁盘缓存；命中时只读一个缓存文件，不解析枪的 Bedrock JSON、不减面、不解码 PNG。
     *
     * @return the handle, or {@code null} if the gun/model/texture is unknown or anything at all
     *         goes wrong. This method never throws.
     */
    @Nullable
    public static GunLodHandle build(ResourceLocation gunId) {
        if (gunId == null) {
            return null;
        }
        try {
            var prepared = prepare(gunId);
            if (prepared == null) {
                return null;
            }
            var cache = BakedModelCache.shared();
            var cached = GunLodFactory.loadCached(cache, prepared.containerId(),
                    prepared.resourceName(), prepared.textureHash(),
                    prepared.texture(), prepared.scale());
            if (cached != null) {
                return cached;
            }
            var cold = decimate(prepared);
            if (cold == null) {
                return null;
            }
            return GunLodFactory.bakeAndStore(cache, prepared.containerId(),
                    prepared.resourceName(), prepared.textureHash(), cold.proto(),
                    prepared.textureBytes(), prepared.texture(), prepared.scale(),
                    cold.anchor());
        } catch (Throwable t) {
            // A broken or exotic gun model must never take the renderer down with it.
            return null;
        }
    }

    /**
     * 后台预热：把这把枪的烘焙产物写进磁盘缓存，<b>不读回原生模型、不产生常驻占用</b>。
     *
     * <p>已经预热好的枪会先被 {@link GunLodFactory#isWarm} 挡掉，不会重复解析模型。
     *
     * @return 这把枪现在是否已经预热好（含"本来就热"与"这次刚写好"）
     */
    public static boolean prewarm(ResourceLocation gunId) {
        if (gunId == null) {
            return false;
        }
        // 没有可写的磁盘缓存时预热毫无意义（结果只会在函数返回时被丢掉），
        // 所以要在解析模型之前就退出——否则会白做一遍减面，每把枪约 35 ms。
        var cache = BakedModelCache.shared();
        if (cache == null) {
            return false;
        }
        try {
            var prepared = prepare(gunId);
            if (prepared == null) {
                return false;
            }
            if (GunLodFactory.isWarm(cache, prepared.containerId(), prepared.resourceName(),
                    prepared.textureHash())) {
                return true;
            }
            var cold = decimate(prepared);
            if (cold == null) {
                return false;
            }
            return GunLodFactory.bakeOnly(cache, prepared.containerId(), prepared.resourceName(),
                    prepared.textureHash(), cold.proto(), prepared.textureBytes(), cold.anchor());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 减面后的中间产物：烘焙前的 proto 模型 + 摆放锚点。 */
    private record Cold(GeoBuilder.Result proto, Vector3f anchor) {
    }

    /**
     * 冷路径的公共部分：解析 Bedrock 模型 → 减面 → 建 proto → 取摆放锚点。
     *
     * <p>{@code handAnchor} 必须在减面<b>之后</b>取，与改动前的行为逐位一致。
     */
    private static @Nullable Cold decimate(Prepared prepared) throws java.io.IOException {
        var raw = parseModel(prepared.modelLocation());
        if (raw == null) {
            return null;
        }
        var anchor = GunLodDecimator.handAnchor(raw);
        GunLodDecimator.decimate(raw, lodDetailMetric(), skipAttachments());
        var proto = GeoBuilder.build(raw);
        if (proto == null) {
            return null;
        }
        return new Cold(proto, anchor);
    }

    /**
     * 一把枪在构建前能确定的全部输入。
     *
     * <p>{@code display} 用 {@link Object} 持有，避免 TaCZ 类型出现在字段签名上（类加载隔离）。
     */
    private record Prepared(Object display, ResourceLocation modelLocation, ResourceLocation texture,
                            byte[] textureBytes, byte[] textureHash, Hash256 containerId,
                            String resourceName, Vector3f scale) {
    }

    /**
     * 解析 TaCZ display、定位贴图、读贴图字节并算出缓存键。
     *
     * <p>这一步在冷热两条路径上都要做，所以刻意放在模型解析之前：磁盘缓存命中时，
     * 后面那些昂贵步骤（Bedrock JSON 解析、减面、PNG 解码、原生烘焙）一个都不会发生。
     *
     * @return 准备工作；枪不存在、没有模型、没有贴图、贴图文件缺失时返回 {@code null}
     */
    private static @Nullable Prepared prepare(ResourceLocation gunId) throws java.io.IOException {
        var display = com.tacz.guns.client.resource.ClientAssetsManager.INSTANCE.getGunDisplay(gunId);
        if (display == null) {
            return null;
        }
        var modelLocation = display.getModelLocation();
        if (modelLocation == null) {
            return null;
        }
        var texture = GunLodFactory.toTextureLocation(display.getModelTexture());
        if (texture == null) {
            return null;
        }
        // 枪包没有带这张贴图时不作 LOD，交回完整模型路径。
        var textureBytes = readResourceBytes(texture);
        if (textureBytes == null) {
            return null;
        }
        var modelHash = modelIdentity(modelLocation);
        if (modelHash == null) {
            return null;
        }
        // 贴图用<b>内容</b>哈希而不是路径：枪包换一张同名贴图必须让缓存失效。
        var textureHash = ModelHashing.blake3(textureBytes).bytes();
        return new Prepared(display, modelLocation, texture, textureBytes, textureHash,
                GunLodCacheKey.containerId(gunId, modelHash),
                GunLodCacheKey.resourceName(lodDetailMetric(), skipAttachments()),
                thirdPersonScale(display));
    }

    /**
     * 模型内容的身份哈希——磁盘缓存键的一部分。
     *
     * <p>首选直接读模型文件本身：TaCZ 把 {@code display.guns.*.model}（例如 {@code eos:gun/xxx_geo}）
     * 解析成 {@code assets/<ns>/geo_models/<path>.json}。读原始字节再哈希是这里最便宜也最可靠的做法
     * （约 0.5 ms），而且它天然覆盖"枪包就地更新模型"这个唯一无法从枪 id 或贴图看出来的变化。
     *
     * <p>兜底：若 TaCZ 换了目录布局导致文件读不到，退回"序列化已解析的 POJO 再哈希"。
     * 那条路要跑一遍 Gson 树构建（约 5~15 ms），慢但同样确定；两条路的哈希值不同，但同一次安装
     * 会稳定地走同一条。
     *
     * @return 模型内容哈希；模型既读不到也解析不出来时返回 {@code null}（此时不做磁盘缓存）
     */
    private static byte @Nullable [] modelIdentity(ResourceLocation modelLocation)
            throws java.io.IOException {
        var path = modelLocation.getPath();
        if (!path.endsWith(".json")) {
            path = path + ".json";
        }
        var raw = readResourceBytes(
                new ResourceLocation(modelLocation.getNamespace(), MODEL_DIRECTORY + "/" + path));
        if (raw != null) {
            return ModelHashing.blake3(raw).bytes();
        }
        try {
            var pojo = com.tacz.guns.client.resource.ClientAssetsManager.INSTANCE
                    .getBedrockModelPOJO(modelLocation);
            if (pojo == null) {
                return null;
            }
            var tree = com.tacz.guns.client.resource.ClientAssetsManager.GSON.toJsonTree(pojo);
            normalizeBedrock(tree);
            return ModelHashing.blake3(tree.toString().getBytes(StandardCharsets.UTF_8)).bytes();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 读一个资源的全部字节；资源不存在时返回 {@code null}。 */
    private static byte @Nullable [] readResourceBytes(ResourceLocation location)
            throws java.io.IOException {
        var resource = Minecraft.getInstance().getResourceManager().getResource(location)
                .orElse(null);
        if (resource == null) {
            return null;
        }
        try (var input = resource.open()) {
            return input.readAllBytes();
        }
    }

    /** 解析 Bedrock JSON 到 KINETIC 自己的 POJO。 */
    private static @Nullable GeoModel parseModel(ResourceLocation modelLocation) {
        var pojo = com.tacz.guns.client.resource.ClientAssetsManager.INSTANCE
                .getBedrockModelPOJO(modelLocation);
        if (pojo == null) {
            return null;
        }
        // TaCZ already keeps the parsed Bedrock POJO in memory. Round-tripping it back to
        // JSON through TaCZ's own Gson keeps this class free of TaCZ pojo types in its
        // signatures while still handing KINETIC's own parser a Bedrock model.
        var root = com.tacz.guns.client.resource.ClientAssetsManager.GSON.toJsonTree(pojo);
        normalizeBedrock(root);
        return new Gson().fromJson(root, GeoModel.class);
    }

    /**
     * 减面阈值来自客户端配置（{@code OtherPlayerGunItemLodExtent}），玩家可以在不重新构建模组的情况下
     * 权衡"保真度 vs 顶点数"；改完做一次资源重载（F3+T）就会丢弃并重建 LOD 缓存。
     *
     * <p>取不到配置（或值非法）时回退到 {@link GunLodDecimator#DEFAULT_MIN_BONE_METRIC}。
     */
    private static float lodDetailMetric() {
        try {
            return sanitize(
                    com.kinetic.config.ClientConfig.OTHER_GUN_ITEM_LOD_EXTENT.get(),
                    GunLodDecimator.DEFAULT_MIN_BONE_METRIC);
        } catch (Throwable ignored) {
            return GunLodDecimator.DEFAULT_MIN_BONE_METRIC;
        }
    }

    private static boolean skipAttachments() {
        try {
            return Boolean.TRUE.equals(
                    com.kinetic.config.ClientConfig.OTHER_GUN_ITEM_LOD_SKIP_ATTACHMENTS.get());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static float sanitize(Double configured, float fallback) {
        if (configured == null) {
            return fallback;
        }
        var number = configured.floatValue();
        return Float.isFinite(number) && number >= 0.0f ? number : fallback;
    }

    /**
     * TaCZ 在 display 里用 {@code transform.scale.thirdperson} 声明这把枪在第三人称下的缩放。
     * 取不到就按 1.0 处理（TaCZ 自己的兜底也是全 1）。
     */
    private static org.joml.Vector3f thirdPersonScale(Object display) {
        var fallback = new org.joml.Vector3f(1.0f, 1.0f, 1.0f);
        try {
            var gunDisplay = (com.tacz.guns.client.resource.pojo.display.gun.GunDisplay) display;
            var transform = gunDisplay.getTransform();
            if (transform == null) {
                return fallback;
            }
            var scale = transform.getScale();
            if (scale == null) {
                return fallback;
            }
            var thirdPerson = scale.getThirdPerson();
            if (thirdPerson == null) {
                return fallback;
            }
            // TaCZ 用的是 scale(v.x, v.y, v.z)：逐轴、可以不等比。这里必须逐轴保存，
            // 否则不等比的枪包会被我们压成等比，也就是"变形"。
            if (!isUsableAxis(thirdPerson.x) || !isUsableAxis(thirdPerson.y)
                    || !isUsableAxis(thirdPerson.z)) {
                return fallback;
            }
            return new org.joml.Vector3f(thirdPerson.x, thirdPerson.y, thirdPerson.z);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static boolean isUsableAxis(float value) {
        return value > 0.0f && Float.isFinite(value);
    }

    /**
     * TaCZ's {@code CubesItem} registers a Gson deserializer but no serializer, so a per-face
     * {@code uv} object comes back out of {@code ClientAssetsManager.GSON} under TaCZ's internal
     * field name {@code faceUv}. Rename it to {@code uv} so KINETIC's parser sees ordinary Bedrock.
     * Legacy models store their geometry under {@code geometry.model} instead of
     * {@code minecraft:geometry}; accept both spellings.
     */
    private static void normalizeBedrock(JsonElement root) {
        if (root == null || !root.isJsonObject()) {
            return;
        }
        var object = root.getAsJsonObject();
        if (!object.has("minecraft:geometry") && object.has("geometry.model")) {
            object.add("minecraft:geometry", object.get("geometry.model"));
        }
        var geometry = object.get("minecraft:geometry");
        if (geometry == null || !geometry.isJsonArray()) {
            return;
        }
        for (var geometryElement : geometry.getAsJsonArray()) {
            if (!geometryElement.isJsonObject()) {
                continue;
            }
            var bones = geometryElement.getAsJsonObject().get("bones");
            if (bones == null || !bones.isJsonArray()) {
                continue;
            }
            for (var boneElement : bones.getAsJsonArray()) {
                if (!boneElement.isJsonObject()) {
                    continue;
                }
                var cubes = boneElement.getAsJsonObject().get("cubes");
                if (cubes == null || !cubes.isJsonArray()) {
                    continue;
                }
                for (var cubeElement : cubes.getAsJsonArray()) {
                    if (!cubeElement.isJsonObject()) {
                        continue;
                    }
                    var cube = cubeElement.getAsJsonObject();
                    var faceUv = cube.get("faceUv");
                    if (faceUv != null && !cube.has("uv")) {
                        cube.add("uv", faceUv);
                        cube.remove("faceUv");
                    }
                }
            }
        }
    }

    /**
     * Pure, TaCZ-free decimation of a parsed Bedrock model. Kept in its own class file so tests
     * and any future non-TaCZ caller can load it without linking against TaCZ.
     */
    /**
     * 兼容入口。真正的减面逻辑只有一份，在 {@link GunLodDecimator}：它不引用任何 TaCZ 类型，
     * 可以离线单测，并由 {@code GunLodDecimatorTest} 与 {@code TaczGunLodPipelineTest} 覆盖。
     */
    public static final class Decimator {
        /** 见 {@link GunLodDecimator#DEFAULT_MIN_BONE_METRIC}。 */
        public static final float MIN_BONE_METRIC = GunLodDecimator.DEFAULT_MIN_BONE_METRIC;

        private Decimator() {
        }

        /** 就地减面并返回同一个模型对象。 */
        public static GeoModel decimate(GeoModel raw, float minBoneMetric) {
            GunLodDecimator.decimate(raw, minBoneMetric);
            return raw;
        }
    }
}
