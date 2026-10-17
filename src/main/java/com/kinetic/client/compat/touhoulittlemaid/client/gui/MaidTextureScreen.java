package com.kinetic.client.compat.touhoulittlemaid.client.gui;

import com.kinetic.client.compat.touhoulittlemaid.capability.KineticMaidCapabilityProvider;
import com.kinetic.client.event.RegisterEntityRenderersEvent;
import com.kinetic.client.gui.PlayerModelScreen;
import com.kinetic.client.gui.PlayerTextureScreen;
import com.kinetic.model.resource.client.ModelRenderTarget;
import com.kinetic.model.domain.Hash256;
import com.kinetic.util.NameUtil;
import com.kinetic.util.RenderUtil;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.network.NetworkHandler;
import com.github.tartaricacid.touhoulittlemaid.network.message.YsmMaidModelMessage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import org.jetbrains.annotations.Nullable;

public class MaidTextureScreen extends PlayerTextureScreen {
    private final EntityMaid maid;

    public MaidTextureScreen(PlayerModelScreen parent, Hash256 modelHash, ModelRenderTarget model, EntityMaid maid) {
        super(parent, modelHash, model);
        this.maid = maid;
    }

    @Override
    protected void selectTexture(Hash256 hash, String path, String texture, @Nullable ModelRenderTarget renderTarget) {
        previewEntity.updateModelAndTexture(hash, texture);
        var name = NameUtil.getModeName(renderTarget == null ? model : renderTarget, path);
        maid.getCapability(KineticMaidCapabilityProvider.CAP).ifPresent(capability ->
                capability.setYsmModel(path, texture));
        NetworkHandler.CHANNEL.sendToServer(new YsmMaidModelMessage(maid.getId(), path, texture, name));
    }

    @Override
    protected void renderReferenceEntity(GuiGraphics graphics, int scissorX, int scissorY,
                                         int scissorW, int scissorH, float partialTick) {
        RenderSystem.enableScissor(scissorX, scissorY, scissorW, scissorH);
        maid.getCapability(KineticMaidCapabilityProvider.CAP).ifPresent(capability -> {
            var hash = capability.getModelHash();
            if (hash != null) {
                previewEntity.updateModelAndTexture(hash, capability.getTextureName());
            }
            RenderUtil.renderTextureScreenEntity(x + 299 / 2.0F + 40 + posX,
                    y + 235 / 2.0F + 80 + posY, scale, pitch, yaw, partialTick,
                    previewEntity, RegisterEntityRenderersEvent.getPlayerRenderer(), showGround);
        });
        RenderSystem.disableScissor();
    }
}
