package com.pvz2.auto.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 采集节流的补睡时长。
 *
 * ## 为什么单独测
 *
 * 真机上帧率一直卡在 34fps、从没超过 35，原因就是这个算术：
 * 时间基准取的是"处理完之后"，于是 `elapsed ≈ 0`，每帧都固定睡满一个
 * 完整间隔，处理时间全加在上面。周期 = 处理(13ms) + 间隔(16ms) = 29ms
 * → 1000/29 ≈ 34fps。
 *
 * 纯算术 bug 用单测钉死，比上真机数快得多。
 */
class CaptureThrottleTest {

    /** 与轮询循环里完全一致的补睡算法。 */
    private fun sleepFor(frameStartedAt: Long, now: Long, targetIntervalMs: Long): Long {
        val elapsed = now - frameStartedAt
        val remain = targetIntervalMs - elapsed
        return if (remain > 0) remain else 0L
    }

    @Test
    fun `处理很快时补满到目标间隔`() {
        // 处理只花了 5ms，目标 16ms → 还要睡 11ms
        assertEquals(11L, sleepFor(frameStartedAt = 0, now = 5, targetIntervalMs = 16))
    }

    @Test
    fun `处理正好等于间隔时不补睡`() {
        assertEquals(0L, sleepFor(frameStartedAt = 0, now = 16, targetIntervalMs = 16))
    }

    @Test
    fun `处理超过间隔时不补睡 也不返回负数`() {
        // 处理 25ms，比 16ms 的间隔还长 —— 不能返回负的 delay
        assertEquals(0L, sleepFor(frameStartedAt = 0, now = 25, targetIntervalMs = 16))
    }

    @Test
    fun `旧的错误算法会把帧率锁死在 34fps`() {
        // 复现旧 bug：时间基准取"处理完之后"，elapsed 恒为 0，
        // 于是每帧都睡满 16ms，处理 13ms 的实际周期是 29ms。
        val processingMs = 13L
        val targetIntervalMs = 16L

        // 旧算法
        val oldElapsed = 0L                                  // now - lastCaptureAt，几乎为 0
        val oldSleep = if (targetIntervalMs - oldElapsed > 0) targetIntervalMs - oldElapsed else 0L
        val oldPeriod = processingMs + oldSleep
        val oldFps = 1000L / oldPeriod
        assertEquals(34L, oldFps)

        // 新算法：从帧开始算 → 周期被压到 16ms
        val newSleep = sleepFor(0, processingMs, targetIntervalMs)
        val newPeriod = processingMs + newSleep
        assertEquals(16L, newPeriod)

        // 注意 1000/16 = 62，比 60 略高 —— 整数毫秒无法精确表示 16.67ms，
        // 所以采集实际会到 62fps。显示值由 Fps.compute 钳到 MAX_FPS，
        // 用户看到的永远不会超过 60。
        assertEquals(62L, 1000L / newPeriod)
    }

    @Test
    fun `目标 60fps 时周期是 16ms`() {
        val interval = 1000L / Fps.MAX_FPS
        assertEquals(16L, interval)
    }

    @Test
    fun `处理耗时 20ms 时帧率被处理能力限制在 50fps`() {
        // 处理比间隔慢，节流不该起作用，帧率由处理速度决定
        val processingMs = 20L
        val sleep = sleepFor(0, processingMs, 16)
        assertEquals(0L, sleep)
        assertEquals(50L, 1000L / processingMs)
    }
}
