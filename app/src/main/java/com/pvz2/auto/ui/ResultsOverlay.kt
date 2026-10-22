package com.pvz2.auto.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * 把匹配结果画在预览画面上。
 *
 * ## 这一层解决的是调参的反馈回路
 *
 * 模板匹配最花时间的不是"能不能匹配"，是**阈值调到多少合适**。
 * 没有这层的话，用户每改一次阈值就得运行一次脚本、等它跑到那一条、
 * 再去看游戏里点没点中 —— 一次三十秒，而他要试十几次。
 *
 * 自动精灵 也是这么做的：`previewResultBounds` 把命中框和相似度
 * 直接画回实时画面，每 300ms 刷一次（见 `docs/zdjl-capture-and-roi.md`）。
 *
 * ## 坐标换算
 *
 * 结果来自执行器，是**虚拟屏坐标**；这里要换算到控件坐标。
 * 先按 [source] 尺寸归一化，再映射进 [imageRect]。直接画虚拟坐标会错位 ——
 * 预览是缩放显示的，1280 宽的图在屏幕上可能只有 600 宽。
 */
class ResultsOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 虚拟屏尺寸（结果坐标系）。 */
    private var sourceW = 0
    private var sourceH = 0

    /** 画面在控件内的位置。 */
    private val imageRect = RectF()

    /** 搜索范围（`limitArea`），没有就整屏。 */
    private var searchRect: RectF? = null

    /** 命中框 + 标签。 */
    private var boxes: List<Box> = emptyList()

    /**
     * 一个命中。
     *
     * 坐标是虚拟屏像素；标签是给用户看的一句话
     * （`相似度: 93.20%` 或 `找不到` 的原因）。
     */
    data class Box(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val label: String
    )

    private val hitStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
        color = Color.parseColor("#FF3B6B")
    }

    private val hitShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f * resources.displayMetrics.density
        color = Color.parseColor("#99000000")
    }

    private val areaStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        color = Color.parseColor("#FFD24A")
        // 虚线：搜索范围是"我在哪找"，不是匹配结果，两者视觉上必须分得开
        pathEffect = DashPathEffect(
            floatArrayOf(10f * resources.displayMetrics.density, 7f * resources.displayMetrics.density),
            0f
        )
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * resources.displayMetrics.scaledDensity
        color = Color.WHITE
    }

    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC000000")
    }

    /** 画面矩形和虚拟屏尺寸都变了才需要重算。 */
    fun setFrame(left: Float, top: Float, right: Float, bottom: Float, sw: Int, sh: Int) {
        imageRect.set(left, top, right, bottom)
        sourceW = sw
        sourceH = sh
        invalidate()
    }

    /**
     * 更新内容。
     *
     * @param search 搜索范围（虚拟屏坐标 left,top,right,bottom），null = 整屏
     * @param list   命中框
     */
    fun setResults(search: IntArray?, list: List<Box>) {
        searchRect = search?.let {
            if (it.size >= 4) RectF(it[0].toFloat(), it[1].toFloat(), it[2].toFloat(), it[3].toFloat())
            else null
        }
        boxes = list
        invalidate()
    }

    fun clear() {
        boxes = emptyList()
        searchRect = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (sourceW <= 0 || sourceH <= 0 || imageRect.width() <= 0f) return

        // 搜索范围先画（虚线），命中框盖在上面
        searchRect?.let { r ->
            val v = toView(r)
            canvas.drawRect(v, areaStroke)
        }

        for (b in boxes) {
            val v = toView(
                RectF(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
            )
            canvas.drawRect(v, hitShadow)
            canvas.drawRect(v, hitStroke)
            drawLabel(canvas, v, b.label)
        }
    }

    /** 虚拟屏坐标 → 控件坐标。 */
    private fun toView(r: RectF): RectF {
        val sx = imageRect.width() / sourceW
        val sy = imageRect.height() / sourceH
        return RectF(
            imageRect.left + r.left * sx,
            imageRect.top + r.top * sy,
            imageRect.left + r.right * sx,
            imageRect.top + r.bottom * sy
        )
    }

    /**
     * 标签画在框**上方**，超出画面就翻到框内。
     *
     * 画在里面会挡住命中的图案，而用户正是要看那个图案对不对。
     */
    private fun drawLabel(canvas: Canvas, box: RectF, text: String) {
        if (text.isEmpty()) return
        val pad = 4f * resources.displayMetrics.density
        val w = labelPaint.measureText(text) + pad * 2
        val h = labelPaint.textSize + pad * 2

        var top = box.top - h
        if (top < imageRect.top) top = box.top   // 顶到画面外就放进框里
        val left = box.left.coerceAtMost(imageRect.right - w).coerceAtLeast(imageRect.left)

        canvas.drawRect(left, top, left + w, top + h, labelBg)
        canvas.drawText(text, left + pad, top + h - pad - labelPaint.descent(), labelPaint)
    }
}
