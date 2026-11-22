package com.kinetic.model.resource.client.render;

import com.kinetic.client.animation.molang.CustomMolangParser;
import com.kinetic.client.model.locator.PlayerLocator;
import com.kinetic.format.AssetLoadException;
import com.kinetic.format.schema.file.AssetFileConstant;
import com.kinetic.format.schema.file.ChunkDataSource;
import com.kinetic.format.schema.file.PBRImageSources;
import com.kinetic.format.schema.model.ModelFileView;
import com.kinetic.format.schema.model.ModelManifestLookup;
import com.kinetic.format.schema.model.views.RenderTargetView;
import com.kinetic.geckolib3.core.molang.value.IValue;
import com.kinetic.geckolib3.file.AnimationControllerFile;
import com.kinetic.geckolib3.geo.render.built.GeoModel;
import com.kinetic.model.catalog.content.DefaultAnimationKey;
import com.kinetic.model.catalog.content.ModelContent;
import com.kinetic.model.domain.Hash256;
import com.kinetic.model.domain.ModelRepresentation;
import com.kinetic.model.resource.client.AnimationStore;
import com.kinetic.model.resource.client.BakeProfile;
import com.kinetic.model.resource.client.ModelRenderTarget;
import com.kinetic.model.resource.client.ModelResourceFailures;
import com.kinetic.model.resource.client.PlayerModelVariant;
import com.kinetic.model.resource.client.ResourceFailure;
import com.kinetic.model.resource.client.SoundSource;
import com.kinetic.model.resource.client.data.CommonAssetData;
import com.kinetic.model.resource.client.data.ModelRenderTargetBuildInput;
import com.kinetic.model.resource.client.data.PlayerModelData;
import com.kinetic.model.resource.client.data.ProjectileModelData;
import com.kinetic.model.resource.client.data.RenderTargetData;
import com.kinetic.model.resource.client.data.VehicleModelData;
import com.kinetic.proto.mixel.asset.model.ModelData;
import com.kinetic.proto.mixel.asset.model.data.AnimationFile;
import com.kinetic.proto.mixel.asset.strings.StringData;
import com.kinetic.proto.mixel.common.Image;
import com.kinetic.proto.mixel.manifest.asset.PBRTextureSet;
import com.kinetic.proto.mixel.manifest.asset.RenderTarget;
import com.kinetic.proto.mixel.manifest.asset.RenderTargetKind;
import com.kinetic.util.FifoHashMap;
import com.kinetic.util.ResourceTransaction;
import it.unimi.dsi.fastutil.objects.Object2ReferenceOpenHashMap;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import us.hebi.quickbuf.ProtoSource;

/** Builds the current Java model render target from a model container view. */
public final class ModelRenderTargetLoader {
    private static final int CURRENT_RAW_UV_VERSION = 29;

    private final BuildStage buildStage;
    private final BakeProfile bakeProfile;

    public ModelRenderTargetLoader(BakedModelCache bakedModels, BakedAnimationCache bakedAnimations,
                              Executor workers, DefaultAnimationRuntime defaultAnimations) {
        this.buildStage = new DefaultBuildStage(
                bakedModels, bakedAnimations, workers, defaultAnimations);
        this.bakeProfile = new BakeProfile(bakedModels.profileKey() + "/"
                + BakedAnimationCache.profileKey() + "/raw-uv-" + CURRENT_RAW_UV_VERSION);
    }

    public BakeProfile bakeProfile() {
        return bakeProfile;
    }

    public LoadResult load(BooleanSupplier cancelled, ModelContent content,
                    RenderTargetKey key, boolean defaultModel,
                    ModelResourceFailures resourceFailures) {
        return load(cancelled, content, key, defaultModel, false, resourceFailures);
    }

    public LoadResult load(BooleanSupplier cancelled, ModelContent content,
                    RenderTargetKey key, boolean defaultModel, boolean cacheOnly,
                    ModelResourceFailures resourceFailures) {
        var representation = content.representation();
        var chunks = content.chunks();
        try {
            requireActive(cancelled);
            var view = representation.view();
            final RenderTargetView target;
            final String selectedTexture;
            try {
                target = view.requireRenderTarget(key.targetId());
                selectedTexture = ModelManifestLookup.chooseTexture(
                        view.getManifest(), key.targetId(), key.selectedTexture());
            } catch (IllegalArgumentException failure) {
                throw AssetLoadException.content(
                        "Invalid model render target selection", failure);
            }
            var request = new LoadRequest(representation, view, target,
                    target.textureDescriptor(selectedTexture),
                    target.textureSources(cancelled, chunks, selectedTexture), key.targetId(), selectedTexture,
                    defaultModel, cacheOnly, resourceFailures);
            requireActive(cancelled);
            var definition = request.target().readDefinition(cancelled, chunks);
            var commonStrings = readCommonStrings(cancelled, request.view(), chunks);
            return new LoadResult.Ready(buildStage.build(
                    cancelled, new LoadedTarget(request, definition, commonStrings)));
        } catch (AssetLoadException | CancellationException failure) {
            return failure(failure);
        } catch (IOException failure) {
            return failure(AssetLoadException.content(
                    "Failed to load model render target", failure));
        } catch (IllegalArgumentException failure) {
            return failure(AssetLoadException.content(
                    "Failed to build model render target", failure));
        }
    }

    public static LoadResult.Failed failure(Throwable cause) {
        final ResourceFailure.Kind kind;
        if (cause instanceof CancellationException) {
            kind = ResourceFailure.Kind.TRANSIENT;
        } else if (cause instanceof AssetLoadException asset) {
            kind = asset.reason() == AssetLoadException.Reason.CONTENT
                    ? ResourceFailure.Kind.DETERMINISTIC
                    : ResourceFailure.Kind.TRANSIENT;
        } else {
            throw new IllegalArgumentException("Unclassified asset failure", cause);
        }
        return new LoadResult.Failed(new ResourceFailure(
                kind, cause));
    }

    private static StringData readCommonStrings(
            BooleanSupplier cancelled, ModelFileView view, ChunkDataSource chunks)
            throws IOException {
        var common = view.getManifest().commonAssets();
        if (common.stringsBlobId() > 0) {
            return view.getCommon().readStringData(cancelled, chunks);
        }
        return null;
    }

    private static void requireActive(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("Model render target load was cancelled");
        }
    }

    static <T extends AutoCloseable> T publishCandidate(
            BooleanSupplier cancelled, ResourceTransaction resources, T candidate) {
        resources.commit();
        try {
            requireActive(cancelled);
            return candidate;
        } catch (RuntimeException | Error error) {
            try {
                candidate.close();
            } catch (Exception closeError) {
                error.addSuppressed(closeError);
            }
            throw error;
        }
    }

    @FunctionalInterface
    interface BuildStage {
        ModelCandidate build(BooleanSupplier cancelled, LoadedTarget input) throws IOException;
    }

    public sealed interface LoadResult {
        record Ready(ModelCandidate candidate) implements LoadResult {
            public Ready {
                Objects.requireNonNull(candidate, "candidate");
            }
        }

        record Failed(ResourceFailure failure) implements LoadResult {
            public Failed {
                Objects.requireNonNull(failure, "failure");
            }
        }
    }

    record LoadRequest(ModelRepresentation representation, ModelFileView view, RenderTargetView target,
                       PBRTextureSet textureProto, PBRImageSources textureSources,
                       String targetId, String selectedTexture, boolean defaultModel,
                       boolean cacheOnly,
                       ModelResourceFailures resourceFailures) {
    }

    record LoadedTarget(LoadRequest request, ModelData definition,
                        StringData commonStrings) {
    }

    private record DefaultBuildStage(BakedModelCache bakedModels, BakedAnimationCache bakedAnimations,
                                     Executor workers, DefaultAnimationRuntime defaultAnimations)
            implements BuildStage {
        @Override
        public ModelCandidate build(BooleanSupplier cancelled, LoadedTarget input) throws IOException {
            var request = input.request();
            var representation = request.representation();
            var view = request.view();
            var target = request.target();
            var textureProto = request.textureProto();
            var textureSources = request.textureSources();
            var targetId = request.targetId();
            var selectedTexture = request.selectedTexture();
            var definition = input.definition();
            requireActive(cancelled);
            return buildNow(cancelled, representation, view, target, textureProto,
                    textureSources, targetId, selectedTexture, definition,
                    input.commonStrings(), request.defaultModel(), request.cacheOnly(),
                    request.resourceFailures());
        }

        private ModelCandidate buildNow(BooleanSupplier cancelled,
                                           ModelRepresentation representation, ModelFileView view,
                                           RenderTargetView target, PBRTextureSet textureProto,
                                           PBRImageSources textureSources, String targetId, String selectedTexture,
                                           ModelData definition,
                                           StringData commonStrings,
                                           boolean defaultModel,
                                           boolean cacheOnly,
                                           ModelResourceFailures resourceFailures) throws IOException {
            try (var resources = new ResourceTransaction()) {
                var textureResource = targetId + "/" + selectedTexture + "/";
                var textures = resources.own(new PreparedTextureSet(
                        textureSources, textureResource, resourceFailures));
                var textureHash = textureHash(view, textureProto.uv());
                var definitionHash = definitionHash(view, target.descriptor().blobId());
                var controllers = controllerFiles(definition);
                Iterable<? extends Map.Entry<String, AnimationFile>> animationFiles =
                        definition.animationFiles().object2ObjectEntrySet();
                final RenderTargetData targetData;
                switch (target.kind()) {
                case RENDER_TARGET_KIND_PLAYER -> {
                    // 玩家模型的贴图在绘制时会被玩家自己的皮肤替换（SpecialPlayerRenderEvent →
                    // VanillaPlayerRenderEvent → CustomPlayerRenderer.textureOverride），所以模型自带贴图
                    // 不是最终的绘制贴图，不能拿它来决定「哪些面完全透明、可以整面丢掉」。否则模型贴图里
                    // 恰好透明、而玩家皮肤里不透明的部位（内置 Steve/Alex 的手臂叠加层、手、眼皮、瞳孔、
                    // 眉毛）会在烘焙时被永久删除，实机表现就是「手臂的外层皮肤不渲染」。
                    // 因此玩家目标改用「alpha 不归零的贴图」烘焙：不丢面，半透明仍按半透明分类。
                    final GeoModel main;
                    final GeoModel arm;
                    try (var masked = new PlayerBakeMask(textures)) {
                        var bakeTextureHash = maskedTextureHash(textureHash);
                        main = resources.own(bakeModel(representation,
                                targetId + "/" + selectedTexture + "/main",
                                bakeTextureHash, () -> geoModel(definition, "main"), masked, target,
                                textureProto, defaultModel, cacheOnly));
                        arm = resources.own(bakeModel(representation,
                                targetId + "/" + selectedTexture + "/arm",
                                bakeTextureHash, () -> geoModel(definition, "arm"), masked, target,
                                textureProto, defaultModel, cacheOnly));
                    }
                    var mainFiles = new ArrayList<Map.Entry<String, AnimationFile>>();
                    var firstPersonFiles = new ArrayList<Map.Entry<String, AnimationFile>>();
                    for (var file : animationFiles) {
                        (file.getKey().equals("fp_arm") ? firstPersonFiles : mainFiles).add(file);
                    }
                    var animations = resources.own(loadAnimations(representation, target.descriptor(), targetId,
                            "main", definitionHash, mainFiles, defaultModel,
                            resourceFailures));
                    var firstPersonAnimations = resources.own(loadAnimations(representation, target.descriptor(),
                            targetId, "fp_arm", definitionHash, firstPersonFiles,
                            defaultModel, resourceFailures));
                    var variants = new FifoHashMap<>(new String[]{selectedTexture},
                            new PlayerModelVariant[]{new PlayerModelVariant(main, arm)});
                    targetData = new PlayerModelData(variants, animations, firstPersonAnimations,
                            List.copyOf(controllers.values()));
                }
                case RENDER_TARGET_KIND_PROJECTILE, RENDER_TARGET_KIND_VEHICLE -> {
                    var baked = resources.own(bakeModel(representation, targetId + "/" + selectedTexture,
                            textureHash, () -> geoModel(definition, "main"), textures, target, textureProto,
                            defaultModel, cacheOnly));
                    var animations = resources.own(loadAnimations(representation, target.descriptor(), targetId,
                            "main", definitionHash, animationFiles,
                            defaultModel, resourceFailures));
                    var controller = controllers.values().stream().findFirst().orElse(null);
                    targetData =
                            target.kind() == RenderTargetKind.RENDER_TARGET_KIND_PROJECTILE
                                    ? new ProjectileModelData(baked, animations, controller)
                                    : new VehicleModelData(baked, animations, controller);
                }
                default -> throw AssetLoadException.content(
                        "Unsupported render target kind: " + target.kind());
                }

                var common = commonAssets(representation, view, commonStrings);
                var data = new ModelRenderTargetBuildInput(targetId, targetData, common,
                        view.getMetadata());
                textures.prepare(cancelled);
                final ModelRenderTarget targetResult;
                try {
                    targetResult = ModelRenderTargetAssembler.build(representation.modelId(), data);
                } catch (IllegalArgumentException error) {
                    throw AssetLoadException.content(
                            "Invalid assembled model render target: " + targetId, error);
                }
                return publishCandidate(cancelled, resources,
                        new ModelCandidate(targetResult, textures));
            }
        }

        private static CommonAssetData commonAssets(ModelRepresentation representation,
                                                    ModelFileView view,
                                                    StringData source)
                throws IOException {
            var sounds = new LinkedHashMap<String, SoundSource>();
            for (var entry : view.getCommon().sounds().entrySet()) {
                sounds.put(entry.getKey(), new SoundSource(
                        representation.identity(), entry.getValue()));
            }
            var functions = new Object2ReferenceOpenHashMap<String,
                    IValue>();
            if (source != null && !source.userFunctions().isEmpty()) {
                var parser = CustomMolangParser.rentInstance();
                try {
                    for (var function : source.userFunctions()) {
                        try {
                            functions.put(function.name(),
                                    parser.parseExpression(function.body().source(), false));
                        } catch (RuntimeException error) {
                            throw AssetLoadException.content(
                                    "Invalid model function: " + function.name(), error);
                        }
                    }
                } finally {
                    CustomMolangParser.returnInstance(parser);
                }
            }
            return new CommonAssetData(Map.copyOf(sounds), functions);
        }

        private static Map<String, AnimationControllerFile> controllerFiles(
                ModelData source) throws IOException {
            var result = new LinkedHashMap<String, AnimationControllerFile>();
            if (!source.animationControllers().isEmpty()) {
                for (var entry : source.animationControllers().object2ObjectEntrySet()) {
                    try {
                        result.put(entry.getKey(),
                                AnimationProtoMapper.controllerFile(entry.getValue()));
                    } catch (RuntimeException error) {
                        throw AssetLoadException.content(
                                "Invalid animation controller: " + entry.getKey(), error);
                    }
                }
            }
            return result;
        }

        private GeoModel bakeModel(ModelRepresentation representation, String resourceName, byte[] textureHash,
                                   BakedModelCache.GeometrySupplier geo,
                                   BakedModelCache.TexturePixelsSupplier texturePixels,
                                   RenderTargetView target,
                                   PBRTextureSet textureProto,
                                   boolean defaultModel, boolean cacheOnly) throws IOException {
            if (defaultModel) {
                return BakedModelCache.bakeResident(geo.get(), resourceName, texturePixels,
                        CURRENT_RAW_UV_VERSION,
                        target.descriptor().settings().forceCulling(),
                        false, hasPbr(textureProto), PlayerLocator.get());
            }
            if (cacheOnly) {
                return bakedModels.loadExisting(representation.containerId(),
                        resourceName, textureHash, PlayerLocator.get());
            }
            return bakedModels.loadOrBake(representation.containerId(),
                    resourceName, textureHash, geo, texturePixels, CURRENT_RAW_UV_VERSION,
                    target.descriptor().settings().forceCulling(), false,
                    hasPbr(textureProto), PlayerLocator.get());
        }

        private AnimationStore loadAnimations(ModelRepresentation representation,
                                              RenderTarget target,
                                              String targetId, String animationSet,
                                              Hash256 definitionHash,
                                              Iterable<? extends Map.Entry<String,
                                                      AnimationFile>>
                                                      animationFiles,
                                              boolean defaultModel,
                                              ModelResourceFailures resourceFailures) throws IOException {
            if (defaultModel) {
                return BakedAnimationCache.bindResident(animationFiles,
                        (animation, bound) -> defaultAnimations.requireCurrent(
                                target, animationSet, animation));
            }
            var domain = DefaultAnimationKey.domain(target, animationSet);
            var fallback = defaultAnimations.fallback(domain);
            return bakedAnimations.loadOrBake(representation.containerId(),
                    targetId, animationSet, definitionHash, animationFiles, fallback,
                    name -> resourceFailures.animation(targetId + "/" + animationSet + "/" + name));
        }
    }

    static com.kinetic.proto.mixel.asset.model.data.GeoModel geoModel(ModelData source, String name)
            throws IOException {
        for (var entry : source.geoModels().object2ObjectEntrySet()) {
            if (entry.getKey().equals(name)) {
                try {
                    return com.kinetic.proto.mixel.asset.model.data.GeoModel.parseFrom(
                            ProtoSource.newInstance(entry.getValue()));
                } catch (IOException error) {
                    throw AssetLoadException.content(
                            "Invalid geo model named " + name, error);
                }
            }
        }
        throw AssetLoadException.content("Model data contains no geo model named " + name);
    }

    private static byte[] textureHash(ModelFileView view,
                                      Image image)
            throws IOException {
        var chunk = view.getFileView().getAssetView().getChunkInfo(AssetFileConstant.BLOB_CHUNK_PREFIX
                + image.blobId());
        if (chunk == null || chunk.hash() == null || chunk.hash().length != Hash256.SIZE) {
            throw AssetLoadException.content("Texture chunk contains no content hash");
        }
        return chunk.hash();
    }

    private static Hash256 definitionHash(ModelFileView view, int blobId) throws IOException {
        var chunk = view.getFileView().getAssetView().getChunkInfo(AssetFileConstant.BLOB_CHUNK_PREFIX + blobId);
        if (chunk == null || chunk.hash() == null || chunk.hash().length != Hash256.SIZE) {
            throw AssetLoadException.content(
                    "Render target definition contains no content hash");
        }
        return new Hash256(chunk.hash());
    }

    private static boolean hasPbr(PBRTextureSet texture) {
        return texture.hasNormal() || texture.hasSpecular();
    }

    /**
     * 用「alpha 不归零的贴图」烘焙玩家模型时使用的贴图哈希域。
     *
     * <p>烘焙缓存以容器 + 资源名 + 贴图哈希为键。掩码烘焙与原样烘焙的几何不同，必须落在不同的
     * 缓存条目里，否则升级后会复用旧版本里已经删掉叠加层的那份烘焙结果。
     */
    private static final byte[] PLAYER_BAKE_MASK_DOMAIN =
            "kinetic.player-bake-alpha-mask\0".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    static byte[] maskedTextureHash(byte[] textureHash) {
        var input = new byte[PLAYER_BAKE_MASK_DOMAIN.length + textureHash.length];
        System.arraycopy(PLAYER_BAKE_MASK_DOMAIN, 0, input, 0, PLAYER_BAKE_MASK_DOMAIN.length);
        System.arraycopy(textureHash, 0, input, PLAYER_BAKE_MASK_DOMAIN.length, textureHash.length);
        return com.kinetic.model.storage.ModelHashing.blake3(input).bytes();
    }

    /**
     * 玩家模型烘焙用的贴图掩码：与原贴图同尺寸，只把 alpha 为 0 的像素抬到 255。
     *
     * <p>原生烘焙会按贴图 alpha 把「整面全透明」的 quad 判成 {@code kEmpty} 并整面丢弃。这对
     * 「用自己贴图绘制」的模型是纯优化，但对玩家模型不成立——玩家模型绘制时用的是玩家皮肤。
     * 掩码让所有面都保留下来，同时保留「半透明」分类（alpha 既非 0 也非 255 的像素不动），
     * 所以开启 {@code ForceCutoutRenderer=false} 时半透明皮肤仍然走混合路径。
     */
    static final class PlayerBakeMask
            implements BakedModelCache.TexturePixelsSupplier, AutoCloseable {
        private final BakedModelCache.TexturePixelsSupplier source;
        private com.mojang.blaze3d.platform.NativeImage mask;

        PlayerBakeMask(BakedModelCache.TexturePixelsSupplier source) {
            this.source = Objects.requireNonNull(source, "source");
        }

        @Override
        public com.mojang.blaze3d.platform.NativeImage get() throws IOException {
            if (mask == null) {
                var real = source.get();
                var width = real.getWidth();
                var height = real.getHeight();
                var copy = new com.mojang.blaze3d.platform.NativeImage(
                        com.mojang.blaze3d.platform.NativeImage.Format.RGBA, width, height, false);
                try {
                    for (var y = 0; y < height; y++) {
                        for (var x = 0; x < width; x++) {
                            var pixel = real.getPixelRGBA(x, y);
                            // 只抬 alpha==0 的像素；alpha 既非 0 也非 255 的像素必须原样保留，
                            // 否则「半透明」分类会消失、透明渲染路径会改变。
                            // alpha 位于高位字节（与 NativeImage 的 ABGR 内存布局一致）。
                            copy.setPixelRGBA(x, y,
                                    (pixel >>> 24) == 0 ? (pixel | 0xFF000000) : pixel);
                        }
                    }
                } catch (RuntimeException | Error failure) {
                    copy.close();
                    throw failure;
                }
                mask = copy;
            }
            return mask;
        }

        @Override
        public void close() {
            if (mask != null) {
                mask.close();
                mask = null;
            }
        }
    }

    public static final class ModelCandidate implements AutoCloseable {
        private final ModelRenderTarget target;
        private final PreparedTextureSet textures;
        private final AtomicBoolean closed = new AtomicBoolean();
        private boolean published;

        ModelCandidate(ModelRenderTarget target, PreparedTextureSet textures) {
            this.target = Objects.requireNonNull(target, "target");
            this.textures = textures;
        }

        static ModelCandidate testing(ModelRenderTarget target) {
            return new ModelCandidate(target, null);
        }

        public ModelRenderTarget publish() throws Exception {
            return publish(HostTexturePublisher.production());
        }

        ModelRenderTarget publish(HostTexturePublisher publisher) throws Exception {
            if (closed.get() || published) {
                throw new IllegalStateException("Model candidate is no longer publishable");
            }
            if (textures == null) {
                published = true;
                return target;
            }
            var binding = publisher.publish(textures);
            try {
                target.adoptTexture(binding.base(), binding);
            } catch (Throwable error) {
                binding.close();
                throw error;
            }
            published = true;
            return target;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            if (textures != null) {
                textures.close();
            }
            target.close();
        }

        void reject(Consumer<ModelRenderTarget> rejectedTargetCloser) {
            if (textures == null) {
                if (closed.compareAndSet(false, true)) {
                    rejectedTargetCloser.accept(target);
                }
            } else {
                close();
            }
        }
    }
}
