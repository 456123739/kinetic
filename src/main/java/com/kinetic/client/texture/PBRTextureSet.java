package com.kinetic.client.texture;

import com.kinetic.info.type.PBRTextureType;
import net.minecraft.client.renderer.texture.AbstractTexture;

import java.util.Map;

public interface PBRTextureSet {
    Map<PBRTextureType, ? extends AbstractTexture> getPBRTextures();
}
