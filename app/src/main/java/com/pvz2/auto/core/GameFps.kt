package com.pvz2.auto.core

/**
 * 帧率设置。
 *
 * ## 这个值同时管两件事
 *
 * 游戏在虚拟屏里跑，它读 `Display.getRefreshRate()` 决定自己的渲染节奏 ——
 * 所以「把游戏限制到 N 帧」就等于**把虚拟屏刷新率设成 N**。
 * 同时采集端也按同一个值截流，两边同一个数字，不会出现
 * 「设了 60、采集却跑 120」这种对不上的情况。
 *
 * ## 为什么固定 30，不再让用户选
 *
 * 真机上选 120 会让平板**重启**。dropbox 里原因很明确：
 *
 * ```
 * System Restart Reason: wd_restart          ← Watchdog 判定 system_server 无响应
 * System Restart Reason: uncaught_restart
 * ```
 *
 * 不是内核崩溃，是我们把系统服务饿死了：当前取帧路径每帧要付
 * 一次同步 Binder（120 次/秒）+ 4 次 3.7MB 拷贝（约 1.8 GB/s 内存带宽），
 * 120fps 时把 CPU 和 Binder 线程池占满，system_server 抢不到时间片。
 * 60 勉强扛住，30 稳。
 *
 * 30fps 对「看到就立刻打断 Boss 技能」这个需求够用（33ms 一帧）。
 * **先把系统跑稳，比多要 30 帧重要。**
 *
 * ## 将来放开的前提
 *
 * 取帧要改成事件驱动：在 `AImageReader` 的回调里直接把像素写进共享缓冲，
 * 零 Binder、零轮询。那样每帧只剩 1 次 memcpy，60/120 才有意义。
 * 在那之前把选项加回来，等于把这个重启重新引入一遍。
 */
object GameFps {

    /** 固定帧率。游戏帧率和采集帧率都用它。 */
    const val FIXED_FPS = 30

    /** 采集节流间隔，毫秒。 */
    val captureIntervalMs: Long get() = 1000L / FIXED_FPS

    /** 界面上显示的文字。 */
    val label: String get() = "$FIXED_FPS fps（固定）"
}
