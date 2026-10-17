package com.kinetic.client.compat.realcamera;

import com.kinetic.Kinetic;
import net.minecraftforge.fml.ModList;

/**
 * 真实相机（Real Camera）兼容的对外门面。
 *
 * <p>本类刻意<b>不引用真实相机的任何类型</b>：未安装时 {@link #INSTALLED} 为 {@code false}，
 * {@link RealCameraCompatInner} / {@link RealCameraBindingInner} 永远不会被加载，因此也不会因为
 * 对方缺席而在类加载阶段炸掉。
 *
 * <p>{@link #isActive()} 表示真实相机当前是否在接管相机。KINETIC 用它决定「第一人称要不要画自己的
 * 身体」，因为这个模组需要看得见身体。
 *
 * <p>绑定解算是否由 KINETIC 接管由初始化日志报告，不额外暴露状态查询：接管失败会自动退回对方自己的
 * 路径，调用方没有任何需要据此分叉的行为。
 */
public class RealCameraCompat {
    private static final String MOD_ID = "realcamera";

    private static boolean INSTALLED = false;
    private static boolean REGISTERED = false;

    public static void init() {
        INSTALLED = ModList.get().isLoaded(MOD_ID);
        if (!INSTALLED || REGISTERED) {
            return;
        }
        try {
            RealCameraBindingInner.register();
            REGISTERED = true;
            Kinetic.LOGGER.info("Real Camera compat: camera binding is resolved from the KINETIC "
                    + "model's baked geometry, so Real Camera no longer re-renders the model to find it");
        } catch (Throwable failure) {
            // 对方换了 API：退回它自己的顶点捕获路径。绑定仍然可用，只是更贵、且在模型本帧没被画出来
            // 的时候会失败。绝不能因为兼容层初始化失败把客户端带下去。
            Kinetic.LOGGER.warn("Real Camera is present but its binding API could not be used; "
                    + "KINETIC falls back to Real Camera's own model scan", failure);
        }
    }

    /** 真实相机当前是否在接管相机（第一人称 + 已启用 + 未被其它模组禁用）。 */
    public static boolean isActive() {
        if (INSTALLED) {
            return RealCameraCompatInner.isActive();
        }
        return false;
    }
}
