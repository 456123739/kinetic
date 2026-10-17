package com.kinetic.capability;

import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.common.util.NonNullSupplier;

/**
 * 缓存 capability 查询返回的 {@link LazyOptional}。
 *
 * <p>Forge 的 {@code ICapabilityProvider#getCapability} 惯例是每次调用都新建一个
 * {@code LazyOptional.of(supplier)}。而 {@link LazyOptional} 的构造会同时分配
 * 自身、锁对象、监听者集合以及 supplier 本身（共约 4~5 个对象），
 * 于是「每个渲染帧、每个实体一次 capability 查询」就变成每帧每实体 4~5 次分配。
 *
 * <p>这些 provider 的 capability 实例在实体存活期间是稳定的（懒创建后复用），
 * 也没有任何调用方会 {@code invalidate()} 查询结果，因此这个 {@link LazyOptional}
 * 可以安全地长期持有：查询方看到的 {@code isPresent()/orElse()/ifPresent()} 语义完全不变。
 *
 * <p>本类不依赖 Minecraft，可单独测试。
 */
final class CapabilityOptionalCache<T> {
    private volatile LazyOptional<T> optional = LazyOptional.empty();

    /** 尚未安装 supplier 时返回 {@link LazyOptional#empty()}，与「capability 不存在」一致。 */
    LazyOptional<T> get() {
        return optional;
    }

    /** 安装（或替换）supplier；之后 {@link #get()} 恒返回同一个实例。 */
    void install(NonNullSupplier<T> supplier) {
        optional = LazyOptional.of(supplier);
    }

    /** 清空缓存，使后续查询重新回到「不存在」。 */
    void clear() {
        optional = LazyOptional.empty();
    }
}
