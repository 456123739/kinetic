package com.kinetic.mixin.client.embeddium;

import com.kinetic.accessor.VertexBufferAccessor;
import com.kinetic.buffer.NativeBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import me.jellysquid.mods.sodium.client.render.vertex.buffer.ExtendedBufferBuilder;
import me.jellysquid.mods.sodium.client.render.vertex.buffer.SodiumBufferBuilder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = SodiumBufferBuilder.class, remap = false)
public class SodiumBufferBuilderMixin implements VertexBufferAccessor {
    @Shadow
    @Final
    private ExtendedBufferBuilder builder;

    @Unique
    @Override
    public boolean kinetic$ok() {
        return builder instanceof VertexBufferAccessor;
    }

    @Unique
    @Override
    public VertexFormat kinetic$vertexFormat() {
        return ((VertexBufferAccessor) builder).kinetic$vertexFormat();
    }

    @Unique
    @Override
    @Nullable
    public NativeBuffer kinetic$reserve(int vertexCount) {
        return ((VertexBufferAccessor) builder).kinetic$reserve(vertexCount);
    }

    @Unique
    @Override
    public void kinetic$advance(int vertexCount) {
        ((VertexBufferAccessor) builder).kinetic$advance(vertexCount);
    }
}
