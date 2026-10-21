package com.pvz2.auto.core

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 从预览画面上「取点 / 框选区域 / 截取模板图」。
 *
 * ## 这是照着 自动精灵 的做法
 *
 * 它的流程是（原文见反编译出的 `renderer-home-bundle.js`）：
 *
 * ```
 * 点字段旁边的小按钮
 *   → 弹窗「等待选择区域 / 请在脚本运行区域中完成选择」
 *   → 用户直接在正在跑的游戏画面上点或拖
 *   → 值回填进字段
 * ```
 *
 * 关键点：**没有让用户输入坐标**。用户在画面上点到哪就是哪，
 * 换算和单位（百分比）由程序处理。
 *
 * ## 为什么用户根本没法填坐标
 *
 * 坐标既不是游戏里的"第几行第几列"，也不是屏幕上看得见的数字。
 * 让用户自己填等于让他去猜。而"在画面上指一下"是零学习成本的。
 *
 * ## 为什么要百分比
 *
 * 见 [com.pvz2.auto.zjs.ZValue]：框选结果存成 `"25.00% 25.00% 75.00% 75.00%"`，
 * 换分辨率不会失效。存像素的话，在另一台设备上整片坐标就偏了。
 *
 * ## 状态放在单例里
 *
 * 因为「武装」发生在编辑器页，而「用户在画面上点」发生在主界面的预览上 ——
 * 两个不同组件的交互，只能通过进程级状态串起来。
 */
object PreviewPicker {

    /** 当前武装的模式。null = 没在取点，预览的触摸照常注入游戏。 */
    enum class Mode {
        /** 取一个坐标点。 */
        POINT,

        /** 框一个矩形区域（ROI）。 */
        RECT,

        /** 框一块图作为模板（模板匹配用）。 */
        TEMPLATE
    }

    /**
     * 取到的结果。
     *
     * 坐标一律是**虚拟屏坐标**（不是物理屏、不是预览控件的像素），
     * 因为要写进脚本的是游戏画面里的位置。
     */
    data class Result(
        val mode: Mode,
        /** 左上角；[Mode.POINT] 时是那个点本身。 */
        val x: Int,
        val y: Int,
        /** 宽高；[Mode.POINT] 时为 0。 */
        val w: Int,
        val h: Int,
        /** 框选时从当前帧裁下来的图，仅 [Mode.TEMPLATE] 有。 */
        val crop: Bitmap? = null,
        /** 取点时用的屏幕尺寸，写回百分比时要用。 */
        val screenWidth: Int,
        val screenHeight: Int
    )

    private val _mode = MutableStateFlow<Mode?>(null)
    val mode: StateFlow<Mode?> = _mode.asStateFlow()

    private val _result = MutableSharedFlow<Result>(replay = 0, extraBufferCapacity = 4)
    val result: SharedFlow<Result> = _result.asSharedFlow()

    /** 开始取点。调用方随后提示用户"请在画面上点一下"。 */
    fun arm(m: Mode) {
        _mode.value = m
    }

    /** 取消取点，预览恢复成直接操作游戏。 */
    fun cancel() {
        _mode.value = null
    }

    /**
     * 用户在预览上完成了一次取点/框选。
     *
     * 由主界面的触摸处理调用。返回值表示"这次触摸被取点消费了"，
     * 调用方据此决定**不**把事件注入游戏 —— 否则取点会顺手点一下游戏。
     */
    fun deliver(result: Result): Boolean {
        if (_mode.value == null) return false
        _mode.value = null
        _result.tryEmit(result)
        return true
    }

    /** 给界面显示用的一句话提示。 */
    fun hintText(mode: Mode): String = when (mode) {
        Mode.POINT -> "请在画面上点一下要点击的位置"
        Mode.RECT -> "请在画面上拖出要识别的区域"
        Mode.TEMPLATE -> "请在画面上框住要识别的图案"
    }
}
