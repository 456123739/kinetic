package com.kinetic.client.compat.quickskin;

import com.kinetic.Kinetic;
import com.kinetic.capability.SkinVariantInvalidation;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 真正接触 Quick Skin 的代码：订阅它的外观更新事件，把皮肤变化转成 KINETIC 的变体失效标记。
 *
 * <p><b>为什么用反射</b>：Quick Skin 是可选依赖，仓库里没有它的 compile-only 制品，也不应该为它新增
 * 依赖。反射可以做到零编译期依赖，并在对方版本变动、类名或方法缺失时安全降级（只损失事件驱动，
 * 每个玩家仍会在能力创建/模型就绪时判定一次）。
 *
 * <p><b>订阅的是什么</b>：{@code com.quickskin.mod.common.event.InternalEventBus}（Quick Skin 自己的
 * 服务间事件总线，公开类 + 公开 {@code register(Class, Consumer)}）。{@code PlayerAppearanceUpdateEvent}
 * 由 {@code PlayerAppearanceService#applyLook} 在客户端线程发布，携带玩家 UUID。
 *
 * <p>本类只会在 {@link QuickSkinCompat#init()} 确认 Quick Skin 已安装后被加载。
 */
final class QuickSkinCompatInner {
    private static final String EVENT_BUS_CLASS = "com.quickskin.mod.common.event.InternalEventBus";
    private static final String APPEARANCE_EVENT_CLASS =
            "com.quickskin.mod.common.event.PlayerAppearanceUpdateEvent";

    private static volatile Method playerIdAccessor;

    private QuickSkinCompatInner() {
    }

    static void register() {
        try {
            Class<?> busClass = Class.forName(EVENT_BUS_CLASS);
            Object bus = busClass.getMethod("getInstance").invoke(null);
            Class<?> eventClass = Class.forName(APPEARANCE_EVENT_CLASS);
            playerIdAccessor = eventClass.getMethod("playerId");
            Consumer<Object> listener = event -> {
                UUID playerId = readPlayerId(event);
                if (playerId != null) {
                    SkinVariantInvalidation.INSTANCE.markDirty(playerId);
                }
            };
            busClass.getMethod("register", Class.class, Consumer.class)
                    .invoke(bus, eventClass, listener);
            Kinetic.LOGGER.info(
                    "Quick Skin compat enabled: builtin player variant follows skin changes");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            playerIdAccessor = null;
            Kinetic.LOGGER.warn("Quick Skin is present but its appearance event could not be "
                    + "subscribed; builtin player variants are evaluated once per player instead", failure);
        }
    }

    private static UUID readPlayerId(Object event) {
        try {
            Method accessor = playerIdAccessor;
            return accessor == null ? null : (UUID) accessor.invoke(event);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }
}
