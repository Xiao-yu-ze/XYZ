package com.pvz2.auto.core

import kotlin.random.Random

/** Injects touches at physical screen coordinates. */
interface TouchInjector {

    fun tap(x: Float, y: Float)

    /**
     * 带按下时长的点击。[holdMs] <= 0 表示**极速模式**（不等事件结束）。
     *
     * 默认实现忽略时长直接调 [tap] —— 只用于测试替身；
     * 真机后端应当覆盖它，把时长和极速模式传下去。
     */
    fun tap(x: Float, y: Float, holdMs: Int) = tap(x, y)

    /**
     * 目标显示（虚拟屏）现在还在不在。
     *
     * ## 为什么需要它
     *
     * 用户真机上报"用脚本会重启手机"。查到的实际是：
     * `system_server` 在 `SystemGesturesPointerEventListener.onPointerEvent` →
     * `RootWindowContainer.getTopFocusedDisplayContent` 里
     * `Index 1 out of bounds for length 1` 崩掉（厂商系统多显示分支的越界），
     * 内核并没有重启（uptime 24 天）。
     *
     * 触发点是一次触摸 DOWN 落在一个"已经不存在的副屏"上。
     * 所以每次注入前先确认显示还在；不在就**立刻停脚本**，
     * 绝不能回退到主屏注入 —— 那一下正好喂给这个系统 bug。
     */
    fun isDisplayAlive(): Boolean = true

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long)

    /**
     * 开始一次由手指驱动的手势（DOWN → 若干 MOVE → UP）。
     *
     * 与 [tap] / [swipe] 的区别：后两者是**一次性**的，手势形状必须事先知道，
     * 所以一次拖动只能在抬手那一刻才注入 —— 用户看到的就是"手不动、
     * 松手后画面才滑一下"。这个方法把控制权交给调用方，按下就发 DOWN，
     * 手指每动一次发一个 MOVE，抬手发 UP。
     *
     * @return null 表示这个后端不支持流式手势
     */
    fun beginGesture(x: Float, y: Float): StreamingGesture? = null

    /**
     * 多指同时滑动。
     *
     * ## 默认实现是逐条滑，但那**不是**多指
     *
     * 逐条滑会变成"先滑第一根，再滑第二根"，而多指手势的全部意义在于同时。
     * 支持真并发的后端**必须覆盖**这个方法。默认实现只用于测试替身和
     * 不支持多指的后端 —— 它保证行为可预测，但不保证语义正确。
     *
     * @param paths 每根手指 `[起点x, 起点y, 终点x, 终点y]`
     */
    fun multiSwipe(paths: List<FloatArray>, durationMs: Long) {
        paths.forEach { p ->
            if (p.size >= 4) swipe(p[0], p[1], p[2], p[3], durationMs)
        }
    }

    /** Releases backend resources. Safe to call multiple times. */
    fun release() = Unit
}

/**
 * 一次进行中的手势。坐标是**注入后端坐标系**（虚拟屏或物理屏）。
 *
 * 使用约定：调用 [finish] 或 [cancel] 结束，否则那个"按下"永远不会释放。
 */
interface StreamingGesture {
    fun moveTo(x: Float, y: Float)
    fun finish(x: Float, y: Float)
    fun cancel()
}

/**
 * +/-5 px of random offset applied to every synthetic tap.
 *
 * Pixel-identical repeated taps are trivially fingerprintable by anti-automation checks,
 * and a small offset also mirrors how a real finger lands.
 */
object TapJitter {
    private const val RADIUS = 5

    fun dx(): Int = Random.nextInt(-RADIUS, RADIUS + 1)

    fun dy(): Int = Random.nextInt(-RADIUS, RADIUS + 1)
}
