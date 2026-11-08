package com.kinetic.natives.render;

/**
 * {@code BufferBuilder} 预留容量的算术。
 *
 * <p>这里存在的唯一理由是一个真实崩溃：{@code BufferBuilder.ensureCapacity(int)} 的参数是
 * <b>字节</b>（原版 {@code ensureVertexCapacity()} 传的就是 {@code format.getVertexSize()}），
 * 而 {@code kinetic$reserve} 曾经把<b>顶点数</b>直接传进去，少乘了每个顶点的步长。于是缓冲不会扩容，
 * 紧接着的 {@code ByteBuffer.slice(nextElementByte, vertexCount * vertexSize)} 越界：
 *
 * <pre>
 * java.lang.IndexOutOfBoundsException: Range [0, 0 + 20304) out of bounds for length 1536
 *   at java.nio.DirectByteBuffer.slice
 *   at com.mojang.blaze3d.vertex.BufferBuilder.kinetic$reserve
 *   at com.kinetic.natives.render.NativeRenderer.probeVertexConsumer
 * </pre>
 *
 * <p>逻辑抽成纯函数，才能在没有客户端的条件下把"字节 vs 顶点数"这件事钉住。
 */
public final class BufferReservation {
    private BufferReservation() {
    }

    /**
     * 顶点数换算成需要的字节数。
     *
     * @return 需要的字节数；参数非正或超出 int 范围时返回 {@code -1}（调用方必须退回回退写入）
     */
    public static long bytesFor(int vertexCount, int vertexSize) {
        if (vertexCount <= 0 || vertexSize <= 0) {
            return -1L;
        }
        long bytes = (long) vertexCount * (long) vertexSize;
        return bytes > Integer.MAX_VALUE ? -1L : bytes;
    }

    /**
     * 扩容之后能否安全地 {@code slice}。
     *
     * <p>即使 {@code ensureCapacity} 已经被正确调用，也要再核对一次：缓冲区可能被其它 mixin
     * 换成固定大小，扩容未必生效。核对失败就返回 {@code false}，让上层走回退写入，而不是抛异常。
     */
    public static boolean fits(long nextElementByte, long neededBytes, long capacity) {
        return neededBytes > 0 && nextElementByte >= 0 && nextElementByte + neededBytes <= capacity;
    }
}
