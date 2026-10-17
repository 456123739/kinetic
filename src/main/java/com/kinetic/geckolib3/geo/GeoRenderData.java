package com.kinetic.geckolib3.geo;

import com.kinetic.geckolib3.geo.animated.GeoModelState;
import com.kinetic.geckolib3.model.provider.data.EntityModelData;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

public class GeoRenderData {
    public final GeoModelState modelState = new GeoModelState();

    public ResourceLocation texture;
    /**
     * 本帧身体<b>实际</b>使用的贴图，即已经过 {@code SpecialPlayerRenderEvent} 覆盖链之后的那一张。
     *
     * <p>与 {@link #texture} 的区别：{@code texture} 是能力自身的贴图（内置 Steve/Alex 模型自带
     * {@code gsl.png} / {@code tartaric_acid.png}），真正画出来的却是玩家皮肤，皮肤类模组也挂在这条
     * 链上。凡是需要「与真实相机配置里的 textureId 对齐」的消费者（相机绑定、模型视图界面）都必须
     * 用这个字段，否则永远匹配不上 {@code minecraft:textures/entity/player/...}。
     */
    public ResourceLocation renderedTexture;
    /**
     * 本帧身体实际用的渲染类型（已含贴图覆盖、可见性、发光、透明判断）。
     *
     * <p>叠加层要复用它：叠加层画在<b>相同深度</b>上，必须在提交自己之前把身体那一批刷出去，
     * 而"身体那一批"是按 RenderType 区分的。让叠加层自己再推一遍这个类型，只要有一项判断不一致
     * （例如皮肤链给了贴图覆盖），刷批就会落空。
     */
    public RenderType bodyRenderType;
    public float widthScale;
    public float heightScale;
    public boolean renderLayersFirst;
    public RenderContext ctx;

    public float partialTicks;
    public EntityModelData animationData;
}
