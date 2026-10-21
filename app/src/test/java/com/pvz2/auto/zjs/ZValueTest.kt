package com.pvz2.auto.zjs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协议地基：`Value` 与 `limitArea`。
 *
 * 这两个东西错了，**上层所有坐标都会偏**，而且往往不是明显偏，
 * 是"点到了按钮旁边 20 像素"这种难查的症状。所以逐条钉死。
 */
class ZValueTest {

    private val w = 1280
    private val h = 720
    private val density = 2.0f

    @Test
    fun `三种单位都能解析`() {
        assertTrue(ZValue.parse("50%") is ZValue.Percent)
        assertTrue(ZValue.parse("50dp") is ZValue.Dp)
        assertTrue(ZValue.parse("50") is ZValue.Px)
    }

    @Test
    fun `没有后缀且不以数字结尾时按百分比处理`() {
        // 协议的默认单位是百分比：初始化 PERCENT，只有匹配到数字结尾才改成 PX
        assertTrue(ZValue.parse("abc") is ZValue.Percent)
    }

    @Test
    fun `百分比按传入的基准换算_x用宽 y用高`() {
        val v = ZValue.parse("50%")
        assertEquals(640f, v.toPx(w, density), 0.01f)   // 宽的一半
        assertEquals(360f, v.toPx(h, density), 0.01f)   // 高的一半
    }

    @Test
    fun `dp 按密度换算`() {
        assertEquals(100f, ZValue.parse("50dp").toPx(w, density), 0.01f)
    }

    @Test
    fun `像素原样返回`() {
        assertEquals(386f, ZValue.parse("386").toPx(w, density), 0.01f)
    }

    @Test
    fun `空值和乱写的退化成 0 而不是崩`() {
        // 宁可点到左上角（一眼看出来没设置），也不要因为一个脏字段闪退
        assertEquals(0f, ZValue.parse(null).toPx(w, density), 0.01f)
        assertEquals(0f, ZValue.parse("").toPx(w, density), 0.01f)
        assertEquals(0f, ZValue.parse("%").toPx(w, density), 0.01f)
    }

    @Test
    fun `区间写法能取到范围内的值`() {
        repeat(20) {
            val px = ZValue.parse("10-20").toPx(w, density)
            assertTrue("取到 $px，应在 10..20", px in 10f..20f)
        }
    }

    @Test
    fun `区域四种写法等价`() {
        val a = ZArea.parse("10% 20% 40% 60%")!!
        assertEquals(128f, a.left.toPx(w, density), 0.5f)
        assertEquals(144f, a.top.toPx(h, density), 0.5f)
        assertEquals(512f, a.right.toPx(w, density), 0.5f)
        assertEquals(432f, a.bottom.toPx(h, density), 0.5f)

        // x1-x2 y1-y2 形式
        val b = ZArea.parse("10%-40% 20%-60%")!!
        assertEquals(128f, b.toPxRect(w, h, density)[0].toFloat(), 1f)
        assertEquals(144f, b.toPxRect(w, h, density)[1].toFloat(), 1f)
    }

    @Test
    fun `区域是左上右下不是左上宽高`() {
        // 搞错的话 ROI 会缩成一条线。这里刻意用一组 左上右下 的百分比，
        // 断言换算出来的宽高是 (right-left, bottom-top)。
        val area = ZArea.parse("25% 25% 75% 75%")!!
        val r = area.toPxRect(w, h, density)
        assertEquals(320, r[0])   // left  = 25% * 1280
        assertEquals(180, r[1])   // top   = 25% * 720
        assertEquals(640, r[2])   // width = 75%-25% = 50% * 1280
        assertEquals(360, r[3])   // height= 50% * 720
    }

    @Test
    fun `越界的区域会被夹回屏幕内`() {
        // 负坐标或超出屏幕的 ROI 直接送进 OpenCV 会崩，必须先夹住
        val r = ZArea.parse("-10% -10% 150% 150%")!!.toPxRect(w, h, density)
        assertEquals(0, r[0])
        assertEquals(0, r[1])
        assertTrue("宽度不能超过屏幕", r[0] + r[2] <= w)
        assertTrue("高度不能超过屏幕", r[1] + r[3] <= h)
        assertTrue("宽高必须为正", r[2] > 0 && r[3] > 0)
    }

    @Test
    fun `框选出来的像素矩形能反推成百分比`() {
        // 这是"用户在预览上框一块"之后写回字段用的路径
        val area = ZArea.fromPxRect(x = 320, y = 180, w = 640, h = 360, screenWidth = 1280, screenHeight = 720)
        assertEquals("25.00%", area.left.raw)
        assertEquals("25.00%", area.top.raw)
        assertEquals("75.00%", area.right.raw)
        assertEquals("75.00%", area.bottom.raw)
        // 落盘格式：左 上 右 下，空格分隔
        assertEquals("25.00% 25.00% 75.00% 75.00%", area.toString())
    }

    @Test
    fun `框选反推再换算回像素应当与原始矩形一致`() {
        // 往返一致性：框选 → 存百分比 → 运行时换算，不能漂
        val area = ZArea.fromPxRect(412, 980 % 720, 86, 32, 1280, 720)
        val r = area.toPxRect(1280, 720, density)
        assertEquals(412, r[0])
        assertEquals(86, r[2])
        assertEquals(32, r[3])
    }

    @Test
    fun `解析不出来返回 null`() {
        assertNull(ZArea.parse(null))
        assertNull(ZArea.parse(""))
        assertNull(ZArea.parse("1 2 3"))       // 段数不对
        assertNull(ZArea.parse("1-2 3"))       // 第二段不是区间
    }
}
