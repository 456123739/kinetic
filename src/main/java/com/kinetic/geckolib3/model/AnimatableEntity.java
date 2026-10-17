package com.kinetic.geckolib3.model;

import com.kinetic.client.animation.molang.PhysicsManager;
import com.kinetic.client.entity.IPreviewEntity;
import com.kinetic.client.event.ClientTickEvent;
import com.kinetic.client.sound.stream.AudioStreamProvider;
import com.kinetic.geckolib3.core.AnimationState;
import com.kinetic.geckolib3.core.builder.Animation;
import com.kinetic.geckolib3.core.builder.controller.AnimationControllerData;
import com.kinetic.geckolib3.core.controller.IAnimationController;
import com.kinetic.geckolib3.core.event.predicate.AnimationEvent;
import com.kinetic.geckolib3.core.manager.AnimationData;
import com.kinetic.geckolib3.core.molang.context.DebugSource;
import com.kinetic.geckolib3.core.molang.storage.IForeignVariableStorage;
import com.kinetic.geckolib3.core.molang.value.IValue;
import com.kinetic.geckolib3.core.processor.AnimationProcessor;
import com.kinetic.geckolib3.core.processor.BoneView;
import com.kinetic.geckolib3.core.util.RateLimiter;
import com.kinetic.geckolib3.geo.GeoRenderData;
import com.kinetic.geckolib3.geo.RenderContext;
import com.kinetic.geckolib3.geo.render.built.GeoModel;
import com.kinetic.geckolib3.model.provider.data.EntityModelData;
import com.kinetic.natives.NativeProfiler;
import com.kinetic.util.RenderUtil;
import com.google.common.collect.Maps;
import it.unimi.dsi.fastutil.objects.Object2ReferenceMap;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

public abstract class AnimatableEntity<TEntity extends Entity> {
    private final AnimationData manager = new AnimationData();
    private final AnimationProcessor<TEntity> animationProcessor;
    private final RateLimiter rateLimiter;
    private final EntityStateTracker<TEntity> stateTracker;
    private final Map<RenderContext, GeoRenderData> renderDataPool = new HashMap<>();
    protected final PhysicsManager physicsManager;

    protected final TEntity entity;
    /**
     * 实体的运行时类型在实例存活期间不会改变，而 {@link #createAnimationEvent} 每个渲染帧都会执行。
     * 这两个判定因此只在构造时算一次，不再逐帧 instanceof。
     */
    @Nullable
    private final LivingEntity livingEntity;
    private final boolean previewEntity;
    private AnimatedGeoModel currentModel;
    private Object2ReferenceMap<String, List<IValue>> eventHandlers;
    private GeoRenderData mainRenderData;

    // 这两个变量不跟随动画一起更新，所以不能放进 stateTracker
    protected float lastFrameTime = -1;
    protected boolean lastMutableRender;
    protected boolean currentFrameTicked;
    protected boolean currentFrameShouldTick;

    private boolean currentFrameExtracted = false;

    /**
     * {@link #getFrameRateLimit()} 的按 tick 缓存。距离分档（远处低刷）只在一个游戏 tick 内保持有效，
     * 此前每个渲染帧都会为每个实体重算一次距离（含开方）。
     */
    private int cachedFrameRateLimit;
    private int cachedFrameRateLimitTick = Integer.MIN_VALUE;

    protected boolean lastFrameRendered = true;
    protected boolean currentFrameRendered = false;
    protected boolean lastFrameUpdated;
    protected float seekTime;
    protected boolean initialize = false;

    /**
     * 存储 Coded 动画控制器的动画播放状态，用于一些 molang 判断
     */
    protected Map<String, AnimationState> codedAnimationStates = Maps.newHashMap();

    protected AnimatableEntity(TEntity entity) {
        this.entity = entity;
        this.livingEntity = entity instanceof LivingEntity living ? living : null;
        this.previewEntity = this instanceof IPreviewEntity;
        this.animationProcessor = new AnimationProcessor<>(this);
        this.rateLimiter = new RateLimiter();
        this.stateTracker = createStateTracker(entity);
        this.physicsManager = new PhysicsManager();
        this.rateLimiter.setLimit(getFrameRateLimit());
    }

    protected void resetGeoModel() {
        if (mainRenderData != null) {
            mainRenderData.modelState.close();
            mainRenderData = null;
        }
        for (var data : renderDataPool.values()) {
            data.modelState.close();
        }
        renderDataPool.clear();

        currentModel = null;
        eventHandlers = null;
        animationProcessor.clearModel();
        physicsManager.reset();
        rateLimiter.reset();
        manager.reset();
        stateTracker.reset();
        lastFrameTime = -1;
        lastMutableRender = false;
        currentFrameTicked = false;
        currentFrameShouldTick = false;
        lastFrameRendered = true;
        currentFrameRendered = false;
        currentFrameExtracted = false;
        cachedFrameRateLimitTick = Integer.MIN_VALUE;
        lastFrameUpdated = false;
        seekTime = 0;
        codedAnimationStates.clear();
    }

    protected EntityStateTracker<TEntity> createStateTracker(TEntity entity) {
        return new EntityStateTracker<>(entity);
    }

    public EntityStateTracker<TEntity> getStateTracker() {
        return stateTracker;
    }

    public float getSeekTime() {
        return seekTime;
    }

    @SuppressWarnings("rawtypes")
    public <T extends AnimatableEntity<TEntity>> void addAnimationController(IAnimationController value) {
        this.manager.addAnimationController(value);
    }

    public AnimationData getAnimationData() {
        return manager;
    }

    public abstract ResourceLocation getTextureLocation();

    public abstract boolean isModelPresent();

    public abstract float getWidthScale();

    public abstract float getHeightScale();

    @Nullable
    public abstract Animation getAnimation(String name);

    @Nullable
    public IValue getUserFunction(String name) {
        return null;
    }

    public Optional<AudioStreamProvider> getSoundStream(String name) {
        return Optional.empty();
    }

    @Nullable
    public final List<IValue> getEventHandler(String name) {
        return this.eventHandlers.get(name);
    }

    public PhysicsManager getPhysicsManager(AnimationEvent<?> event) {
        return physicsManager;
    }

    @Nullable
    public AnimationControllerData getAnimationControllerData(String animationControllerName) {
        return null;
    }

    protected float getSwingMotionAniMathHelperreshold() {
        return 0.15f;
    }

    /**
     * 更新动画之前调用，
     * 如果由于频率限制、renderTick 倒退等原因导致动画不更新，则不会调用
     */
    protected void preAnimationSetup(float seekTime, boolean shouldTick) {
    }

    /**
     * 更新动画之后调用，
     * 如果由于频率限制、renderTick 倒退等原因导致动画不更新，则不会调用
     */
    protected void postAnimationSetup(float seekTime, boolean shouldTick) {
    }

    public final TEntity getEntity() {
        return entity;
    }

    public boolean isFakePlayer() {
        return false;
    }

    @Nullable
    public BoneView getBone(int boneName) {
        return animationProcessor.getBone(boneName);
    }

    protected boolean allowEmitting() {
        return true;
    }

    public int getFrameRateLimit() {
        // 距离分档只在一个游戏 tick 内保持有效，按 tick 缓存可以省掉每个渲染帧、每个实体一次的
        // 位置向量分配与开方距离计算。
        var tickCount = entity.tickCount;
        if (cachedFrameRateLimitTick == tickCount) {
            return cachedFrameRateLimit;
        }
        cachedFrameRateLimitTick = tickCount;
        cachedFrameRateLimit = computeFrameRateLimit();
        return cachedFrameRateLimit;
    }

    private int computeFrameRateLimit() {
        var localPlayer = Minecraft.getInstance().player;
        if (localPlayer != null && localPlayer != entity) {
            var localPos = localPlayer.position();
            if (localPos.x != 0 || localPos.y != 0 || localPos.z != 0) {
                // 未渲染：屏幕外、太远、被 EntityCulling 剔除
                if (!lastFrameRendered) {
                    return ClientTickEvent.getAnimationRateHidden();
                }

                var distance = localPlayer.distanceTo(entity);
                // 超远距离：原版超过这个距离会跳过渲染
                if (distance > ClientTickEvent.getAnimationDistanceFar()) {
                    return ClientTickEvent.getAnimationRateFar();
                }
                // 一般远距离
                if (distance > ClientTickEvent.getAnimationDistanceNear()) {
                    return ClientTickEvent.getAnimationRateNear();
                }
            }
        }
        return ClientTickEvent.getAnimationRefreshRate();
    }

    public @Nullable GeoRenderData update(float partialTicks) {
        return update(partialTicks, resolveRenderContext(RenderUtil.extractRenderContext()));
    }

    @Nullable
    protected GeoRenderData update(float partialTicks, RenderContext context) {
        try (var ignored = NativeProfiler.beginAnimatableUpdate()) {
            if (this.currentModel == null) {
                return null;
            }
            var event = createAnimationEvent(partialTicks, context);
            tickAnimation(event);

            var renderData = getRenderData(context);
            if (!context.immutable() || !currentFrameExtracted) {
                if (context.immutable()) {
                    currentFrameExtracted = true;
                }
                extractRenderData(event, renderData);
            } else {
                renderData.ctx = context;
            }
            return renderData;
        }
    }

    protected GeoRenderData createRenderData() {
        return new GeoRenderData();
    }

    protected void extractRenderData(AnimationEvent<?> event, GeoRenderData data) {
        data.modelState.extract(currentModel);
        data.ctx = event.getRenderContext();
        data.texture = getTextureLocation();
        data.widthScale = getWidthScale();
        data.heightScale = getHeightScale();
        data.partialTicks = event.getRequestedPartialTick();
        data.animationData = event.getExtraData();
    }

    /**
     * 最近一次「关卡（immutable）」上下文烘焙出来的渲染数据。
     *
     * <p>{@code AnimationParallelTicker#scheduleAll} 每帧都会以 {@code levelImmutable()} 上下文调用一次
     * {@link #update}，因此这份数据对每个活跃实体都是逐帧更新的，不依赖它本帧是否真的被画出来。
     *
     * <p>只读消费者（例如真实相机绑定）用它拿到当前动画姿态下的骨骼矩阵；返回 {@code null} 表示模型
     * 还没烘焙过。不要在渲染线程之外读取。
     */
    @Nullable
    public GeoRenderData getMainRenderData() {
        return mainRenderData;
    }

    private GeoRenderData getRenderData(RenderContext context) {
        GeoRenderData renderData;
        if (context.immutable()) {
            if (mainRenderData == null) {
                renderData = createRenderData();
                mainRenderData = renderData;
            } else {
                renderData = mainRenderData;
            }
        } else {
            renderData = renderDataPool.computeIfAbsent(context, c -> createRenderData());
        }
        return renderData;
    }

    private AnimationEvent<AnimatableEntity<TEntity>> createAnimationEvent(float partialTicks, RenderContext context) {
        final Entity entity = this.entity;
        final LivingEntity livingEntity = this.livingEntity;
        int entityTickCount = this.previewEntity ? ClientTickEvent.getTickCount() : entity.tickCount;
        float realPartialTicks = partialTicks != 1f ? partialTicks : Minecraft.getInstance().getFrameTime();

        final Entity vehicle = entity.getVehicle();
        boolean shouldSit = entity.isPassenger() && (vehicle != null && vehicle.shouldRiderSit());
        float limbSwingAmount = 0;
        float limbSwing = 0;

        if (!shouldSit && entity.isAlive() && livingEntity != null) {
            limbSwingAmount = livingEntity.walkAnimation.speed(partialTicks);
            limbSwing = livingEntity.walkAnimation.position(partialTicks);
            if (livingEntity.isBaby()) {
                limbSwing *= 3.0F;
            }
        }

        EntityModelData entityModelData = new EntityModelData();
        entityModelData.isSitting = shouldSit;

        float lerpBodyRot = 0;
        float lerpHeadRot = 0;
        float netHeadYaw = 0;

        if (livingEntity != null) {
            entityModelData.isChild = livingEntity.isBaby();
            lerpBodyRot = Mth.rotLerp(partialTicks, livingEntity.yBodyRotO, livingEntity.yBodyRot);
            lerpHeadRot = Mth.rotLerp(partialTicks, livingEntity.yHeadRotO, livingEntity.yHeadRot);
            netHeadYaw = lerpHeadRot - lerpBodyRot;
        }

        if (shouldSit && vehicle instanceof LivingEntity livingVehicle) {
            lerpBodyRot = Mth.rotLerp(partialTicks, livingVehicle.yBodyRotO, livingVehicle.yBodyRot);
            netHeadYaw = lerpHeadRot - lerpBodyRot;
            float clampedHeadYaw = Mth.clamp(Mth.wrapDegrees(netHeadYaw), -85, 85);
            lerpBodyRot = lerpHeadRot - clampedHeadYaw;
            if (clampedHeadYaw * clampedHeadYaw > 2500f) {
                lerpBodyRot += clampedHeadYaw * 0.2f;
            }
            netHeadYaw = lerpHeadRot - lerpBodyRot;
        }

        entityModelData.rawHeadPitch = Mth.lerp(partialTicks, entity.xRotO, entity.getXRot());
        entityModelData.headPitch = -entityModelData.rawHeadPitch;
        entityModelData.rawNetHeadYaw = netHeadYaw;
        entityModelData.netHeadYaw = -Mth.clamp(Mth.wrapDegrees(netHeadYaw), -85, 85);
        entityModelData.lerpBodyRot = lerpBodyRot;
        entityModelData.lerpedAge = entityTickCount + partialTicks;
        entityModelData.limbSwing = limbSwing;
        entityModelData.limbSwingAmount = limbSwingAmount;
        entityModelData.isMoving =  (limbSwingAmount <= -getSwingMotionAniMathHelperreshold() || limbSwingAmount <= getSwingMotionAniMathHelperreshold());

        return new AnimationEvent<>(this,
                entityTickCount, partialTicks, realPartialTicks,
                context,
                entityModelData,
                getDebugSource());
    }

    protected void tickAnimation(@NotNull AnimationEvent<AnimatableEntity<TEntity>> animationEvent) {
        var frameTime = animationEvent.renderTicks;
        var ctx = animationEvent.getRenderContext();
        var mutableRender = !ctx.immutable();

        if (frameTime > lastFrameTime) {
            currentFrameTicked = false;
            currentFrameShouldTick = false;
            lastFrameTime = frameTime;
            rateLimiter.setLimit(getFrameRateLimit());
            lastFrameRendered = currentFrameRendered;
            currentFrameExtracted = false;
            currentFrameRendered = false;
        } else {
            // 目前不允许倒退，可能会影响 replay 的回放
            frameTime = lastFrameTime;
        }

        if (manager.startTick == -1) {
            manager.startTick = frameTime;
        } else {
            float currentTick = frameTime - manager.startTick;
            float deltaTicks = currentTick - manager.lastTick;
            if (deltaTicks > 0f) {
                manager.lastTick = currentTick;
                this.seekTime += deltaTicks;
            }
        }
        animationEvent.renderTicks = this.seekTime;

        if (!animationProcessor.isModelEmpty()) {
            currentFrameShouldTick |= rateLimiter.request(seekTime / 20);
            var shouldUpdate = (currentFrameShouldTick && !currentFrameTicked) || lastMutableRender || mutableRender;
            var shouldTick = (!mutableRender || (seekTime == 0 && !currentFrameTicked)) && currentFrameShouldTick && !currentFrameTicked;
            recoverLastCodedAnimation(lastFrameUpdated);
            if (shouldUpdate) {
                if (shouldTick) {
                    currentFrameTicked = true;
                    stateTracker.update(animationEvent.getEntityTickCount(), this.seekTime, animationEvent.getPartialTick());
                }
                getPhysicsManager(animationEvent).update(this.seekTime);
                preAnimationSetup(this.seekTime, shouldTick);
                getAnimationProcessor().tickAnimation(animationEvent, shouldTick, allowEmitting());
                postAnimationSetup(this.seekTime, shouldTick);
                lastMutableRender = mutableRender;
            }
            codeAnimation(animationEvent, shouldUpdate);
            lastFrameUpdated = shouldUpdate;
        }
    }

    protected void codeAnimation(AnimationEvent<? extends AnimatableEntity<TEntity>> animationEvent, boolean shouldUpdate) {
    }

    protected void recoverLastCodedAnimation(boolean lastFrameUpdated) {
    }

    public AnimationProcessor<TEntity> getAnimationProcessor() {
        return this.animationProcessor;
    }

    protected abstract void onSetupAnimationController();

    /**
     * 设置模型
     */
    protected void loadGeoModel(@NotNull GeoModel model, Object2ReferenceMap<String, List<IValue>> eventHandlers) {
        resetGeoModel();
        this.currentModel = new AnimatedGeoModel(model);
        this.eventHandlers = eventHandlers;
        onSetupAnimationController();
        this.animationProcessor.loadModel(currentModel, eventHandlers);
        onLoadGeoModel(this.currentModel);
    }

    protected final void setGeoModelInplace(@NotNull GeoModel model) {
        if (currentModel != null && currentModel.getModel() != model) {
            currentModel.setModelInplace(model);
        }
    }

    public void reloadGeoModel() {
        if (this.currentModel != null) {
            var model = this.currentModel.getModel();
            var eventHandlers = this.eventHandlers;
            resetGeoModel();
            loadGeoModel(model, eventHandlers);
        }
    }

    /**
     * 获取当前正在使用的模型
     */
    @Nullable
    public final AnimatedGeoModel getLoadedGeoModel() {
        return currentModel;
    }

    public final boolean hasRenderableModel() {
        return currentModel != null;
    }

    /**
     * 更新当前使用的模型后调用
     */
    protected void onLoadGeoModel(AnimatedGeoModel model) {
    }

    /**
     * 渲染期间是否会保持实体属性不变
     */
    protected final RenderContext resolveRenderContext(RenderContext context) {
        return context.withImmutable(determineImmutableContext(context));
    }

    protected boolean determineImmutableContext(RenderContext context) {
        if (context.inventory() || context.firstPersonMod() || context.paperDoll()) {
            return false;
        }
        return context.level() || context.irisShadow() || context.offScreen();
    }

    public void countRender() {
        currentFrameRendered = true;
    }

    public void executeMolangExp(IValue value, boolean allowEmitting, boolean pre, @Nullable Consumer<String> resultConsumer) {
        animationProcessor.enqueueMolangTask(value, allowEmitting, pre, resultConsumer);
    }

    public IForeignVariableStorage getPublicVariableStorage() {
        return this.animationProcessor.getPublicVariableStorage();
    }

    protected void setInitialized() {
        this.initialize = true;
    }

    public boolean isInitialized() {
        return this.initialize;
    }

    @Nullable
    public DebugSource getDebugSource() {
        return null;
    }

    @SuppressWarnings("resource")
    public boolean isActive() {
        return Minecraft.getInstance().level == entity.level() && !entity.isRemoved();
    }

    public void setCodedAnimationStates(String controllerName, AnimationState state) {
        this.codedAnimationStates.put(controllerName, state);
    }

    public AnimationState getCodedAnimationStates(String controllerName) {
        return this.codedAnimationStates.getOrDefault(controllerName, AnimationState.IDLE);
    }
}
