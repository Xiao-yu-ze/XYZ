package com.pvz2.auto.zjs

import com.pvz2.auto.core.TouchInjector

/**
 * 把 [TouchInjector] 接到 [ZExecutor.Injector] 上。
 *
 * ## 为什么中间还要一层
 *
 * [TouchInjector] 的 `tap` 没有"按住多久"这个参数 —— 它就是一次快速点击。
 * 而协议里`duration` 对点击表示按下时长。直接丢了这个参数会让"长按"
 * 变成短按，而长按和短按在很多游戏里是完全不同的操作。
 *
 * 所以这里用 `swipe(起点, 起点, duration)` 实现长按：同一点拖 0 距离，
 * 时长就是按住时长。短按时（≤100ms）还是走 `tap`，那条路径更快。
 */
class ZTouchInjectorAdapter(
    private val touch: TouchInjector
) : ZExecutor.Injector {

    override fun tap(x: Int, y: Int, holdMs: Int) {
        if (holdMs <= SHORT_TAP_MS) {
            touch.tap(x.toFloat(), y.toFloat())
        } else {
            // 同一点之间的 swipe = 按住不放
            touch.swipe(x.toFloat(), y.toFloat(), x.toFloat(), y.toFloat(), holdMs.toLong())
        }
    }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
        touch.swipe(
            x1.toFloat(), y1.toFloat(),
            x2.toFloat(), y2.toFloat(),
            durationMs.toLong()
        )
    }

    override fun key(keyCode: Int, holdMs: Int) {
        // TouchInjector 只管触摸，按键要走另一个通道。
        // 没配通道时**必须抛**：静默吞掉的话，用户看到脚本跑完了，
        // 但游戏里那个键从没按过，而日志里什么都没有。
        val ch = delegateKey
            ?: throw IllegalStateException("没有配置按键通道，无法按键")
        ch(keyCode, holdMs)
    }

    override fun inputText(text: String) {
        // 同上：没通道就抛，别让"输入了什么"这件事无声消失。
        val ch = delegateText
            ?: throw IllegalStateException("没有配置文字输入通道，无法输入")
        ch(text)
    }

    /** 按键通道。没配置时点按键动作会抛，执行器会记一条跳过原因。 */
    var delegateKey: ((Int, Int) -> Unit)? = null

    /** 文字输入通道。 */
    var delegateText: ((String) -> Unit)? = null

    private companion object {
        /** 这个时长以内算短按，走更快的 tap。 */
        const val SHORT_TAP_MS = 120
    }
}
