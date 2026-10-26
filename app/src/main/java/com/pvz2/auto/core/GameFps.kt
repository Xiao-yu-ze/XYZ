package com.pvz2.auto.core

/**
 * 帧率策略：**游戏和采集分开**。
 *
 * ## 为什么必须分开
 *
 * 这两件事的需求完全不同，绑在一起是错的：
 *
 * | | 需要多快 | 原因 |
 * |---|---|---|
 * | 游戏帧率 | 越快越顺眼 | 给人看的，120 明显更润 |
 * | 采集帧率 | 30 够用 | 识别每次要算一遍，33ms 已能满足"及时打断" |
 *
 * 实测印证：虚拟屏报 120Hz 时游戏出帧 120fps，改成 30Hz 后立刻掉到 30fps
 * （SurfaceFlinger 时间戳量的）。也就是**虚拟屏刷新率直接决定游戏帧率**。
 *
 * ## 为什么采集要限 30
 *
 * 真机上采集跑到 120fps 会让平板重启，dropbox 的原因是：
 *
 * ```
 * System Restart Reason: wd_restart          ← Watchdog: system_server 无响应
 * ```
 *
 * 因为取帧路径每帧要付一次同步 Binder + 4 次 3.7MB 拷贝，
 * 120fps 时约 1.8 GB/s 内存带宽，把系统服务饿死了。
 * 限到 30 后负载降到 0.44 GB/s，是原来的 1/4。
 *
 * ## 为什么虚拟屏不限
 *
 * 反证：用户在物理屏上一直 120Hz 玩这个游戏，从未重启 ——
 * 所以"游戏 120fps 渲染"本身不会重启，重启是**采集负载**造成的。
 * 既然这样，就没有理由为了采集去牺牲游戏的流畅度。
 *
 * ## 将来
 *
 * 取帧改成事件驱动后（native 回调里直接写共享缓冲，零 Binder、零轮询），
 * 每帧只剩 1 次 memcpy，那时采集也能放开。
 */
object GameFps {

    /*
     * 采集帧率**跟随**用户选的帧率（用户要求："我们改的 30 和 60 是采集和游戏同时改的"）。
     *
     * 原来采集写死 30：那是当初为了压住 120fps 的取帧带宽（约 1.8GB/s 会把
     * system_server 饿死）而定的。现在上限已经收到 60，采集跟到 60 也只有
     * 当年那个带宽的一半（约 0.9GB/s），且不再有 120 那条路径。
     */
    val CAPTURE_FPS: Int get() = REQUESTED_REFRESH_RATE.toInt().coerceAtLeast(1)

    /** 采集节流间隔，毫秒。 */
    val captureIntervalMs: Long get() = 1000L / CAPTURE_FPS

    /**
     * 请求的虚拟屏刷新率。**必须是 30，不能用系统默认。**
     *
     * ## 一段被真机否掉的推理
     *
     * 我曾经认为"重启是采集负载造成的，游戏跑 120 不要紧"，依据是：
     * 用户在物理屏上一直 120Hz 玩这个游戏，从未重启。
     *
     * 于是改成"虚拟屏用系统默认（120Hz）、采集限 30"。**结果又重启了。**
     * dropbox：`uncaught_restart`（system_server 未捕获异常）。
     *
     * 那条"反证"是错的：物理屏和虚拟屏不是同一条路径。虚拟屏要把
     * 120fps 的画面合成进 AImageReader 的 surface 再让 CPU 读回，
     * 和直接显示到物理屏完全不是一回事。
     *
     * **结论：游戏在虚拟屏上跑 120 本身就会把系统搞崩，与采集多少无关。**
     * 所以这里必须显式请求 30，不能留给系统默认。
     */
    /*
     * ★ 用户可选择 30 / 60（2026-10-26）。
     *
     * 原来写死 30，依据是"虚拟屏跑 120 会把系统搞崩"。
     * 但后来查清了崩溃的真实原因不是帧率（是 WM 的多显示越界 + 内存压力），
     * 所以帧率交还给用户选：省电/稳一点用 30，顺滑一点用 60。
     *
     * 仍然**不提供 120**：那条 1.8GB/s 的取帧带宽是有实测记录的，留着不碰。
     */
    @Volatile
    var REQUESTED_REFRESH_RATE: Float = 60f

    /** 允许的取值：只有 30 和 60。 */
    val ALLOWED_RATES = listOf(30f, 60f)

    private const val PREF = "vd_fps"
    private const val KEY = "rate"

    /** App 启动时读回上次选的帧率。 */
    fun load(ctx: android.content.Context) {
        val v = ctx.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE)
            .getFloat(KEY, 60f)
        if (v in ALLOWED_RATES) REQUESTED_REFRESH_RATE = v
    }

    /** 切换帧率并落盘；返回是否真的变了。 */
    fun setRate(ctx: android.content.Context, hz: Float): Boolean {
        if (hz !in ALLOWED_RATES) return false
        REQUESTED_REFRESH_RATE = hz
        ctx.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE)
            .edit().putFloat(KEY, hz).apply()
        return true
    }
}