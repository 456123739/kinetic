package com.kinetic.capability;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
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
public class VehicleAnimatableCapabilityProvider implements ICapabilityProvider {
    public static Capability<VehicleAnimatableCapability> CAP = CapabilityManager.get(new CapabilityToken<>() {
    });
    private VehicleAnimatableCapability instance;
    private Entity entity;
    /**
     * capability 查询在每个渲染帧对每个载具都会发生，而 Forge 的惯例是每次新建 LazyOptional
     * （见 {@link CapabilityOptionalCache}）。能力实例在实体存活期间稳定，因此只在 initialize()
     * 时建一次。
     */
    private final CapabilityOptionalCache<VehicleAnimatableCapability> optionalCache =
            new CapabilityOptionalCache<>();

    public VehicleAnimatableCapabilityProvider(Entity entity) {
        this.entity = entity;
    }

    public VehicleAnimatableCapability initialize() {
        if (this.instance == null) {
            this.instance = new VehicleAnimatableCapability(entity);
            this.entity = null;
            optionalCache.install(() -> instance);
        }
        return instance;
    }

    @Override
    public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        return CAP.orEmpty(cap, optionalCache.get());
    }
}
