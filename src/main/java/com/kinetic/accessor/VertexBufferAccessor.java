package com.kinetic.accessor;

import com.kinetic.buffer.NativeBuffer;
import com.kinetic.buffer.annotation.Borrowed;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.jetbrains.annotations.Nullable;

public interface VertexBufferAccessor {
    boolean kinetic$ok();

    VertexFormat kinetic$vertexFormat();

    /**
     * 预留 {@code maxVertexCount} 个顶点的写入空间。
     *
     * @return 可直接写入的区域；缓冲区放不下时返回 {@code null}，调用方必须改用回退写入
     */
    @Nullable
    @Borrowed
    NativeBuffer kinetic$reserve(int maxVertexCount);

    void kinetic$advance(int actualVertexCount);
}
