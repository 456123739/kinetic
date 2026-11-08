package com.kinetic.mixin.client;

import com.kinetic.accessor.VertexBufferAccessor;
import com.kinetic.buffer.NativeBuffer;
import com.kinetic.natives.render.BufferReservation;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Unique;

import java.nio.ByteBuffer;

@Mixin(BufferBuilder.class)
public abstract class BufferBuilderMixin implements VertexBufferAccessor {
    @Shadow
    private ByteBuffer buffer;
    @Shadow
    private int nextElementByte;
    @Shadow
    private int vertices;
    @Shadow
    private VertexFormat format;

    @Shadow
    protected abstract void ensureCapacity(int increaseAmount);

    @Unique
    @Override
    public boolean kinetic$ok() {
        return true;
    }

    @Unique
    @Override
    public VertexFormat kinetic$vertexFormat() {
        return format;
    }

    @Unique
    @Override
    @Nullable
    public NativeBuffer kinetic$reserve(int vertexCount) {
        // ensureCapacity 的参数是**字节**（见 BufferReservation 里的崩溃记录）：
        // 传顶点数会让缓冲不扩容，随后 slice 直接越界。
        long needed = BufferReservation.bytesFor(vertexCount, this.format.getVertexSize());
        if (needed < 0L) {
            return null;
        }
        ensureCapacity((int) needed);
        if (!BufferReservation.fits(this.nextElementByte, needed, this.buffer.capacity())) {
            // 扩容没生效（缓冲区被其它 mixin 换成固定大小）：交给回退写入，绝不在这里抛异常。
            return null;
        }
        return NativeBuffer.borrow(this.buffer.slice(this.nextElementByte, (int) needed));
    }

    @Override
    public void kinetic$advance(int vertexCount) {
        this.vertices += vertexCount;
        this.nextElementByte += vertexCount * this.format.getVertexSize();
    }
}
