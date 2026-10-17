package com.kinetic.client.compat.tacz.lod;

import com.kinetic.model.domain.Hash256;
import com.kinetic.model.storage.ModelHashing;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import net.minecraft.resources.ResourceLocation;

/**
 * 枪械 LOD 在 {@code BakedModelCache} 里的键。
 *
 * <p>磁盘缓存命中与否完全由这个键决定，所以键里必须包含<b>一切会改变烘焙产物</b>的输入：
 *
 * <ul>
 *   <li><b>枪械 id</b>（进 {@code containerId}）：不同枪 = 不同模型。</li>
 *   <li><b>贴图内容哈希</b>：由调用方算好传进来。枪包换一张同名贴图必须让缓存失效，
 *       所以用内容哈希而不是路径。</li>
 *   <li><b>减面参数</b>：体量阈值与"是否跳过配件"直接改变几何，必须进键，
 *       否则玩家改了配置却拿到旧模型。</li>
 *   <li><b>减面规则版本</b>（{@link GunLodDecimator#RULE_REVISION}）：折叠阈值、网格阶梯、
 *       面级剔除算法这些代码常量的改动不体现在任何输入里，只能靠人手递增这个版本号。</li>
 *   <li><b>缓存 ABI 与原生能力</b>：由 {@code BakedModelCache} 自己的目录层级承担
 *       （{@code CACHE_ABI}/{@code capabilityKey}），不需要在这里重复。</li>
 * </ul>
 *
 * <p>本类不引用任何 TaCZ 类型，也不做 IO，可以离线单测。
 */
public final class GunLodCacheKey {
    /** 域分隔符：避免不同用途的哈希在同一个 256 位空间里相互碰撞。 */
    private static final byte[] CONTAINER_DOMAIN =
            "kinetic.tacz-gun-lod-container\0".getBytes(StandardCharsets.UTF_8);

    private GunLodCacheKey() {
    }

    /**
     * 枪械 id + <b>模型内容哈希</b> → 缓存容器 id（在磁盘上是一个目录名）。
     *
     * <p>模型内容必须进键，不能只用枪 id：枪包更新是<b>就地改</b> {@code geo_models/**} 里的模型
     * 文件，枪 id、模型路径、贴图路径与贴图内容全都不变。少了模型内容，更新枪包后 LOD 会继续
     * 用旧几何，与 TaCZ 的完整模型对不上——近处看是新的、远处看是旧的。
     *
     * @param modelHash 模型内容的哈希，由调用方读出（见 {@code TaczGunLodBuilder.modelIdentity}）
     */
    public static Hash256 containerId(ResourceLocation gunId, byte[] modelHash) {
        var name = gunId.toString().getBytes(StandardCharsets.UTF_8);
        var input = ByteBuffer.allocate(
                CONTAINER_DOMAIN.length + Integer.BYTES + name.length
                        + Integer.BYTES + modelHash.length);
        input.put(CONTAINER_DOMAIN).putInt(name.length).put(name)
                .putInt(modelHash.length).put(modelHash);
        return ModelHashing.blake3(input.array());
    }

    /**
     * 减面参数 → 资源名（只参与哈希，不会成为文件名）。
     *
     * <p>用 {@link Float#toString} 而不是默认的十进制格式化：它是往返精确的，
     * 配置值 3.0 与 3.0000001 会得到不同的键，正是我们要的。
     */
    public static String resourceName(float minBoneMetric, boolean skipAttachments) {
        return "tacz-gun-lod/r" + GunLodDecimator.RULE_REVISION
                + "/uv" + GunLodFactory.RAW_UV_VERSION
                + "/m" + Float.toString(minBoneMetric)
                + "/skip" + (skipAttachments ? 1 : 0);
    }
}
