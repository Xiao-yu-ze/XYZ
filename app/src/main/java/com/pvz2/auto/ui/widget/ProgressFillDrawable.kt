package com.pvz2.auto.ui.widget

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * 节点卡片的背景：白底 + 绿色进度。
 *
 * 为什么不放一条独立进度条：
 *   卡片本身就有白底，再插一条 10dp 的条会挤占一行高度，而且"第几条跑得怎么样"
 *   要同时看两处。改成"白底上从左往右染色"，进度直接体现在卡片本身，
 *   前沿再压一层渐变阴影 —— 看起来是光扫过去，不是硬切一刀。
 */
class ProgressFillDrawable : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var fraction = -1f

    /** [f] < 0 表示没有可算的进度（只画白底）。 */
    fun setFraction(f: Float) {
        if (fraction == f) return
        fraction = f
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val r = rect
        r.set(bounds)
        if (r.width() <= 0f || r.height() <= 0f) return
        val rad = 8f * (r.width() / 360f).coerceIn(0.6f, 3f)

        // 白底
        paint.shader = null
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawRoundRect(r, rad, rad, paint)

        val f = fraction
        if (f !in 0f..1f) return

        val w = r.width() * f
        if (w <= 0.5f) return

        // 绿色填充（半透明，压在白底上像一层阴影而不是纯色块）
        canvas.save()
        canvas.clipRect(r.left, r.top, r.left + w, r.bottom)
        paint.color = 0x2E2FBF5B
        canvas.drawRoundRect(r, rad, rad, paint)
        canvas.restore()

        // 前沿光晕：让边缘是渐隐的
        val edge = (r.width() * 0.12f).coerceIn(24f, 120f)
        if (w > edge) {
            paint.shader = LinearGradient(
                r.left + w - edge, 0f, r.left + w, 0f,
                intArrayOf(0x00000000, 0x552FBF5B, 0x222FBF5B),
                floatArrayOf(0f, 0.75f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(r.left + w - edge, r.top, r.left + w, r.bottom, paint)
            paint.shader = null
        }
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("deprecated in Drawable")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getPadding(padding: Rect): Boolean {
        padding.set(0, 0, 0, 0)
        return true
    }
}
