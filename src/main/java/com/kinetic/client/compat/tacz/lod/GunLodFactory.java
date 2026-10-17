package com.kinetic.client.compat.tacz.lod;

import com.kinetic.client.model.locator.PlayerLocator;
import com.kinetic.format.parser.GeoBuilder;
import com.kinetic.geckolib3.model.AnimatedGeoModel;
import com.kinetic.model.domain.Hash256;
import com.kinetic.model.resource.client.render.BakedModelCache;
import com.kinetic.util.AtomicFileWrites;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

/**
 * 把 {@link TaczGunLodBuilder} 产出的 proto 模型 + TaCZ display 里声明的贴图/缩放，
 * 组装成一个可直接渲染的 {@link GunLodHandle}。
 *
 * <p>只负责"烘焙与缓存"这一件事，且<b>不引用任何 TaCZ 类型</b>，因此可以离线单测。
 * 整个过程跑在 worker 线程上（与 KINETIC 自己的模型加载同一条路径，原生烘焙本身没有线程亲和性）。
 *
 * <h2>两级缓存</h2>
 *
 * <ul>
 *   <li><b>磁盘</b>（{@link BakedModelCache}）：跨进程存活。命中时只读一个约 200~300 KB 的
 *       文件并把 flatbuffer 还原成原生模型，<b>不需要</b>解析枪的 Bedrock JSON、不需要减面、
 *       不需要解码 PNG。这是"1000 把枪只需要冷烘焙一次"的落点。</li>
 *   <li><b>常驻</b>（{@link GunLodRegistry}，16 条上限）：跨帧存活，只保留最近用到的句柄。</li>
 * </ul>
 *
 * <h2>摆放锚点为什么要单独存</h2>
 *
 * <p>{@link GunLodHandle} 除了烘焙模型还需要一个"把 {@code thirdperson_hand} 搬到手上"的平移量，
 * 它来自枪的 Bedrock 模型。若只缓存烘焙产物，命中时仍然要解析一遍模型才能拿到这个平移量——
 * 那样磁盘缓存就白做了一半。所以把它作为<b>附属文件</b>写在缓存文件旁边（同名 + {@code .anchor}），
 * 两者同生共死：附属文件缺失或损坏一律当作未命中，回落到冷路径，绝不会用错位姿渲染。
 */
public final class GunLodFactory {
    /** 与 {@code ModelRenderTargetLoader} / {@code BuiltinModelMaterializer} 保持一致的原始 UV 版本。 */
    public static final int RAW_UV_VERSION = 29;

    /** 摆放锚点附属文件的后缀与长度（三个大端 float）。 */
    static final String ANCHOR_SUFFIX = ".anchor";
    static final int ANCHOR_BYTES = Float.BYTES * 3;

    private GunLodFactory() {
    }

    /**
     * TaCZ 的 display 里 {@code model_texture} 是"去掉 {@code textures/} 前缀与 {@code .png} 后缀"的
     * 资源路径（例如 {@code tacz:gun/uv/ak47}），原版贴图管理器的键则是完整路径。这里做一次转换，
     * 并且对已经写全的路径保持幂等，避免个别枪包写法不同时找不到贴图。
     */
    public static ResourceLocation toTextureLocation(@Nullable ResourceLocation declared) {
        if (declared == null) {
            return null;
        }
        var path = declared.getPath();
        if (!path.startsWith("textures/")) {
            path = "textures/" + path;
        }
        if (!path.endsWith(".png")) {
            path = path + ".png";
        }
        return new ResourceLocation(declared.getNamespace(), path);
    }

    /**
     * 从磁盘缓存直接组装句柄。命中时<b>不解析模型、不解码贴图</b>。
     *
     * @return 句柄；未命中（缓存缺失、附属文件缺失、任何 IO/解析失败）返回 {@code null}，
     *         调用方必须回落到 {@link #bakeAndStore}
     */
    @Nullable
    public static GunLodHandle loadCached(@Nullable BakedModelCache cache, Hash256 containerId,
                                          String resourceName, byte[] textureHash,
                                          ResourceLocation texture, Vector3f scale) {
        if (cache == null) {
            return null;
        }
        try {
            var anchor = readAnchor(cache.resolveCachePath(containerId, resourceName, textureHash));
            if (anchor == null) {
                return null;
            }
            var baked = cache.loadExisting(containerId, resourceName, textureHash,
                    PlayerLocator.get());
            try {
                var handle = new GunLodHandle(new AnimatedGeoModel(baked), texture, scale, anchor);
                baked = null;
                return handle;
            } finally {
                closeQuietly(baked);
            }
        } catch (Throwable failure) {
            // 缓存损坏等价于未命中：回落冷路径，绝不让渲染拿到半成品。
            return null;
        }
    }

    /**
     * 烘焙 + 落盘 + 组装句柄（冷路径）。
     *
     * <p>任何一步失败都返回 {@code null}，由调用方回退到不做 LOD。
     */
    @Nullable
    public static GunLodHandle bakeAndStore(@Nullable BakedModelCache cache, Hash256 containerId,
                                            String resourceName, byte[] textureHash,
                                            @Nullable GeoBuilder.Result raw, byte[] textureBytes,
                                            ResourceLocation texture, Vector3f scale,
                                            Vector3f anchor) {
        var source = sourceOf(raw);
        if (source == null) {
            return null;
        }
        // 烘焙模型一拿到就必须有归宿：组装句柄失败时在这里关掉它，否则每把失败一次就漏一份
        // 原生烘焙模型（约 200~300 KB，要等 GC 触发 Cleaner 才回收）。成功时置 null 跳过。
        com.kinetic.geckolib3.geo.render.built.GeoModel baked = null;
        // 贴图也必须关：NativeImage 没有 Cleaner/finalizer，只有 close() 会释放像素内存，
        // 而 NativeBakedModel.bake 只读不关。1000 把枪各漏一张 1024²~2048² 的贴图就是几个 GB。
        // 用惰性供应商是为了命中磁盘缓存时根本不解码；但"解出来的那张"必须有人负责关。
        try (var lazyTexture = new LazyTexture(textureBytes)) {
            if (cache == null) {
                // 缓存未安装（理论上只在极端初始化失败时）：退回不落盘的行为。
                baked = BakedModelCache.bakeResident(source, "tacz-gun-lod/" + texture,
                        lazyTexture, RAW_UV_VERSION, false, false, false,
                        PlayerLocator.get());
            } else {
                baked = cache.loadOrBake(containerId, resourceName, textureHash, source,
                        lazyTexture, RAW_UV_VERSION, false, false, false,
                        PlayerLocator.get());
                writeAnchor(cache.resolveCachePath(containerId, resourceName, textureHash), anchor);
            }
            var handle = new GunLodHandle(new AnimatedGeoModel(baked), texture, scale, anchor);
            baked = null;
            return handle;
        } catch (Throwable failure) {
            return null;
        } finally {
            closeQuietly(baked);
        }
    }

    /**
     * 惰性解码的贴图像素，并且<b>保证被关闭</b>。
     *
     * <p>{@code BakedModelCache} 的供应商契约是"返回借用的像素，由创建者持有所有权"：它自己
     * 从不关闭拿到的 {@code NativeImage}。而这个类没有 Cleaner 也没有 finalizer，只有
     * {@code close()} 会释放像素内存——所以"谁 decode 谁 close"。
     *
     * <p>之所以仍然是惰性的：命中磁盘缓存时 {@code materialize} 会在调用写入方之前就返回，
     * 供应商根本不会被求值，于是这一次冷启动连 PNG 都不用解码。
     */
    static final class LazyTexture
            implements BakedModelCache.TexturePixelsSupplier, AutoCloseable {
        private final byte[] bytes;
        private NativeImage image;

        LazyTexture(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public NativeImage get() throws IOException {
            if (image == null) {
                image = decode(bytes);
            }
            return image;
        }

        @Override
        public void close() {
            var created = image;
            image = null;
            if (created == null) {
                return;
            }
            try {
                created.close();
            } catch (Throwable ignored) {
                // 释放失败无关紧要。
            }
        }
    }

    /** 静默关掉一个烘焙模型；{@code null} 时什么也不做。 */
    private static void closeQuietly(
            com.kinetic.geckolib3.geo.render.built.GeoModel model) {
        if (model == null) {
            return;
        }
        try {
            model.close();
        } catch (Throwable ignored) {
            // 释放失败无关紧要。
        }
    }

    /**
     * 只烘焙 + 落盘，<b>不读回原生模型、不组装句柄</b>——后台预热专用。
     *
     * <p>预热的结果通常几十分钟内都不会被渲染到，读回等于白白占住原生堆
     * （每把枪约 200~300 KB，1000 把就是 250 MB）。这里只保证磁盘上有合法缓存。
     *
     * <p><b>返回值必须是精确的。</b>预热器只在某个枪包<b>每一把</b>都返回 {@code true} 时才把它记进
     * 索引（= 下次整包跳过），所以这里不能"写完就当成功"：锚点写失败时那把枪其实还没热，
     * 一旦被记成热的，这个包以后都不会再扫它了。因此这里写完再读回来确认一次——
     * 多花一次 12 字节的读，换掉"某把枪永远不再被预热"这种永久性退化。
     *
     * @return 这把枪现在是否真的已经预热好（缓存文件与锚点都在）
     */
    public static boolean bakeOnly(@Nullable BakedModelCache cache, Hash256 containerId,
                                   String resourceName, byte[] textureHash,
                                   @Nullable GeoBuilder.Result raw, byte[] textureBytes,
                                   Vector3f anchor) {
        if (cache == null) {
            return false;
        }
        var source = sourceOf(raw);
        if (source == null) {
            return false;
        }
        try (var lazyTexture = new LazyTexture(textureBytes)) {
            cache.bakeOnly(containerId, resourceName, textureHash, source,
                    lazyTexture, RAW_UV_VERSION, false, false, false);
            var path = cache.resolveCachePath(containerId, resourceName, textureHash);
            writeAnchor(path, anchor);
            // 写失败只是"这次没预热到"，绝不能被记成"已经预热过"。
            return readAnchor(path) != null;
        } catch (Throwable failure) {
            return false;
        }
    }

    /** 该键是否已经预热好（缓存文件与锚点都在）。供预热器跳过已完成项。 */
    public static boolean isWarm(@Nullable BakedModelCache cache, Hash256 containerId,
                                 String resourceName, byte[] textureHash) {
        if (cache == null) {
            return false;
        }
        try {
            if (!cache.isCached(containerId, resourceName, textureHash)) {
                return false;
            }
            return readAnchor(cache.resolveCachePath(containerId, resourceName, textureHash)) != null;
        } catch (Throwable failure) {
            return false;
        }
    }

    private static com.kinetic.proto.mixel.asset.model.data.GeoModel sourceOf(
            @Nullable GeoBuilder.Result raw) {
        if (raw == null) {
            return null;
        }
        var source = raw.model();
        if (source == null || source.bones().size() == 0) {
            return null;
        }
        return source;
    }

    private static NativeImage decode(byte[] bytes) throws IOException {
        try (var input = new ByteArrayInputStream(bytes)) {
            return NativeImage.read(input);
        }
    }

    /**
     * 读附属文件；不存在、长度不对、内容非有限数都返回 {@code null}（= 未命中）。
     *
     * <p>先看文件长度再读：这个文件永远只有 12 字节，如果磁盘上有个同名的大文件（用户误放、
     * 磁盘出错），{@code readAllBytes} 会把它整个读进堆里。长度不符直接判未命中，既省事也安全。
     */
    static @Nullable Vector3f readAnchor(Path path) {
        var sidecar = path.resolveSibling(path.getFileName() + ANCHOR_SUFFIX);
        try {
            if (Files.size(sidecar) != ANCHOR_BYTES) {
                return null;
            }
            var bytes = Files.readAllBytes(sidecar);
            if (bytes.length != ANCHOR_BYTES) {
                return null;
            }
            var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
            var x = buffer.getFloat();
            var y = buffer.getFloat();
            var z = buffer.getFloat();
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                return null;
            }
            return new Vector3f(x, y, z);
        } catch (IOException missing) {
            return null;
        }
    }

    /**
     * 写附属文件：唯一临时文件 + fsync + 原子改名。
     *
     * <p>被强杀最多丢掉这一次写入（下次仍然走冷路径重建），绝不会留下一个"长度对但内容是半截"
     * 的锚点——写入期间目标文件一直是旧的完整内容或不存在，而读取端还会再校验长度与有限性。
     *
     * <p>顺带回收同目录下过期的临时文件；只在已经要写这个目录时才做，不额外扫全树。
     */
    static void writeAnchor(Path path, Vector3f anchor) {
        var sidecar = path.resolveSibling(path.getFileName() + ANCHOR_SUFFIX);
        var bytes = ByteBuffer.allocate(ANCHOR_BYTES).order(ByteOrder.BIG_ENDIAN)
                .putFloat(anchor.x).putFloat(anchor.y).putFloat(anchor.z).array();
        AtomicFileWrites.sweepStaleTemps(sidecar.getParent(), sidecar.getFileName().toString());
        try {
            AtomicFileWrites.write(sidecar, bytes);
        } catch (Throwable ignored) {
            // 附属文件写不进去只是失去一次优化。
        }
    }
}
