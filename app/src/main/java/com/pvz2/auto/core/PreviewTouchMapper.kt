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

        val scale = min(
            viewWidth.toFloat() / imageWidth,
            viewHeight.toFloat() / imageHeight
        )
        if (scale <= 0f) return null

        val drawnWidth = imageWidth * scale
        val drawnHeight = imageHeight * scale
        val offsetX = (viewWidth - drawnWidth) / 2f
        val offsetY = (viewHeight - drawnHeight) / 2f

        // 留边判定：用换算前的坐标比，比换算后再判更直观。
        if (viewX < offsetX || viewY < offsetY) return null
        if (viewX > offsetX + drawnWidth || viewY > offsetY + drawnHeight) return null

        val imageX = (viewX - offsetX) / scale
        val imageY = (viewY - offsetY) / scale

        // 夹到最后一像素以内。浮点误差可能算出 720.0（上界外），
        // 越界坐标注入会被系统丢弃。
        return Point(
            x = imageX.toInt().coerceIn(0, imageWidth - 1),
            y = imageY.toInt().coerceIn(0, imageHeight - 1)
        )
    }
}
