package com.kinetic.client.compat.tacz.mesh;

import com.tacz.guns.client.model.bedrock.BedrockModel;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 静态烘焙结果的缓存：按模型对象身份索引，按字节预算淘汰。
 *
 * <p>为什么要预算：自带枪包 71 把枪平均 1.6 万顶点、烘焙后约 520 KB/把，全包约 37 MB。
 * 第一人称同屏通常只有 1~2 把枪，所以把上限压在 {@link #BUDGET_BYTES}、按 LRU 淘汰就够了；
 * 千万不能"载入枪包时全部烘焙"，那是几十 MB 的常驻内存加一次明显的启动/进服卡顿。
 *
 * <p>用 {@link WeakHashMap} 做身份表，避免持有已经卸载的模型（换资源包、退世界）造成泄漏。
 */
public final class TaczGunMeshCache {
    /** 烘焙顶点预算：约 20 把 AK74M 的量级，仍远低于"全包 37 MB"。 */
    public static final long BUDGET_BYTES = 10L * 1024 * 1024;

    private static final Map<BedrockModel, TaczBakedGunMesh> MESHES = new WeakHashMap<>();
    private static final LinkedHashMap<BedrockModel, Long> ORDER =
            new LinkedHashMap<>(16, 0.75f, true);
    private static long usedBytes;

    private TaczGunMeshCache() {
    }

    public static synchronized TaczBakedGunMesh get(BedrockModel model) {
        return MESHES.get(model);
    }

    public static synchronized void put(BedrockModel model, TaczBakedGunMesh mesh) {
        long bytes = mesh.bytes();
        if (bytes > BUDGET_BYTES) {
            return;
        }
        TaczBakedGunMesh previous = MESHES.put(model, mesh);
        if (previous != null) {
            usedBytes -= previous.bytes();
        }
        ORDER.put(model, bytes);
        usedBytes += bytes;
        evict();
    }

    public static synchronized void clear() {
        MESHES.clear();
        ORDER.clear();
        usedBytes = 0;
    }

    public static synchronized long usedBytes() {
        return usedBytes;
    }

    private static void evict() {
        var iterator = ORDER.entrySet().iterator();
        while (usedBytes > BUDGET_BYTES && iterator.hasNext()) {
            var entry = iterator.next();
            iterator.remove();
            if (MESHES.remove(entry.getKey()) != null) {
                usedBytes -= entry.getValue();
            }
        }
    }
}
