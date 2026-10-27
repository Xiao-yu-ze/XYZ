package com.pvz2.auto.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * 取点用的准星。
 *
 * ## 为什么必须用准星而不是"点一下就定"
 *
 * 手指落下的地方就是被手指**挡住**的地方。用户点完抬手之前，
 * 根本看不见自己点在哪 —— 点偏了也不知道。
 *
 * 准星把"瞄准"和"确认"分成两步：手指在画面上拖，准星跟着走，
 * 位置由旁边的数字实时显示，满意了再点确定。
 * 这是 脚本格式 的做法（它的浮层底部就是这么一条工具条）。
 *
 * ## 坐标空间
 *
 * 本控件覆盖在预览图之上，但预览图是 `fitCenter` 的 —— 画面两侧
 * 可能有黑边。所以换算必须走 [imageRect]：
 * 只有落在画面矩形内的触摸才算有效，落在黑边上的直接忽略。
 *
 * ## 只负责画和收触摸
 *
 * 真正的坐标存在 [com.pvz2.auto.core.PreviewPicker.crosshair] 里 ——
 * 取点是被两个页面共享的状态，不能只活在这个 View 里。
 */
class CrosshairOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 画面在控件里的实际矩形（扣掉黑边）。 */
    private var imageRect = android.graphics.RectF()

    /** 准星位置，**控件像素**坐标系。null = 不显示。 */
    private var cx = 0f
    private var cy = 0f
    private var visible = false

    /** 拖动回调：(控件像素 x, y)。 */
    var onDrag: ((Float, Float) -> Unit)? = null

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        color = Color.WHITE
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        // 黑描边垫在白色下面 —— 纯白线在亮背景（比如白草地）上会看不见
        strokeWidth = 4f * resources.displayMetrics.density
        color = 0x99000000.toInt()
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    /**
     * 画面矩形变化时调用（预览控件尺寸变了、或图片比例变了）。
     *
     * @param left / top / right / bottom 画面在控件内的位置
     */
    fun setImageRect(left: Float, top: Float, right: Float, bottom: Float) {
        imageRect.set(left, top, right, bottom)
        invalidate()
    }

    /** 显示准星并设置位置（控件像素）。 */
    fun show(x: Float, y: Float) {
        cx = x
        cy = y
        visible = true
        invalidate()
    }

    fun hide() {
        visible = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!visible) return

        val d = 14f * resources.displayMetrics.density
        val r = 5f * resources.displayMetrics.density

        // 先画黑底再画白线，保证在深浅背景上都看得见
        for (p in listOf(shadow, stroke)) {
            // 横线
            canvas.drawLine(cx - d, cy, cx - r, cy, p)
            canvas.drawLine(cx + r, cy, cx + d, cy, p)
            // 竖线
            canvas.drawLine(cx, cy - d, cx, cy - r, p)
            canvas.drawLine(cx, cy + r, cx, cy + d, p)
            // 四角小方框，标出"这个点是命中点"
            canvas.drawRect(cx - r, cy - r, cx + r, cy + r, p)
        }
        canvas.drawCircle(cx, cy, 1.6f * resources.displayMetrics.density, dot)
    }

    /**
     * 按下时的触点，用来算位移。
     *
     * ## 为什么准星不能跳到手指下面
     *
     * 手指按在屏幕上，它盖住的地方就是用户最想看的那个点 ——
     * 直接跳到手指位置的话，准星被手挡着，等于没画。
     *
     * 所以改成**相对移动**：按下时记住触点，之后准星按手指的**位移量**
     * 走。手指往右拖 10px，准星往右挪 10px，但准星始终在手指上方
     * （或任意它当时所在的地方），用户看得见。
     *
     * 这也顺带解决了另一个问题：手指按下去的那一下不会让准星跳一下。
     */
    private var downTouchX = 0f
    private var downTouchY = 0f

    /** 按下时准星在哪，相对移动的起点。 */
    private var downCrossX = 0f
    private var downCrossY = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!visible) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 按下点必须在画面内。但准星可以不在按下点 ——
                // 用户可以从任何地方开始拖。
                if (!imageRect.contains(event.x, event.y)) return false
                downTouchX = event.x
                downTouchY = event.y
                downCrossX = cx
                downCrossY = cy
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                // 相对位移。手指拖多远，准星走多远。
                val nx = downCrossX + (event.x - downTouchX)
                val ny = downCrossY + (event.y - downTouchY)

                // 夹在画面矩形内 —— 拖出边界时准星贴边停住，
                // 而不是跑到黑边上（换算过去是负坐标）。
                cx = nx.coerceIn(imageRect.left, imageRect.right)
                cy = ny.coerceIn(imageRect.top, imageRect.bottom)
                invalidate()
                onDrag?.invoke(cx, cy)
                return true
            }
        }
        // 抬手不算确认 —— 确认要走「确定」按钮。
        // 抬手就定的话，用户想微调时会不小心提交。
        return true
    }
}
