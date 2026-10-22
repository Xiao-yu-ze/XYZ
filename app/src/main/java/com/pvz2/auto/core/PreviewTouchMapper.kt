package com.pvz2.auto.core

import kotlin.math.min

/**
 * 把**预览控件内的触点坐标**换算成**虚拟屏坐标**。
 *
 * ## 为什么需要它
 *
 * 虚拟屏没有物理触摸面板 —— 用户不能用手指去点它。要在游戏里点登录、
 * 翻菜单，唯一的路是：在预览画面上点一下，由程序换算坐标后注入。
 *
 * 没有这个，用户连"把游戏点到竞技界面"都做不到，脚本就永远跑不到能验证的状态。
 *
 * ## fitCenter 的几何
 *
 * 预览用的是 `ImageView.scaleType="fitCenter"`：整张图**等比缩放**到刚好装进
 * 控件，然后**居中**。所以画面四周可能有留边（letterbox）。
 *
 * ```
 * scale  = min(viewW / imageW, viewH / imageH)
 * drawnW = imageW * scale
 * drawnH = imageH * scale
 * offsetX = (viewW - drawnW) / 2
 * offsetY = (viewH - drawnH) / 2
 *
 * imageX = (viewX - offsetX) / scale
 * imageY = (viewY - offsetY) / scale
 * ```
 *
 * 点在留边上时返回 null —— 那里没有画面，不该注入。
 * 不判这件事的话，点黑边会换算出一个贴着边缘的坐标，误触屏幕边缘的按钮。
 */
object PreviewTouchMapper {

    /** 虚拟屏坐标系下的整数点。 */
    data class Point(val x: Int, val y: Int)

    /**
     * 画面在控件里的实际矩形 `[left, top, right, bottom]`。
     *
     * `fitCenter` 会把画面等比缩放到控件内并居中，两侧/上下留黑边。
     * 准星要知道这块矩形，才能只在自己身上接收触摸 ——
     * 画到黑边上换算出来是负坐标，写进脚本就是坏值。
     *
     * 和 [map] 用同一套算法，保证准星的位置和最终写入的坐标是同一个点。
     */
    fun imageRect(
        viewWidth: Int,
        viewHeight: Int,
        imageWidth: Int,
        imageHeight: Int
    ): FloatArray {
        if (viewWidth <= 0 || viewHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) {
            return floatArrayOf(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        }
        val scale = min(
            viewWidth.toFloat() / imageWidth,
            viewHeight.toFloat() / imageHeight
        )
        if (scale <= 0f) return floatArrayOf(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())

        val drawnWidth = imageWidth * scale
        val drawnHeight = imageHeight * scale
        val offsetX = (viewWidth - drawnWidth) / 2f
        val offsetY = (viewHeight - drawnHeight) / 2f
        return floatArrayOf(offsetX, offsetY, offsetX + drawnWidth, offsetY + drawnHeight)
    }

    /**
     * @return 虚拟屏坐标；触点落在留边或参数非法时返回 null
     */
    fun map(
        viewX: Float,
        viewY: Float,
        viewWidth: Int,
        viewHeight: Int,
        imageWidth: Int,
        imageHeight: Int
    ): Point? {
        if (viewWidth <= 0 || viewHeight <= 0) return null
        if (imageWidth <= 0 || imageHeight <= 0) return null

        // 与 imageRect 共用同一套缩放/居中算法，避免以后两边算法漂移 ——
        // 那会导致准星显示的位置和实际写入的坐标不是同一个点。
        val r = imageRect(viewWidth, viewHeight, imageWidth, imageHeight)
        val left = r[0]
        val top = r[1]
        val right = r[2]
        val bottom = r[3]

        // 留边判定：用换算前的坐标比，比换算后再判更直观。
        if (viewX < left || viewY < top) return null
        if (viewX > right || viewY > bottom) return null

        val scale = (right - left) / imageWidth
        if (scale <= 0f) return null

        val imageX = (viewX - left) / scale
        val imageY = (viewY - top) / scale

        // 夹到最后一像素以内。浮点误差可能算出 720.0（上界外），
        // 越界坐标注入会被系统丢弃。
        return Point(
            x = imageX.toInt().coerceIn(0, imageWidth - 1),
            y = imageY.toInt().coerceIn(0, imageHeight - 1)
        )
    }
}
