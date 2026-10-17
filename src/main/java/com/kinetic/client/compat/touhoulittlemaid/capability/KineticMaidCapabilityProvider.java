package com.kinetic.client.compat.touhoulittlemaid.capability;

import com.kinetic.client.compat.touhoulittlemaid.client.CustomKineticMaidEntity;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.Direction;
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
public class KineticMaidCapabilityProvider implements ICapabilityProvider {
    public static final Capability<CustomKineticMaidEntity> CAP = CapabilityManager.get(new CapabilityToken<>() {
    });

    private CustomKineticMaidEntity instance;
    private final EntityMaid maid;

    public KineticMaidCapabilityProvider(EntityMaid maid) {
        this.maid = maid;
    }

    @Override
    @NotNull
    public <T> LazyOptional<T> getCapability(@NotNull Capability<T> capability, @Nullable Direction direction) {
        return this.getCapability(capability);
    }

    @NotNull
    @Override
    public <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap) {
        return CAP.orEmpty(cap, LazyOptional.of(this::createCapability));
    }

    private CustomKineticMaidEntity createCapability() {
        if (instance == null) {
            instance = new CustomKineticMaidEntity(this.maid, true);
        }
        return instance;
    }
}
