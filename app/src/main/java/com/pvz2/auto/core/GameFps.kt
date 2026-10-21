package com.pvz2.auto.core

import android.content.Context

/**
 * 游戏帧率上限设置。
 *
 * ## 这个设置到底控的是什么
 *
 * 游戏在虚拟屏里跑，它读 `Display.getRefreshRate()` 来决定自己的渲染节奏。
 * 所以"把游戏限制到 30 / 60 / 120 帧"，在这块屏上就等于
 * **把虚拟屏的刷新率设成 30 / 60 / 120**。
 *
 * 这也是为什么采集端不能再自己加一层节流 —— 那会让"设了 120 却只有 60"
 * 这种问题查不出来（两边都在截断，不知道是谁）。
 *
 * ## 为什么默认是 0（系统默认）
 *
 * 不预设上限。用户装上来先看到完整的帧率表现，觉得费电或太烫再往下调。
 * 反过来默认锁 30 的话，用户第一眼看到的就是卡顿，会以为是程序的问题。
 */
object GameFps {

    private const val PREFS = "game_fps"
    private const val KEY = "limit"

    /**
     * 可选值。0 = 无上限（用系统默认刷新率）。
     *
     * ## 120 被移除，因为它是**真机验证过的重启触发点**
     *
     * 真机上选 120 有概率导致平板重启，dropbox 里的原因很明确：
     *
     * ```
     * System Restart Reason: wd_restart          ← Watchdog 超时
     * System Restart Reason: uncaught_restart    ← 系统服务未捕获异常
     * ```
     *
     * 不是内核 panic，而是**我们把 system_server 拖死了**。设 120Hz 后虚拟屏以
     * 120fps 生产帧，而当前消费路径每帧要付一次同步 Binder（120 次/秒）
     * 和 4 次 3.7MB 拷贝（约 1.8 GB/s 内存带宽），服务端进程、App 进程、
     * Binder 线程池全被占满，system_server 抢不到 CPU，Watchdog 判死重启。
     *
     * 60Hz 时负载减半，勉强扛住 —— 所以这不是"120 这个数字不吉利"，
     * 是架构在 120 下过载。
     *
     * **放回 120 的前提**：把取帧改成事件驱动（native 回调里直接写共享缓冲，
     * 零 Binder、零轮询），让每帧成本降到 1 次 memcpy。在那之前不要加回来。
     */
    val OPTIONS = listOf(30, 60, 0)

    fun prefs(context: Context): Int {
        val v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, 0)
        return if (v in OPTIONS) v else 0
    }

    fun save(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY, value).apply()
    }

    /** 界面上显示的文字。 */
    fun label(value: Int): String = if (value <= 0) "无上限" else "${value} fps"

    /** 从显示文字反查值。 */
    fun fromLabel(text: String): Int {
        val digits = text.takeWhile { it.isDigit() }
        return digits.toIntOrNull()?.takeIf { it in OPTIONS } ?: 0
    }
}
