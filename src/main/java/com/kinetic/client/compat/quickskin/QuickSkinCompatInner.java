package com.kinetic.client.compat.quickskin;

import com.kinetic.Kinetic;
import com.kinetic.capability.SkinVariantInvalidation;

import org.jetbrains.annotations.Nullable;

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
    /** Quick Skin 的外观服务：它自己记着"这个 uuid 现在该用哪个模型"，比 mixin 改写后的 getSkin 更早可用。 */
    private static final String APPEARANCE_SERVICE_CLASS =
            "com.quickskin.mod.client.services.PlayerAppearanceService";

    /** Quick Skin 的客户端配置：玩家在标题界面选好的皮肤就存在这里（进世界前就有）。 */
    private static final String CONFIG_CLASS = "com.quickskin.mod.config.ClientConfig";
    /** 本地资源管理器：按皮肤哈希查"细手臂/宽手臂/自动"。 */
    private static final String ASSET_CLASS = "com.quickskin.mod.client.services.LocalAssetManager";

    private static volatile Method playerIdAccessor;
    private static volatile Object appearanceService;
    private static volatile Method modelNameAccessor;
    private static volatile Object clientConfig;
    private static volatile java.lang.reflect.Field activeSkinHashField;
    private static volatile Object assetManager;
    private static volatile Method skinModelPreference;
    private static volatile Method metadataOf;

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
        bindAppearanceService();
        bindSavedSkinPreference();
    }

    /**
     * 绑定 Quick Skin 的外观服务，用来在事件之前就读到"该玩家现在用哪个模型"。
     *
     * <p>为什么需要它：Quick Skin 在 {@code CLIENT_PLAYER_JOIN} 里才把保存的皮肤应用到玩家身上，
     * 而 KINETIC 的变体判定可能在那一刻之前就跑过一次。只依赖事件的话，那一次拿到的还是原版
     * 皮肤模型名（宽手臂），要等事件回来才纠正——玩家看到的就是"进世界后得再切一次"。
     * 直接问服务要答案，进世界第一帧就是对的。
     *
     * <p>失败只影响这条快路径，事件驱动仍然有效，所以单独 try。
     */
    private static void bindAppearanceService() {
        try {
            Class<?> serviceClass = Class.forName(APPEARANCE_SERVICE_CLASS);
            appearanceService = serviceClass.getMethod("getInstance").invoke(null);
            modelNameAccessor = serviceClass.getMethod("getModelName", UUID.class);
            Kinetic.LOGGER.info("Quick Skin compat: appearance service bound "
                    + "(builtin player variant follows the saved skin from the first frame)");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            appearanceService = null;
            modelNameAccessor = null;
            Kinetic.LOGGER.warn("Quick Skin is present but its appearance service could not be bound; "
                    + "builtin player variants stay event-driven", failure);
        }
    }

    /**
     * 绑定"进入世界前就已经存在的预设"：标题界面选好的皮肤哈希 + 它的粗细偏好。
     *
     * <p>这条比外观服务更早可用——Quick Skin 的外观光靠 {@code applyLook} 填充，而 {@code applyLook}
     * 要等到 {@code CLIENT_PLAYER_JOIN}。玩家在标题界面把皮肤设成细手臂后进世界，第一帧就能读到。
     */
    private static void bindSavedSkinPreference() {
        try {
            Class<?> configClass = Class.forName(CONFIG_CLASS);
            // activeSkinHash 是实例字段（Quick Skin 自己的 mixin 也是先 getInstance 再读）
            clientConfig = configClass.getMethod("getInstance").invoke(null);
            activeSkinHashField = configClass.getField("activeSkinHash");
            Class<?> assetClass = Class.forName(ASSET_CLASS);
            assetManager = assetClass.getMethod("getInstance").invoke(null);
            skinModelPreference = assetClass.getMethod("getSkinModelPreference", String.class);
            metadataOf = assetClass.getMethod("getMetadata", String.class);
            Kinetic.LOGGER.info("Quick Skin compat: saved skin preference bound "
                    + "(title-screen choice applies on the first frame in-world)");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            clientConfig = null;
            activeSkinHashField = null;
            assetManager = null;
            skinModelPreference = null;
            metadataOf = null;
            Kinetic.LOGGER.warn("Quick Skin is present but its saved skin preference could not be bound; "
                    + "the title-screen choice will only take effect once Quick Skin applies it", failure);
        }
    }

    /**
     * 标题界面里选好的皮肤模型名（{@code "slim"} / {@code "classic"}），{@code "auto"} 会解析成元数据里的值。
     *
     * @return 没选皮肤、未绑定或反射失败时 {@code null}
     */
    @Nullable
    static String savedModelName() {
        java.lang.reflect.Field field = activeSkinHashField;
        Object config = clientConfig;
        Object manager = assetManager;
        Method preference = skinModelPreference;
        if (field == null || config == null || manager == null || preference == null) {
            return null;
        }
        try {
            Object hash = field.get(config);
            if (!(hash instanceof String id) || id.isEmpty()) {
                return null;
            }
            Object model = preference.invoke(manager, id);
            if (!(model instanceof String name) || name.isEmpty()) {
                return null;
            }
            if (!"auto".equalsIgnoreCase(name)) {
                return name;
            }
            Method lookup = metadataOf;
            if (lookup == null) {
                return null;
            }
            Object metadata = lookup.invoke(manager, id);
            if (metadata == null) {
                return null;
            }
            Object resolved = metadata.getClass().getMethod("skinModel").invoke(metadata);
            return resolved instanceof String resolvedName ? resolvedName : null;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    /**
     * Quick Skin 记录的模型名（{@code "classic"} / {@code "slim"} / {@code "auto"}）。
     *
     * @return 未绑定、未安装或反射失败时为 {@code null}
     */
    @Nullable
    static String playerModelName(@Nullable UUID playerId) {
        Method accessor = modelNameAccessor;
        Object service = appearanceService;
        if (accessor == null || service == null || playerId == null) {
            return null;
        }
        try {
            return (String) accessor.invoke(service, playerId);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
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
