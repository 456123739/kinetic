package com.kinetic.accessor;

import com.kinetic.buffer.NativeBuffer;
import com.kinetic.buffer.annotation.Borrowed;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

public interface VertexBufferAccessor {
    boolean kinetic$ok();

    VertexFormat kinetic$vertexFormat();

    @Borrowed
    NativeBuffer kinetic$reserve(int maxVertexCount);

    void kinetic$advance(int actualVertexCount);
}
