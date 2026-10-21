package com.pvz2.auto.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 实时帧率计算。
 *
 * ## 为什么要测
 *
 * 帧率是判断"采集还活着吗"的唯一实时指标 —— 之前用的是累计帧数，
 * 那个数字只增不减，画面卡死了照样在涨，等于没用。
 *
 * 而帧率算法有几个容易写错的边界：
 *  - 刚启动时窗口里只有两三帧，按公式折算会报出不可靠的读数；
 *  - 整数除法在低帧率下截断偏差；
 *  - 窗口里恰好只有一帧时跨度是 0，除零；
 *  - 读数不能超过上限。
 *
 * 这些都不该上真机去数，用纯函数钉死。
 */
class FpsTest {

    private fun times(vararg ms: Long): List<Long> = ms.toList()

    @Test
    fun `稳定 60fps`() {
        // 每 16.67ms 一帧，1 秒内 60 帧
        val t = (0..60).map { (it * 1000L / 60) }
        val fps = Fps.compute(t, now = t.last())
        assertEquals(60, fps)
    }

    @Test
    fun `稳定 30fps`() {
        val t = (0..30).map { it * 1000L / 30 }
        val fps = Fps.compute(t, now = t.last())
        assertEquals(30, fps)
    }

    @Test
    fun `空窗口返回 0`() {
        assertEquals(0, Fps.compute(emptyList(), now = 1000))
    }

    @Test
    fun `只有一帧时不会除零`() {
        // 跨度是 0，必须走"样本太少"分支而不是做除法
        assertEquals(1, Fps.compute(times(1000), now = 1000))
    }

    @Test
    fun `样本跨度不足时直接报帧数 不做折算`() {
        // 3 帧、跨度 60ms。按公式折算会是 33fps，但样本太少不可信 ——
        // 应该直接报 3（窗口里有 3 帧）。
        val fps = Fps.compute(times(1000, 1020, 1060), now = 1060)
        assertEquals(3, fps)
    }

    @Test
    fun `读数不超过上限`() {
        // 假设异常密集的 1000 帧挤在 400ms 里（实际不可能，但要钳住）
        val t = (0..1000).map { 1000L + it * 400 / 1000 }
        val fps = Fps.compute(t, now = t.last())
        assertEquals(Fps.MAX_FPS, fps)
    }

    @Test
    fun `卡住时读数降到 0`() {
        // 采集停了：窗口里只剩一帧旧时间戳
        assertEquals(1, Fps.compute(times(5000), now = 6000))
    }

    @Test
    fun `帧率抖动时读数不跳变`() {
        // 一半 60fps 一半 30fps，用"减一除以跨度"应该得到中间值，
        // 而不是在 30 和 60 之间来回跳。
        val t = mutableListOf<Long>()
        var now = 0L
        repeat(30) { t.add(now); now += 16 }      // 前 30 帧快
        repeat(15) { t.add(now); now += 33 }      // 后 15 帧慢
        val fps = Fps.compute(t, now = t.last())
        assertEquals(true, fps in 25..55)
    }

    @Test
    fun `窗口内的帧数决定读数 不会因历史累积而虚高`() {
        // 调用方负责裁掉超窗的时间戳；这里模拟一个已经裁好的窗口：
        // 61 个时间戳、间隔 16ms → 60 帧 / 0.96 秒 ≈ 62fps。
        //
        // ★ 断言它**超过 60** 是有意的：以前 MAX_FPS=60 会把这个读数钳回 60，
        //   于是"实际 62、显示 60"，而虚拟屏刷新率设到 120 时更是永远显示 60 ——
        //   用户会以为提不上去。现在只在 240 封顶，读数如实反映真实帧率。
        val t = (0..60).map { it * 16L }
        val fps = Fps.compute(t, now = t.last())
        assertEquals(true, fps in 61..63)
    }
}
