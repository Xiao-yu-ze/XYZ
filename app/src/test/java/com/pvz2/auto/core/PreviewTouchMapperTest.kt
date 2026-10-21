package com.pvz2.auto.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 预览触点 → 虚拟屏坐标的换算。
 *
 * 这个换算错了，用户点"登录"会点到别处，而且很难从现象反推是坐标问题
 * （看起来就像"点了没反应"）。所以按 fitCenter 的几何逐种情形钉死。
 */
class PreviewTouchMapperTest {

    private fun map(
        vx: Float, vy: Float,
        vw: Int, vh: Int,
        iw: Int = 1280, ih: Int = 720
    ) = PreviewTouchMapper.map(vx, vy, vw, vh, iw, ih)

    @Test
    fun `视图与画面同比例时是一比一`() {
        // 640x360 的控件装 1280x720，正好 0.5 倍，无留边。
        assertEquals(PreviewTouchMapper.Point(640, 360), map(320f, 180f, 640, 360))
        assertEquals(PreviewTouchMapper.Point(0, 0), map(0f, 0f, 640, 360))
        assertEquals(PreviewTouchMapper.Point(1279, 719), map(639.5f, 359.5f, 640, 360))
    }

    @Test
    fun `视图更宽时左右留边_中心仍然对齐`() {
        // 1000x720 装 16:9：scale = min(1000/1280, 720/720) = 0.78125
        // drawnW = 1000, offsetX = 0（宽度先触顶）
        // 换成高先触顶的情形：800x500 装 16:9 → scale=min(0.625,0.694)=0.625
        // drawnW=800, drawnH=450, offsetY=(500-450)/2=25
        val p = map(400f, 250f, 800, 500)
        assertEquals(PreviewTouchMapper.Point(640, 360), p)
    }

    @Test
    fun `点在上下留边上返回 null`() {
        // 800x500，画面实际只占 y ∈ [25, 475]
        assertNull(map(400f, 10f, 800, 500))
        assertNull(map(400f, 490f, 800, 500))
    }

    @Test
    fun `点在左右留边上返回 null`() {
        // 1600x720 装 16:9：scale=min(1.25,1.0)=1.0 → drawnW=1280, offsetX=160
        assertNull(map(100f, 360f, 1600, 720))
        assertNull(map(1500f, 360f, 1600, 720))
        // 正中间应该换算到 640
        assertEquals(PreviewTouchMapper.Point(640, 360), map(800f, 360f, 1600, 720))
    }

    @Test
    fun `数值退化时返回 null 而不是崩`() {
        assertNull(map(0f, 0f, 0, 0))
        assertNull(map(0f, 0f, -1, 100))
        assertNull(map(0f, 0f, 100, 100, 0, 720))
        assertNull(map(0f, 0f, 100, 100, 1280, -1))
    }

    @Test
    fun `浮点误差不会算出越界坐标`() {
        // 右下角边界值：imageX 可能算成 1280.0，必须夹到 1279
        val p = map(640f, 360f, 640, 360)
        assertEquals(PreviewTouchMapper.Point(1279, 719), p)
    }
}
