package com.pvz2.auto.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * 可调四角的框选层。
 *
 * ## 为什么不是"拖一下就定"
 *
 * 拖出来的框永远差一点：手一抖就偏了 20px，而模板偏 20px 就匹配不上。
 * 松手后还能拖四个角改，才谈得上"框准"。这也正是 脚本格式 让用户在画面上
 * 一次一次调的原因 —— 一次成型的框在游戏画面的细节上根本不可能准。
 *
 * ## 触摸优先级
 *
 * 角 > 边 > 内部（移动） > 空白（重新框）。
 * 角排最前是因为它最小、最难命中，如果被"移动"抢走就永远调不到。
 *
 * ## 坐标
 *
 * 对外一律是**虚拟屏坐标**；内部自己换算到控件像素。
 * 直接对外发控件坐标的话，调用方还得再算一遍，两处算法一不一致就全偏。
 */
class RectPickerOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 画面在控件内的位置。 */
    private val imageRect = RectF()

    /** 虚拟屏尺寸。 */
    private var srcW = 0
    private var srcH = 0

    /** 当前框（虚拟屏坐标）。 */
    private var rect = Rect()

    /** 拖动中：正在动的是哪个角，或整体移动。 */
    private var grab = Grab.NONE
    private var downX = 0f
    private var downY = 0f
    private var downRect = Rect()

    /** 角/边的命中半径（控件像素）。手指比图标大得多。 */
    private val slop = 28f * resources.displayMetrics.density

    private enum class Grab { NONE, LEFT_TOP, RIGHT_TOP, LEFT_BOTTOM, RIGHT_BOTTOM, LEFT, RIGHT, TOP, BOTTOM, MOVE }

    /** 框变化回调（虚拟屏坐标 l,t,r,b）。 */
    var onRectChanged: ((IntArray) -> Unit)? = null

    private val dim = Paint().apply { color = Color.parseColor("#66000000") }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        color = Color.parseColor("#4CD07A")
    }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#4CD07A")
    }
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        color = Color.WHITE
    }
    /** 三分网格：帮助把框对齐到图案边界。 */
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density
        color = Color.parseColor("#804CD07A")
    }

    init {
        // 必须可点：否则事件会穿透到下面的预览，导致"按着框却在拖游戏"
        isClickable = true
        isFocusable = true
    }

    fun setFrame(left: Float, top: Float, right: Float, bottom: Float, sw: Int, sh: Int) {
        imageRect.set(left, top, right, bottom)
        srcW = sw
        srcH = sh
        invalidate()
    }

    /** 设置当前框（虚拟屏坐标）。null = 清空。 */
    fun setRect(r: IntArray?) {
        rect = if (r != null && r.size >= 4) Rect(r[0], r[1], r[2], r[3]) else Rect()
        invalidate()
    }

    // ------------------------------------------------------------ 换算

    private fun toViewX(vx: Float): Float =
        imageRect.left + vx / srcW * imageRect.width()

    private fun toViewY(vy: Float): Float =
        imageRect.top + vy / srcH * imageRect.height()

    private fun toViewXBack(px: Float): Int =
        ((px - imageRect.left) / imageRect.width() * srcW).toInt()

    private fun toViewYBack(py: Float): Int =
        ((py - imageRect.top) / imageRect.height() * srcH).toInt()

    private fun viewRect(): RectF = RectF(
        toViewX(rect.left.toFloat()), toViewY(rect.top.toFloat()),
        toViewX(rect.right.toFloat()), toViewY(rect.bottom.toFloat())
    )

    // ------------------------------------------------------------ 触摸

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (srcW <= 0 || imageRect.width() <= 0f) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; downRect = Rect(rect)
                grab = pick(e.x, e.y)
                if (grab == Grab.NONE) {
                    // 空白处按下 = 从这一点重新框
                    val vx = toViewXBack(e.x).coerceIn(0, srcW)
                    val vy = toViewYBack(e.y).coerceIn(0, srcH)
                    rect.set(vx, vy, vx, vy)
                    grab = Grab.RIGHT_BOTTOM
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (grab == Grab.NONE) return true
                apply(e.x, e.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (grab != Grab.NONE) {
                    apply(e.x, e.y)
                    // 太小的框没有意义（等于没框），清掉让用户重来
                    if (rect.width() < 8 || rect.height() < 8) rect = Rect()
                    grab = Grab.NONE
                    invalidate()
                    onRectChanged?.invoke(rect.toArray())
                }
                return true
            }
        }
        return false
    }

    /** 判断按在哪儿。角优先。 */
    private fun pick(x: Float, y: Float): Grab {
        if (rect.width() <= 0 || rect.height() <= 0) return Grab.NONE
        val v = viewRect()
        val nearL = kotlin.math.abs(x - v.left) < slop
        val nearR = kotlin.math.abs(x - v.right) < slop
        val nearT = kotlin.math.abs(y - v.top) < slop
        val nearB = kotlin.math.abs(y - v.bottom) < slop
        val inX = x > v.left - slop && x < v.right + slop
        val inY = y > v.top - slop && y < v.bottom + slop

        return when {
            nearL && nearT -> Grab.LEFT_TOP
            nearR && nearT -> Grab.RIGHT_TOP
            nearL && nearB -> Grab.LEFT_BOTTOM
            nearR && nearB -> Grab.RIGHT_BOTTOM
            nearL && inY -> Grab.LEFT
            nearR && inY -> Grab.RIGHT
            nearT && inX -> Grab.TOP
            nearB && inX -> Grab.BOTTOM
            v.contains(x, y) -> Grab.MOVE
            else -> Grab.NONE
        }
    }

    private fun apply(x: Float, y: Float) {
        val vx = toViewXBack(x).coerceIn(0, srcW)
        val vy = toViewYBack(y).coerceIn(0, srcH)
        when (grab) {
            Grab.LEFT_TOP -> rect.set(vx, vy, rect.right, rect.bottom)
            Grab.RIGHT_TOP -> rect.set(rect.left, vy, vx, rect.bottom)
            Grab.LEFT_BOTTOM -> rect.set(vx, rect.top, rect.right, vy)
            Grab.RIGHT_BOTTOM -> rect.set(rect.left, rect.top, vx, vy)
            Grab.LEFT -> rect.set(vx, rect.top, rect.right, rect.bottom)
            Grab.RIGHT -> rect.set(rect.left, rect.top, vx, rect.bottom)
            Grab.TOP -> rect.set(rect.left, vy, rect.right, rect.bottom)
            Grab.BOTTOM -> rect.set(rect.left, rect.top, rect.right, vy)
            Grab.MOVE -> {
                // 整体移动：保持按下时的宽高，只跟着手指走
                val mvx = (downRect.left + (x - downX) / imageRect.width() * srcW).toInt()
                val mvy = (downRect.top + (y - downY) / imageRect.height() * srcH).toInt()
                rect.set(
                    mvx, mvy,
                    mvx + downRect.width(), mvy + downRect.height()
                )
            }
            Grab.NONE -> Unit
        }
        // 规范化：拖过头会让 left>right
        if (rect.left > rect.right) { val t = rect.left; rect.left = rect.right; rect.right = t }
        if (rect.top > rect.bottom) { val t = rect.top; rect.top = rect.bottom; rect.bottom = t }
        invalidate()
        onRectChanged?.invoke(rect.toArray())
    }

    private fun Rect.toArray() = intArrayOf(left, top, right, bottom)

    // ------------------------------------------------------------ 绘制

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (srcW <= 0 || imageRect.width() <= 0f) return
        if (rect.width() <= 0 || rect.height() <= 0) return

        val v = viewRect()

        // 框外压暗。不压暗的话，用户分不清"我框的是这一块"还是"整屏都算"。
        canvas.drawRect(imageRect.left, imageRect.top, imageRect.right, v.top, dim)
        canvas.drawRect(imageRect.left, v.bottom, imageRect.right, imageRect.bottom, dim)
        canvas.drawRect(imageRect.left, v.top, v.left, v.bottom, dim)
        canvas.drawRect(v.right, v.top, imageRect.right, v.bottom, dim)

        canvas.drawRect(v, stroke)

        // 三分线，方便对齐图案边界
        for (i in 1..2) {
            val gx = v.left + v.width() * i / 3f
            val gy = v.top + v.height() * i / 3f
            canvas.drawLine(gx, v.top, gx, v.bottom, grid)
            canvas.drawLine(v.left, gy, v.right, gy, grid)
        }

        // 四角手柄：白圈 + 实心，深浅背景上都看得见
        val r = 9f * resources.displayMetrics.density
        for ((cx, cy) in listOf(
            v.left to v.top, v.right to v.top,
            v.left to v.bottom, v.right to v.bottom
        )) {
            canvas.drawCircle(cx, cy, r, handle)
            canvas.drawCircle(cx, cy, r, handleRing)
        }
    }
}
