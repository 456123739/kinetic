package com.kinetic.capability;

import net.minecraft.client.player.AbstractClientPlayer;
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
public class PlayerAnimatableCapabilityProvider implements ICapabilityProvider {
    public static Capability<PlayerAnimatableCapability> CAP = CapabilityManager.get(new CapabilityToken<>() {
    });
    private PlayerAnimatableCapability instance;
    private AbstractClientPlayer player;
    /**
     * capability 查询在每个渲染帧对每个玩家都会发生，而 Forge 的惯例是每次新建 LazyOptional
     * （见 {@link CapabilityOptionalCache}）。玩家能力实例在实体存活期间是稳定的，因此只建一次。
     */
    private final CapabilityOptionalCache<PlayerAnimatableCapability> optionalCache =
            new CapabilityOptionalCache<>();

    public PlayerAnimatableCapabilityProvider(AbstractClientPlayer player) {
        this.player = player;
        optionalCache.install(this::createCapability);
    }

    @NotNull
    @Override
    public <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        return getCapability(cap);
    }

    @NotNull
    @Override
    public <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap) {
        return CAP.orEmpty(cap, optionalCache.get());
    }

    @NotNull
    private PlayerAnimatableCapability createCapability() {
        if (instance == null) {
            this.instance = new PlayerAnimatableCapability(player);
            player = null;
        }
        return instance;
    }
}
