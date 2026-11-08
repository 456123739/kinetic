package com.kinetic.natives.render;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code kinetic$reserve} 的容量算术。
 *
 * <p>线上崩溃（{@code IndexOutOfBoundsException: Range [0, 0 + 20304) out of bounds for length 1536}）
 * 的根因是把顶点数当成字节数传给 {@code ensureCapacity}。这里钉住换算与边界，再加一条源码护栏，
 * 防止有人把它改回"直接传顶点数"。
 */
class BufferReservationTest {
    /** DefaultVertexFormat.NEW_ENTITY 的步长。 */
    private static final int NEW_ENTITY_STRIDE = 48;

    @Test
    void convertsVerticesToBytes() {
        assertEquals(20304L, BufferReservation.bytesFor(423, NEW_ENTITY_STRIDE));
        assertEquals(48L, BufferReservation.bytesFor(1, NEW_ENTITY_STRIDE));
        // 崩溃里的实际数字：423 个顶点 = 20304 字节，远大于 1536 字节的初始缓冲
        assertFalse(BufferReservation.fits(0, 20304L, 1536L),
                "旧写法（传顶点数 423）会让 1536 字节的缓冲通过检查，随后 slice 越界");
        assertTrue(BufferReservation.fits(0, 48L * 32, 1536L));
    }

    @Test
    void rejectsImpossibleRequests() {
        assertEquals(-1L, BufferReservation.bytesFor(0, NEW_ENTITY_STRIDE));
        assertEquals(-1L, BufferReservation.bytesFor(-5, NEW_ENTITY_STRIDE));
        assertEquals(-1L, BufferReservation.bytesFor(10, 0));
        // 乘法溢出必须被拒绝，而不是回绕成一个"放得下"的小数字
        assertEquals(-1L, BufferReservation.bytesFor(Integer.MAX_VALUE, NEW_ENTITY_STRIDE));
        assertFalse(BufferReservation.fits(0, -1L, 1L << 20));
    }

    @Test
    void fitsChecksTheExactEndOfTheSlice() {
        assertTrue(BufferReservation.fits(100, 200, 300), "刚好写满应当允许");
        assertFalse(BufferReservation.fits(100, 201, 300), "超出 1 字节就必须拒绝");
        assertFalse(BufferReservation.fits(-1, 10, 300));
    }

    @Test
    void reserveMixinStillPassesBytes() throws Exception {
        File source = new File("src/main/java/com/kinetic/mixin/client/BufferBuilderMixin.java");
        assertTrue(source.isFile(), "找不到 " + source.getAbsolutePath());
        String text = Files.readString(source.toPath(), StandardCharsets.UTF_8);
        assertTrue(text.contains("BufferReservation.bytesFor("),
                "kinetic$reserve 必须用 BufferReservation.bytesFor 把顶点数换算成字节");
        assertFalse(text.contains("ensureCapacity(vertexCount)"),
                "不能把顶点数直接传给 ensureCapacity（参数是字节）——这正是线上崩溃的原因");
    }
}
