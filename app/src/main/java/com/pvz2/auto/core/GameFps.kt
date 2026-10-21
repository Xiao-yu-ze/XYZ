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

    /** 可选值。0 = 无上限（用系统默认刷新率）。 */
    val OPTIONS = listOf(30, 60, 120, 0)

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
