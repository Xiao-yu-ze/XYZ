package com.pvz2.auto.maa

import com.pvz2.auto.core.ScreenAdapter
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.ceil
import kotlin.math.floor

/**
 * ROI 逻辑坐标 → 物理像素的换算。
 *
 * ## 为什么值得单独测
 *
 * Recognizer 的契约是「ROI 全是 720p 逻辑坐标」，但它拿到的 frame 是物理像素。
 * 这个换算一旦漏掉，在 **1280×720 虚拟屏上完全看不出来**（scale=1、offset=0，
 * 逻辑坐标与物理坐标恰好相等），只有换到 1080p 设备才暴露 —— 而那时表现是
 * 「识别全部落空」，很难反推到坐标层。
 *
 * 现在主路线恰好是 720p，所以这组断言是本模块唯一能防住回归的东西。
 *
 * ## 为什么不直接调 ScreenAdapter.toScreenRect
 *
 * 它的入参/出参是 `android.graphics.Rect` 与 `org.opencv.core.Rect`，
 * 两者在纯 JVM 单测里都不可用（framework 类字段读出来是 0，OpenCV 是 native）。
 * 所以这里断言的是**换算公式本身**（与 toScreenRect 内部逐行对应），
 * 以及 ScreenAdapter 暴露的纯 Float 属性。
 */
class RoiConvertTest {

    private fun adapter(w: Int, h: Int) = ScreenAdapter(w, h)

    /** 与 ScreenAdapter.toScreenRect 完全一致的换算，只是不碰 Android 类型。 */
    private fun convert(
        a: ScreenAdapter,
        left: Int, top: Int, right: Int, bottom: Int
    ): IntArray? {
        val l = floor(a.offsetX + left * a.scale).toInt().coerceIn(0, a.screenWidth)
        val t = floor(a.offsetY + top * a.scale).toInt().coerceIn(0, a.screenHeight)
        val r = ceil(a.offsetX + right * a.scale).toInt().coerceIn(0, a.screenWidth)
        val b = ceil(a.offsetY + bottom * a.scale).toInt().coerceIn(0, a.screenHeight)
        val w = r - l
        val h = b - t
        if (w <= 0 || h <= 0) return null
        return intArrayOf(l, t, w, h)
    }

    @Test
    fun `720p 上逻辑坐标与物理坐标相同`() {
        val a = adapter(1280, 720)
        assertEquals(1.0f, a.scale, 0.0001f)
        assertEquals(0.0f, a.offsetX, 0.0001f)

        val r = convert(a, 100, 50, 300, 250)!!
        assertEquals(100, r[0])
        assertEquals(50, r[1])
        assertEquals(200, r[2])
        assertEquals(200, r[3])
    }

    @Test
    fun `1080p 上 ROI 按 scale 放大`() {
        // 1080p 横屏：短边 1080 → scale = 1080/720 = 1.5
        val a = adapter(1920, 1080)
        assertEquals(1.5f, a.scale, 0.0001f)

        // 逻辑 (100,50)-(300,250) 应映射到物理 (150,75)，尺寸 300x300
        val r = convert(a, 100, 50, 300, 250)!!
        assertEquals(150, r[0])
        assertEquals(75, r[1])
        assertEquals(300, r[2])
        assertEquals(300, r[3])
    }

    @Test
    fun `1440p 上 ROI 继续按比例放大`() {
        // 2560x1440：scale = 1440/720 = 2.0
        val a = adapter(2560, 1440)
        assertEquals(2.0f, a.scale, 0.0001f)

        val r = convert(a, 640, 360, 740, 410)!!
        assertEquals(1280, r[0])
        assertEquals(720, r[1])
        assertEquals(200, r[2])
        assertEquals(100, r[3])
    }

    @Test
    fun `物理坐标转回逻辑坐标是逆运算`() {
        // toLogical 返回 android.graphics.PointF，在 JVM 单测里字段读出来是 0，
        // 所以这里按它的公式算一遍（(screen - offset) / scale），
        // 验证"物理→逻辑→物理"能闭合。
        val a = adapter(1920, 1080)
        val physicalX = 150f
        val physicalY = 75f

        val logicalX = (physicalX - a.offsetX) / a.scale
        val logicalY = (physicalY - a.offsetY) / a.scale
        assertEquals(100.0, logicalX.toDouble(), 0.01)
        assertEquals(50.0, logicalY.toDouble(), 0.01)

        // 再转回物理，应当回到原点
        val backX = a.offsetX + logicalX * a.scale
        val backY = a.offsetY + logicalY * a.scale
        assertEquals(physicalX.toDouble(), backX.toDouble(), 0.01)
        assertEquals(physicalY.toDouble(), backY.toDouble(), 0.01)
    }

    @Test
    fun `完全落在屏外的 ROI 返回 null`() {
        val a = adapter(1280, 720)
        // 逻辑画布只有 1280 宽，x=2000 起完全在外
        assertEquals(null, convert(a, 2000, 100, 2100, 200))
    }

    @Test
    fun `模板逻辑尺寸等于物理尺寸除以 scale`() {
        // 这是 Recognizer 里 box 宽高的算法。scale=1.5 时物理 300px 的模板
        // 对应逻辑 200px；之前直接用物理宽高，框会大出 50%。
        val a = adapter(1920, 1080)
        assertEquals(200.0, 300.0 / a.scale, 0.01)
    }

    @Test
    fun `offsetX 在常见横屏比例下恒为零`() {
        // 依据 ScreenAdapter 的注释：宽高比 ≥ 4:3 时游戏内容左对齐，offsetX = 0。
        // 这条钉死的是"游戏原始算法"的结论，防止有人改回居中留边。
        for ((w, h) in listOf(
            1280 to 720,    // 16:9
            1920 to 1080,   // 16:9
            2560 to 1440,   // 16:9
            1600 to 1200,   // 4:3 边界
            2048 to 1536,   // 4:3
        )) {
            assertEquals("${w}x$h 的 offsetX 应为 0", 0.0f, adapter(w, h).offsetX, 0.0001f)
        }
    }

    @Test
    fun `offsetY 恒为零`() {
        // 内容区高 == 屏高，所以 Y 方向永远不偏移。
        for ((w, h) in listOf(1280 to 720, 1920 to 1080, 2560 to 1440)) {
            assertEquals(0.0f, adapter(w, h).offsetY, 0.0001f)
        }
    }

    @Test
    fun `窄屏上 offsetX 为负 内容左右对称裁掉`() {
        // 宽高比 < 4:3 时 contentW > screenW，offsetX 为负。
        // 例如 1000x900（比 4:3 更窄）：contentW = 900*4/3 = 1200 > 1000
        val a = adapter(1000, 900)
        assertEquals(-100.0f, a.offsetX, 0.0001f)
    }
}
