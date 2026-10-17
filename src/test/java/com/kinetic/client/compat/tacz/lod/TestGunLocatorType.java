package com.kinetic.client.compat.tacz.lod;

import com.kinetic.geckolib3.geo.render.built.GeoLocatorType;
import net.minecraft.resources.ResourceLocation;

/**
 * 测试用的空 locator 类型。枪械 LOD 模型没有任何 locator，但 {@code GeoModel} 的构造需要一个
 * {@code GeoLocatorType}；单测里不能初始化 {@code PlayerLocator}（它要发 Forge 事件），所以这里
 * 提供一个最小实现。
 */
public final class TestGunLocatorType extends GeoLocatorType {
    private static final TestGunLocatorType INSTANCE = new TestGunLocatorType();

    private TestGunLocatorType() {
        super(new ResourceLocation("kinetic", "test_gun_lod"));
    }

    public static TestGunLocatorType get() {
        return INSTANCE;
    }
}
