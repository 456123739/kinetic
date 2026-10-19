package com.kinetic.client.compat.tacz.lod;

/**
 * 距离 LOD 的判定。
 *
 * <p>纯计算、<b>不分配</b>、不开方：渲染路径每帧每物品都会调用它，所以只做平方比较。
 * 相机位置用 {@code Camera.getPosition()} 的 x/y/z 三个 double 传入，避免为了调用它而构造 {@code Vec3}。
 *
 * <p>约定：{@code limit <= 0} 表示"不启用该 LOD"（由调用方决定语义），此时一律返回 false。
 */
public final class GunLodDistance {
    private GunLodDistance() {
    }

    /**
     * 目标点是否已经超出以相机为中心的 {@code limit} 格球体。
     *
     * @param limit  距离上限（格）；{@code <= 0} 时返回 false
     * @param camX   相机 x
     * @param camY   相机 y
     * @param camZ   相机 z
     * @param targetX 目标 x
     * @param targetY 目标 y
     * @param targetZ 目标 z
     * @return 超出为 true
     */
    public static boolean isBeyond(int limit,
                                   double camX, double camY, double camZ,
                                   double targetX, double targetY, double targetZ) {
        if (limit <= 0) {
            return false;
        }
        var dx = targetX - camX;
        var dy = targetY - camY;
        var dz = targetZ - camZ;
        var limitSquared = (double) limit * limit;
        return dx * dx + dy * dy + dz * dz > limitSquared;
    }

    /**
     * 物品 LOD 是否生效。与 {@link #isBeyond} 的区别只有 {@code limit <= 0} 的语义：
     * 物品 LOD 里 {@code 0} 表示"其他玩家的枪一直用简化模型"，所以此时返回 true。
     *
     * <p>不分配、不开方。
     */
    public static boolean useItemLod(int limit,
                                     double camX, double camY, double camZ,
                                     double targetX, double targetY, double targetZ) {
        return limit <= 0 || isBeyond(limit, camX, camY, camZ, targetX, targetY, targetZ);
    }
}
