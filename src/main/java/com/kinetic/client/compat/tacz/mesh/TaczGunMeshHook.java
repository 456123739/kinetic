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
 * <p>烘焙是**分帧**的：每帧只烘一块（AK74M 14 块），一次约几十微秒，玩家不会在第一次掏出某把枪时
 * 感到掉帧。烘焙期间该枪继续走 TaCZ 原路径，画面上没有任何中间状态。
 */
public final class TaczGunMeshHook {
    private static final Map<BedrockGunModel, TaczGunMeshRenderer.BakedMeshes> MESHES = new WeakHashMap<>();
    /** 正在分帧烘焙的枪：每帧只推进一步，期间继续走原路径。 */
    private static final Map<BedrockGunModel, TaczBakedGunMesh.Builder> PENDING = new WeakHashMap<>();
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
            // 没烘完就继续走 TaCZ 原路径；每帧只推进一块，避免"第一次掏枪"掉一帧。
            var builder = PENDING.computeIfAbsent(model, m -> new TaczBakedGunMesh.Builder(m.getShouldRender()));
            if (!builder.bakeNextChunk()) {
                return false;
            }
            PENDING.remove(model);
            var baked = builder.finish();
            if (baked == null) {
                return false;
            }
            meshes = new TaczGunMeshRenderer.BakedMeshes(baked);
            MESHES.put(model, meshes);
            enforceBudget();
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

    /**
     * 显存上限：超过预算就把整张表清掉（连同 VertexBuffer）。
     *
     * <p>不做逐条 LRU 是有意的：枪只有几十把、缓存又只在渲染线程访问，整表清空最多让下一次持枪
     * 重新分帧烘一遍（11~16 帧），比起"为了省几把枪而维护一套淘汰顺序"更不容易出错。
     * 不清空才是真问题——那样每把见过的枪都会把显存一直占着。
     */
    private static void enforceBudget() {
        long used = 0;
        for (var entry : MESHES.values()) {
            used += entry.bytes();
        }
        if (used > TaczGunMeshCache.BUDGET_BYTES) {
            clear();
        }
    }

    public static void clear() {
        for (var entry : MESHES.values()) {
            entry.close();
        }
        MESHES.clear();
        PENDING.clear();
    }

    private static boolean enabled() {
        try {
            return ClientConfig.TACZ_STATIC_MESH != null && ClientConfig.TACZ_STATIC_MESH.get();
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
