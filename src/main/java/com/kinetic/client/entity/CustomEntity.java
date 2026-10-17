package com.kinetic.client.entity;

import com.kinetic.client.animation.AnimationParallelTicker;
import com.kinetic.client.animation.debug.CustomDebugSource;
import com.kinetic.client.animation.molang.MolangEventWrapper;
import com.kinetic.client.animation.molang.PhysicsManager;
import com.kinetic.client.gui.overlay.DebugAnimationScreen;
import com.kinetic.model.resource.client.AcquireResult;
import com.kinetic.model.resource.client.ModelRenderTarget;
import com.kinetic.model.service.ClientModelService;
import com.kinetic.model.resource.client.ResourceLease;
import com.kinetic.model.resource.client.ResourceRequest;
import com.kinetic.client.sound.stream.AudioStreamProvider;
import com.kinetic.geckolib3.core.event.predicate.AnimationEvent;
import com.kinetic.geckolib3.core.molang.context.DebugSource;
import com.kinetic.geckolib3.core.molang.value.IValue;
import com.kinetic.geckolib3.core.processor.DebugInfo;
import com.kinetic.geckolib3.geo.GeoRenderData;
import com.kinetic.geckolib3.geo.RenderContext;
import com.kinetic.geckolib3.geo.render.built.GeoModel;
import com.kinetic.geckolib3.model.AnimatableEntity;
import com.kinetic.model.domain.Hash256;
import com.kinetic.model.domain.RenderTargetIds;
import com.kinetic.util.Closeable;
import com.kinetic.util.ThreadTools;
import com.kinetic.util.UnsafeUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Future;
import com.kinetic.model.catalog.builtin.BuiltinModelIndex;

/**
 * 自动管理当前 model id 和 model container，并在找不到指定模型时 fallback 到默认模型
 */
public abstract class CustomEntity<T extends Entity> extends AnimatableEntity<T> {
    private final EntityModelBinding modelBinding;
    private final ModelDisplayPathCache modelDisplayPathCache = new ModelDisplayPathCache();
    private ModelRenderTarget currentModelRenderTarget;
    private boolean modelFallback;
    private int lastCheckUpdateTime;
    @Nullable
    private PhysicsManager alterPhysicsManager;
    @Nullable
    private DebugInfo debugInfo;
    @Nullable
    private List<IValue> deferHandler;

    @Nullable
    private Future<GeoRenderData> asyncTask;

    protected CustomEntity(T entity, boolean asyncUpdate) {
        this(entity, asyncUpdate, new EntityModelBinding());
    }

    CustomEntity(T entity, boolean asyncUpdate, EntityModelBinding modelBinding) {
        super(entity);
        this.modelBinding = Objects.requireNonNull(modelBinding, "modelBinding");
        if (asyncUpdate) {
            AnimationParallelTicker.add(this);
        }
    }

    @Override
    public PhysicsManager getPhysicsManager(AnimationEvent<?> event) {
        var context = event.getRenderContext();
        if (context.immutable() || context.firstPersonMod() || context.paperDoll()) {
            return physicsManager;
        }
        if (alterPhysicsManager == null) {
            alterPhysicsManager = new PhysicsManager();
        }
        return alterPhysicsManager;
    }

    @Nullable
    public List<IValue> getMolangDeferHandler() {
        return deferHandler;
    }

    public void setDebugInfo(@Nullable DebugInfo debugInfo) {
        this.debugInfo = debugInfo;
    }

    @Override
    protected void preAnimationSetup(float seekTime, boolean shouldTick) {
        super.preAnimationSetup(seekTime, shouldTick);
        // 更新调试信息
        if (debugInfo != null) {
            var processor = getAnimationProcessor();
            processor.enqueueMolangTask(evaluator -> {
                debugInfo.evaluatePre(evaluator);
                return null;
            }, false, true, null);
            processor.enqueueMolangTask(evaluator -> {
                debugInfo.evaluatePost(evaluator);
                return null;
            }, false, false, null);
        }
    }

    public void checkModelUpdate() {
        if (lastCheckUpdateTime < entity.tickCount) {
            checkModelRenderTargetUpdate();
            lastCheckUpdateTime = entity.tickCount;
        }
    }

    public final ModelRenderTarget getModelRenderTarget() {
        return currentModelRenderTarget;
    }

    protected final void updateModelHash(Hash256 modelHash) {
        modelBinding.updateModelHash(
                modelHash, requestedRenderTargetId(), requestedTextureName());
        checkModelRenderTargetUpdate();
    }

    private void checkModelRenderTargetUpdate() {
        modelBinding.synchronize(
                requestedRenderTargetId(), requestedTextureName(), fallbackRenderTargetId(), this::createResourceHolder);
        applyBoundResource();
    }

    protected final void installReadyForPreview(ResourceRequest request,
                                                ResourceLease lease) {
        modelBinding.installReadyForPreview(request, lease, this::createResourceHolder);
        applyBoundResource();
    }

    private void applyBoundResource() {
        var resourceHolder = modelBinding.resourceHolder();
        if (resourceHolder != null) {
            if ((resourceHolder.model != currentModelRenderTarget || resourceHolder.fallback != modelFallback) && resourceHolder.isLoaded()) {
                currentModelRenderTarget = resourceHolder.model;
                modelFallback = resourceHolder.fallback;
                onModelRenderTargetLoaded(currentModelRenderTarget);
                loadGeoModel(getKineticGeoModel(), currentModelRenderTarget.assets().eventHandlers());
            }
        } else if (currentModelRenderTarget != null) {
            resetModelRenderTarget();
        }
    }

    @Nullable
    protected abstract ResourceHolder createResourceHolder(ResourceLease lease, boolean isFallback);

    protected String requestedTextureName() {
        return "";
    }

    protected String requestedRenderTargetId() {
        return RenderTargetIds.PLAYER;
    }

    protected String fallbackRenderTargetId() {
        return requestedRenderTargetId();
    }

    protected final ResourceHolder getResourceHolder() {
        return modelBinding.resourceHolder();
    }

    protected void onModelRenderTargetLoaded(ModelRenderTarget newModel) {
        var resourceHolder = modelBinding.resourceHolder();
        if (resourceHolder == null) {
            return;
        }
        deferHandler = newModel.assets().eventHandlers().get(MolangEventWrapper.DEFER);
    }

    protected void resetModelRenderTarget() {
        modelBinding.releaseRenderTarget();
        currentModelRenderTarget = null;
        deferHandler = null;
        modelFallback = false;
        resetGeoModel();
    }

    @Override
    protected void resetGeoModel() {
        super.resetGeoModel();
        alterPhysicsManager = null;
        lastCheckUpdateTime = 0;
    }

    public void reset() {
        modelBinding.clearModel();
        initialize = false;
        resetModelRenderTarget();
    }

    // getGeoModel 跟女仆的 IGeoEntity 冲突了，所以叫这个
    protected abstract GeoModel getKineticGeoModel();

    public final Hash256 getModelHash() {
        return modelBinding.modelHash();
    }

    /** Presentation path for GUI and third-party string APIs; never used as model identity. */
    public final String getModelId() {
        var modelHash = modelBinding.modelHash();
        if (modelHash == null) {
            return BuiltinModelIndex.INTRINSIC_DEFAULT_PATH.value();
        }
        var service = ClientModelService.instance();
        var catalog = service.catalog();
        var cached = modelDisplayPathCache.find(modelHash, catalog);
        if (cached != null) {
            return cached;
        }
        var path = service.displayPath(modelHash);
        // 目录快照若在查询期间被替换，这次就不写入缓存，避免把旧快照的结果挂到新快照上。
        if (service.catalog() == catalog) {
            modelDisplayPathCache.put(modelHash, catalog, path);
        }
        return path;
    }

    @Override
    public boolean isModelPresent() {
        var resourceHolder = modelBinding.resourceHolder();
        return resourceHolder != null && !resourceHolder.fallback && resourceHolder.isLoaded();
    }

    @Override
    @Nullable
    public final IValue getUserFunction(String name) {
        return getModelRenderTarget().assets().userFunctions().get(name);
    }

    @Override
    public Optional<AudioStreamProvider> getSoundStream(String name) {
        var target = currentModelRenderTarget;
        if (target == null) {
            return Optional.empty();
        }
        var source = target.assets().sounds().get(name);
        if (source == null) {
            return Optional.empty();
        }
        return Optional.of(ClientModelService.instance().createSoundPlayback(source));
    }

    @Override
    public DebugSource getDebugSource() {
        if (DebugAnimationScreen.isEnabled()) {
            return CustomDebugSource.INSTANCE;
        } else {
            return null;
        }
    }

    public void beginAsyncUpdate(final float partialTicks) {
        waitForAsyncUpdate();
        UnsafeUtil.getUnsafe().storeFence();
        asyncTask = ThreadTools.submit(() -> {
            try {
                return super.update(partialTicks, RenderContext.levelImmutable());
            } finally {
                UnsafeUtil.getUnsafe().storeFence();
            }
        });
    }

    @Override
    @Nullable
    protected GeoRenderData update(float partialTicks, RenderContext context) {
        RenderSystem.assertOnRenderThread();
        var resolvedContext = resolveRenderContext(context);
        if (resolvedContext.immutable() && asyncTask != null) {
            var result = awaitAsyncUpdate();
            if (result != null) {
                return result;
            }
        }
        waitForAsyncUpdate();
        checkModelUpdate();
        return super.update(partialTicks, resolvedContext);
    }

    public void waitForAsyncUpdate() {
        awaitAsyncUpdate();
    }

    private @Nullable GeoRenderData awaitAsyncUpdate() {
        if (asyncTask != null) {
            GeoRenderData result = null;
            try {
                result = asyncTask.get();
                UnsafeUtil.getUnsafe().loadFence();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable e) {
                e.printStackTrace();
            }
            asyncTask = null;
            return result;
        }
        return null;
    }

    public boolean canUpdateAsync() {
        return true;
    }

    protected static class ResourceHolder implements Closeable {
        private final ResourceLease lease;
        public final ModelRenderTarget model;
        public final boolean fallback;
        private boolean closed;

        protected ResourceHolder(ResourceLease lease, boolean fallback) {
            this.lease = lease;
            if (!(lease.poll() instanceof AcquireResult.Ready ready)) {
                throw new IllegalArgumentException("Resource holder requires a ready lease");
            }
            this.model = ready.target();
            this.fallback = fallback;
        }

        public boolean isLoaded() {
            return true;
        }

        ResourceLease lease() {
            return lease;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
        }

    }
}
