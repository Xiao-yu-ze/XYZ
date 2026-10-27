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
 * ## 这是照着 脚本格式 的做法
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

    /**
     * 准星当前位置（虚拟屏坐标）。null = 还没开始拖。
     *
     * ## 为什么要准星而不是"点一下就定"
     *
     * 做法是：画面上出现一个光标，用户拖着它走，
     * 底下实时显示 `x, y`，位置满意了再点确定。
     *
     * 直接点一下的问题在于**手指挡住目标**——手指落下的地方就是被挡住的地方，
     * 用户永远看不清自己点准了没有。准星加二次确认解决了这个。
     */
    private val _crosshair = MutableStateFlow<Point?>(null)
    val crosshair: StateFlow<Point?> = _crosshair.asStateFlow()

    /** 准星坐标（虚拟屏像素）。 */
    data class Point(val x: Int, val y: Int)

    private val _result = MutableSharedFlow<Result>(replay = 0, extraBufferCapacity = 4)
    val result: SharedFlow<Result> = _result.asSharedFlow()

    /**
     * 冻结帧。非 null 时预览显示这张静图而不是实时画面。
     *
     * ## 为什么必须冻结
     *
     * 用户在画面上定位置时游戏还在跑。要瞄准的东西一直在动，四个角
     * 根本对不准 —— 松手那一刻位置已经变了。冻结一帧，用户面对的
     * 就是一张静止的图，框哪里就是哪里。
     *
     * 拿到的东西（模板像素、坐标）也应该来自这同一张图，
     * 否则"看到的"和"存下来的"不是一回事。
     */
    private val _frozen = MutableStateFlow<Bitmap?>(null)
    val frozen: StateFlow<Bitmap?> = _frozen.asStateFlow()

    /** 框选模式下当前的矩形（虚拟屏坐标），null = 还没框。 */
    private val _rect = MutableStateFlow<IntArray?>(null)
    val rect: StateFlow<IntArray?> = _rect.asStateFlow()

    /**
     * 开始取点。
     *
     * @param m 取什么
     * @param initial 准星初始位置。null = 屏幕正中
     * @param screenWidth / screenHeight 虚拟屏尺寸，用来算正中
     * @param frozenFrame 当前帧的快照。取点/框选期间预览显示它。
     */
    fun arm(
        m: Mode,
        initial: Point? = null,
        screenWidth: Int = 0,
        screenHeight: Int = 0,
        frozenFrame: Bitmap? = null,
        /**
         * 框选模式的**初始矩形**（虚拟屏坐标 l,t,r,b）。
         *
         * 为什么需要：截完模板后「范围」已经被设成模板那块了。用户再点
         * 「范围」只是想看一眼，什么都不改就点确定 —— 这时如果 rect 是空的，
         * 「确定」会报"先框出一个框"，等于逼他重框一遍自己刚框过的东西。
         */
        initialRect: IntArray? = null
    ) {
        _mode.value = m
        _rect.value = initialRect
        _frozen.value = frozenFrame
        _crosshair.value = initial ?: if (screenWidth > 0 && screenHeight > 0) {
            Point(screenWidth / 2, screenHeight / 2)
        } else {
            null
        }
    }

    /** 更新框选矩形（拖角时调用）。 */
    fun setRect(r: IntArray?) {
        _rect.value = r
    }

    /** 拖准星。坐标会被夹到屏幕范围内，出界的话写回脚本就是个坏值。 */
    fun moveCrosshair(x: Int, y: Int, screenWidth: Int, screenHeight: Int) {
        if (_mode.value == null) return
        _crosshair.value = Point(
            x.coerceIn(0, (screenWidth - 1).coerceAtLeast(0)),
            y.coerceIn(0, (screenHeight - 1).coerceAtLeast(0))
        )
    }

    /** 取消取点，预览恢复成直接操作游戏。 */
    fun cancel() {
        _mode.value = null
        _crosshair.value = null
        _rect.value = null
        // 关键：不清掉冻结帧的话，用户取消后画面会一直停在那一帧，
        // 游戏看起来像卡死了。
        _frozen.value = null
    }

    /**
     * 用户确认了准星位置。
     *
     * 这是「确定」按钮走的路径，不是触摸直接触发 ——
     * 触摸只移动准星，位置看准了再确认。见 [crosshair] 的说明。
     */
    fun confirmCrosshair(screenWidth: Int, screenHeight: Int): Boolean {
        val mode = _mode.value ?: return false
        val p = _crosshair.value ?: return false
        _mode.value = null
        _crosshair.value = null
        _rect.value = null
        _frozen.value = null
        _result.tryEmit(
            Result(
                mode = mode,
                x = p.x, y = p.y, w = 0, h = 0,
                screenWidth = screenWidth, screenHeight = screenHeight
            )
        )
        return true
    }

    /**
     * 用户确认了框选。
     *
     * 模板模式直接从**冻结帧**上裁图 —— 用户看到的就是那张静图，
     * 从别处裁会和他眼睛看到的错位。
     */
    fun confirmRect(screenWidth: Int, screenHeight: Int): Boolean {
        val mode = _mode.value ?: return false
        val r = _rect.value ?: return false
        val x = r[0].coerceIn(0, (screenWidth - 1).coerceAtLeast(0))
        val y = r[1].coerceIn(0, (screenHeight - 1).coerceAtLeast(0))
        val w = (r[2] - r[0]).coerceIn(0, screenWidth - x)
        val h = (r[3] - r[1]).coerceIn(0, screenHeight - y)
        if (w <= 0 || h <= 0) return false

        val crop = if (mode == Mode.TEMPLATE) {
            _frozen.value?.let { bmp ->
                runCatching {
                    val cw = w.coerceAtMost(bmp.width - x)
                    val ch = h.coerceAtMost(bmp.height - y)
                    if (cw > 0 && ch > 0) Bitmap.createBitmap(bmp, x, y, cw, ch) else null
                }.getOrNull()
            }
        } else null

        _mode.value = null
        _crosshair.value = null
        _rect.value = null
        _frozen.value = null
        _result.tryEmit(
            Result(
                mode = mode,
                x = x, y = y, w = w, h = h,
                crop = crop,
                screenWidth = screenWidth, screenHeight = screenHeight
            )
        )
        return true
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
        _crosshair.value = null
        _rect.value = null
        _frozen.value = null
        _result.tryEmit(result)
        return true
    }

    /** 给界面显示用的一句话提示。 */
    fun hintText(mode: Mode): String = when (mode) {
        Mode.POINT -> "按住画面拖动，准星跟着走；位置对了点确定"
        Mode.RECT -> "请在画面上拖出要识别的区域"
        Mode.TEMPLATE -> "请在画面上框住要识别的图案"
    }
}
