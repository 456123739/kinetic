package com.kinetic.client.compat.tacz.mesh;

import com.kinetic.Kinetic;
import com.kinetic.config.ClientConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.client.model.BedrockGunModel;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.renderer.RenderType;

/**
 * TaCZ 渲染入口：命中烘焙就画烘焙块，否则走原路径并顺手把它烘出来。
 *
 * <p>只在 {@code tacz.StaticGunMesh} 打开、且 TaCZ 存在时生效；任何失败都永久回退。
 *
 * <p>烘焙在渲染线程同步完成，代价约 0.3~0.5 ms，只在该枪**第一次出现**时付一次。
 * 这一点是 v1 的已知瑕疵（首次持枪可能掉一帧），后续改成每帧烘一块、把尖峰摊平。
 */
public final class TaczGunMeshHook {
    private static final Map<BedrockGunModel, TaczGunMeshRenderer.BakedMeshes> MESHES = new WeakHashMap<>();
    private static boolean loggedFallback;

    private TaczGunMeshHook() {
    }

    /**
     * @return true 表示这一帧已经由烘焙网格画完，调用方必须取消原渲染
     */
    public static boolean tryRender(BedrockGunModel model, PoseStack pose, RenderType renderType, int light) {
        if (!enabled()) {
            return false;
        }
        var meshes = MESHES.get(model);
        if (meshes == null) {
            var baked = TaczBakedGunMesh.bake(model.getShouldRender());
            if (baked == null) {
                return false;
            }
            meshes = new TaczGunMeshRenderer.BakedMeshes(baked);
            MESHES.put(model, meshes);
        }
        if (!TaczGunMeshRenderer.upload(meshes)) {
            return false;
        }
        boolean drawn = TaczGunMeshRenderer.draw(meshes, pose, light, renderType);
        if (!drawn && !loggedFallback) {
            loggedFallback = true;
            Kinetic.LOGGER.info("[TaCZ] static gun mesh fell back for a frame (animation or an upload failure); "
                    + "the original path keeps rendering");
        }
        return drawn;
    }

    public static void clear() {
        MESHES.clear();
    }

    private static boolean enabled() {
        try {
            return ClientConfig.TACZ_STATIC_MESH != null && ClientConfig.TACZ_STATIC_MESH.get();
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
