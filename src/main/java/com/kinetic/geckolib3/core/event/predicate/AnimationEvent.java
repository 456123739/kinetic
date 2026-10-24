package com.kinetic.geckolib3.core.event.predicate;

import com.kinetic.geckolib3.core.controller.CodedAnimationController;
import com.kinetic.geckolib3.core.molang.context.DebugSource;
import com.kinetic.geckolib3.geo.RenderContext;
import com.kinetic.geckolib3.model.AnimatableEntity;
import com.kinetic.geckolib3.model.provider.data.EntityModelData;
import org.jetbrains.annotations.NotNull;

public class AnimationEvent<T extends AnimatableEntity<?>> {
    // 这些字段每帧都会被 reset：AnimatableEntity 复用同一个事件对象，避免每个实体每帧
    // 分配一个 AnimationEvent + 一个 EntityModelData（40 玩家 144 fps 下约 1.1 万对/秒）。
    private T animatable;
    private int entityTickCount;
    private float requestedPartialTick;
    private float partialTick;
    private RenderContext renderContext;
    public float renderTicks;
    private EntityModelData extraData;
    private DebugSource debugSource;
    protected CodedAnimationController<T> codedController;

    public AnimationEvent(T animatable,
                          int entityTickCount, float requestedPartialTick, float partialTick,
                          RenderContext renderContext,
                          @NotNull EntityModelData extraData,
                          DebugSource debugSource) {
        this.animatable = animatable;
        this.entityTickCount = entityTickCount;
        this.requestedPartialTick = requestedPartialTick;
        this.partialTick = partialTick;
        this.renderTicks = entityTickCount + partialTick;
        this.renderContext = renderContext;
        this.extraData = extraData;
        this.debugSource = debugSource;
    }

    /** 复用同一个实例时重置全部可变字段；语义与构造器完全一致。 */
    public void reset(T animatable,
                      int entityTickCount, float requestedPartialTick, float partialTick,
                      RenderContext renderContext,
                      @NotNull EntityModelData extraData,
                      DebugSource debugSource) {
        this.animatable = animatable;
        this.entityTickCount = entityTickCount;
        this.requestedPartialTick = requestedPartialTick;
        this.partialTick = partialTick;
        this.renderTicks = entityTickCount + partialTick;
        this.renderContext = renderContext;
        this.extraData = extraData;
        this.debugSource = debugSource;
        this.codedController = null;
    }

    public float getRenderTicks() {
        return renderTicks;
    }

    public T getAnimatableEntity() {
        return animatable;
    }

    public float getLimbSwing() {
        return extraData.limbSwing;
    }

    public float getLimbSwingAmount() {
        return extraData.limbSwingAmount;
    }

    public int getEntityTickCount() {
        return entityTickCount;
    }

    public float getRequestedPartialTick() {
        return requestedPartialTick;
    }

    public float getPartialTick() {
        return partialTick;
    }

    public boolean isMoving() {
        return extraData.isMoving;
    }

    public boolean isRenderingInLevelExclusive() {
        return renderContext.level();
    }

    public RenderContext getRenderContext() {
        return renderContext;
    }

    public CodedAnimationController<T> getCodedController() {
        return codedController;
    }

    public void setCodedAnimationController(CodedAnimationController<T> controller) {
        this.codedController = controller;
    }

    @NotNull
    public EntityModelData getExtraData() {
        return extraData;
    }

    public DebugSource getDebugSource() {
        return debugSource;
    }
}
