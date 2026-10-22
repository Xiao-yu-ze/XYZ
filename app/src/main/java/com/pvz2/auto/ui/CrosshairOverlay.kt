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
 * 这是 自动精灵 的做法（它的浮层底部就是这么一条工具条）。
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

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!visible) return false

        // 只认画面矩形内的触摸。落在黑边上的忽略 —— 换算过去会是负坐标。
        if (!imageRect.contains(event.x, event.y)) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                cx = event.x
                cy = event.y
                invalidate()
                onDrag?.invoke(event.x, event.y)
                return true
            }
        }
        // 抬手不算确认 —— 确认要走「确定」按钮。
        // 抬手就定的话，用户想微调时会不小心提交。
        return true
    }
}
