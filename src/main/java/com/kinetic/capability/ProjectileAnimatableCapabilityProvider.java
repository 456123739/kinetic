package com.kinetic.capability;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@OnlyIn(Dist.CLIENT)
public class ProjectileAnimatableCapabilityProvider implements ICapabilityProvider {
    public static Capability<ProjectileAnimatableCapability> CAP = CapabilityManager.get(new CapabilityToken<>() {
    });
    private ProjectileAnimatableCapability instance;
    private Projectile projectile;
    /**
     * capability 查询在每个渲染帧对每个投射物都会发生，而 Forge 的惯例是每次新建 LazyOptional
     * （见 {@link CapabilityOptionalCache}）。能力实例在实体存活期间稳定，因此只在 initialize()
     * 时建一次。
     */
    private final CapabilityOptionalCache<ProjectileAnimatableCapability> optionalCache =
            new CapabilityOptionalCache<>();

    public ProjectileAnimatableCapabilityProvider(Projectile projectile) {
        this.projectile = projectile;
    }

    public ProjectileAnimatableCapability initialize() {
        if (this.instance == null) {
            this.instance = new ProjectileAnimatableCapability(projectile);
            this.projectile = null;
            optionalCache.install(() -> instance);
        }
        return this.instance;
    }

    @Override
    public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        return CAP.orEmpty(cap, optionalCache.get());
    }
}
