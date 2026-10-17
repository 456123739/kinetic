package com.kinetic.client.compat.realcamera;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CameraBindGeometry} 的纯几何行为：UV 命中、面内插值、法线、退化输入。
 *
 * <p>这些是相机绑定的地基：只要 UV → 面的映射偏了，相机就会贴在一个和模型不符的位置上，而这种
 * 偏差在实机上很难和「偏移量配错了」区分开，所以必须在这里钉住。
 */
class CameraBindGeometryTest {
    /** 一个 1×1 的正方形面，UV 与位置一一对应；用于验证插值与法线。 */
    private static CameraBindGeometry.Quad square(int bone) {
        return new CameraBindGeometry.Quad(
                new float[]{0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f},
                new float[]{0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f},
                0f, 0f, 1f, bone);
    }

    @Test
    void findsTheQuadContainingTheUv() {
        var geometry = CameraBindGeometry.of(new CameraBindGeometry.Quad[]{square(0)});
        assertEquals(0, geometry.findQuad(0.5f, 0.5f));
        assertEquals(0, geometry.findQuad(0.01f, 0.99f));
        assertEquals(-1, geometry.findQuad(1.5f, 0.5f));
        assertEquals(-1, geometry.findQuad(0.5f, -0.01f));
    }

    @Test
    void countsTheBoundaryAsInside() {
        // 用户在模型视图界面里按像素点选，落点常常正好压在面的边上；真实相机自己那份实现
        // （java.awt.Polygon 含边界）会命中，这里也必须命中，否则同一个配置在两边表现不同。
        var geometry = CameraBindGeometry.of(new CameraBindGeometry.Quad[]{square(0)});
        assertEquals(0, geometry.findQuad(0f, 0f));
        assertEquals(0, geometry.findQuad(1f, 1f));
        assertEquals(0, geometry.findQuad(0.5f, 0f));
        assertEquals(0, geometry.findQuad(1f, 0.5f));
    }

    @Test
    void returnsTheFirstMatchingQuadSoTheChoiceIsStable() {
        // 模型重复使用同一块贴图区域时命中哪个面必须确定，否则相机会在两帧之间跳面。
        var first = square(3);
        var second = square(7);
        var geometry = CameraBindGeometry.of(new CameraBindGeometry.Quad[]{first, second});
        assertEquals(0, geometry.findQuad(0.5f, 0.5f));
        assertEquals(3, geometry.quad(0).boneIndex());
    }

    @Test
    void interpolatesThePositionInsideTheQuad() {
        var geometry = CameraBindGeometry.of(new CameraBindGeometry.Quad[]{square(0)});
        var quad = geometry.quad(0);
        var dest = new Vector3f();

        assertNotNull(geometry.positionAt(quad, 0.5f, 0.5f, dest));
        assertEquals(0.5f, dest.x, 1.0e-5f);
        assertEquals(0.5f, dest.y, 1.0e-5f);

        assertNotNull(geometry.positionAt(quad, 0.25f, 0.75f, dest));
        assertEquals(0.25f, dest.x, 1.0e-5f);
        assertEquals(0.75f, dest.y, 1.0e-5f);

        // 角点必须精确落回角点。
        assertNotNull(geometry.positionAt(quad, 1f, 1f, dest));
        assertEquals(1f, dest.x, 1.0e-5f);
        assertEquals(1f, dest.y, 1.0e-5f);
    }

    @Test
    void computesTheGeometricNormal() {
        var geometry = CameraBindGeometry.of(new CameraBindGeometry.Quad[]{square(0)});
        var dest = new Vector3f();
        assertNotNull(geometry.normalAt(geometry.quad(0), dest));
        assertEquals(0f, dest.x, 1.0e-6f);
        assertEquals(0f, dest.y, 1.0e-6f);
        assertEquals(1f, dest.z, 1.0e-6f);
    }

    @Test
    void rejectsDegenerateGeometryInsteadOfReturningNaN() {
        // 位置全塌成一条线：法线求不出来，必须显式失败，不能把 NaN 交给真实相机
        //（它的 BindResult#setForward 会归一化出 NaN，进而毁掉相机的旋转矩阵）。
        var flat = new CameraBindGeometry.Quad(
                new float[]{0f, 0f, 0f, 1f, 0f, 0f, 2f, 0f, 0f, 3f, 0f, 0f},
                new float[]{0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f},
                0f, 1f, 0f, 0);
        var geometry = CameraBindGeometry.of(new CameraBindGeometry.Quad[]{flat});
        assertNull(geometry.normalAt(flat, new Vector3f()));
    }

    @Test
    void stillHitsAQuadWhoseUvAreaCollapsedToASegment() {
        // 贴图上一块被压成一条线的区域（uv_size 有 0 分量）在基岩模型里是合法的。
        // 点在线上应当算命中，否则这类模型的绑定会整片失效。
        var line = new CameraBindGeometry.Quad(
                new float[]{0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f},
                new float[]{0.5f, 0f, 0.5f, 0f, 0.5f, 1f, 0.5f, 1f},
                0f, 0f, 1f, 0);
        var geometry = CameraBindGeometry.of(new CameraBindGeometry.Quad[]{line});
        assertEquals(0, geometry.findQuad(0.5f, 0.5f));
        assertEquals(-1, geometry.findQuad(0.6f, 0.5f));
    }

    @Test
    void exposesTheQuadsItWasGiven() {
        var quad = square(2);
        var geometry = CameraBindGeometry.of(new CameraBindGeometry.Quad[]{quad});
        assertEquals(1, geometry.quadCount());
        assertSame(quad, geometry.quad(0));
        assertTrue(geometry.quad(0).positions().length == 12);
        assertTrue(geometry.quad(0).uvs().length == 8);
    }
}
