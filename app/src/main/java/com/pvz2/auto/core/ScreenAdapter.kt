package com.pvz2.auto.core

import android.graphics.PointF
import android.graphics.Rect
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Converts between the 720p "logical" canvas that templates are authored in, and the
 * device's real screen pixels.
 *
 * Scaling rule: height match with the picture horizontally centred - the same behaviour as
 * Unity's `CanvasScaler` with `matchWidthOrHeight = 1`:
 *
 *     scale   = screenHeight / 720
 *     offsetX = (screenWidth - 1280 * scale) / 2
 *
 * Height maps exactly, so `y` needs no offset. `offsetX` is 0 on a 16:9 screen, positive on
 * a wider one (the game is pillarboxed), and negative on a narrower one (the game crops
 * equal amounts off both sides - [toScreenRect] clips for that case).
 */
class ScreenAdapter(
    val screenWidth: Int,
    val screenHeight: Int
) {
    /** Physical pixels per logical unit. */
    val scale: Float = screenHeight / BASE_HEIGHT.toFloat()

    /** Physical X that logical x = 0 maps to. */
    val offsetX: Float = (screenWidth - BASE_WIDTH * scale) / 2f

    /** Logical point -> physical screen point. */
    fun toScreen(logicalX: Float, logicalY: Float): PointF =
        PointF(offsetX + logicalX * scale, logicalY * scale)

    /** Physical screen point -> logical point. */
    fun toLogical(screenX: Float, screenY: Float): PointF =
        PointF((screenX - offsetX) / scale, screenY / scale)

    /**
     * Logical rectangle -> physical rectangle, rounded outwards and clipped to the screen.
     * Returns null when the region lies entirely off-screen.
     */
    fun toScreenRect(logical: Rect): org.opencv.core.Rect? {
        val left = floor(offsetX + logical.left * scale).toInt().coerceIn(0, screenWidth)
        val top = floor(logical.top * scale).toInt().coerceIn(0, screenHeight)
        val right = ceil(offsetX + logical.right * scale).toInt().coerceIn(0, screenWidth)
        val bottom = ceil(logical.bottom * scale).toInt().coerceIn(0, screenHeight)

        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return null
        return org.opencv.core.Rect(left, top, width, height)
    }

    override fun toString(): String =
        "ScreenAdapter(${screenWidth}x$screenHeight scale=$scale offsetX=$offsetX)"

    companion object {
        const val BASE_WIDTH = 1280
        const val BASE_HEIGHT = 720
    }
}
