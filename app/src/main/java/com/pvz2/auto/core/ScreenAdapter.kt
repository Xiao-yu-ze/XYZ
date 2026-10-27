package com.pvz2.auto.core

import android.graphics.PointF
import android.graphics.Rect
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * 720p 逻辑坐标 ↔ 设备物理像素的换算。
 *
 * ## 缩放（这部分一直是对的）
 *
 * 参考管线 的 `interface.json` 里写着 `"display_short_side": 720`，即
 * **参考管线 把截图短边归一到 720** 再做识别。横屏下短边就是高，
 * 所以逻辑画布的高度恒为 720、宽度随设备比例浮动：
 *
 *     scale = screenHeight / 720
 *
 * 这也解释了为什么模板能跨分辨率复用 —— 游戏本身用
 * `K = 内容区高 / 600` 等比缩放（见下），归一化到 720 之后，
 * 同一个按钮在任何分辨率下都落在同样的逻辑位置。
 *
 * ## 偏移（★ 这里之前是错的，已按游戏原始算法改正）
 *
 * 旧实现假设「宽度不够就水平居中留边」：
 *
 *     offsetX = (screenWidth - 1280 * scale) / 2     ← 错的
 *
 * 但游戏的原始实现**不是**这么算的。PvZ2 中文版 `libSrc.so` 里
 * `GetScreenContentOffset`（**ELF 0x22E7C24**，92 字节）反汇编如下：
 *
 * ```asm
 * ELF 0x022e7c2c  ldr  s0,[x0, #0x154]      ; mWidth
 * ELF 0x022e7c30  ldr  s2,[x0, #0x2f08]     ; m_contentResolutionWidth
 * ELF 0x022e7c48  fmaxnm s0,s2,s0           ; ★ max(内容区宽, (float)mWidth)
 * ELF 0x022e7c58  sub  w9,w9,w10            ; 屏幕宽 - (int)max(...)
 * ELF 0x022e7c64  cinc w9,w9,lt             ; 负数补偿（向零取整）
 * ELF 0x022e7c68  asr  w9,w9,#0x1           ; ★ 算术右移，不是 /2
 * ```
 *
 * 等价 C：
 * ```c
 * int w = screenW - (int)fmaxf(contentW, (float)mWidth);
 * if (w < 0) w += 1;        // 向零取整补偿
 * offsetX = w >> 1;         // 算术右移
 * ```
 *
 * 其中 `mWidth = (int)(origW * newH / origH)`，而 `newH` 就是屏高、
 * `origW/origH` 就是屏宽高比，所以 **`mWidth` 恒等于屏宽**。
 *
 * 于是：
 * - **宽高比 ≥ 4:3（所有手机、所有横屏模拟器）**：
 *   `contentW = screenH * 4/3 ≤ screenW ≈ mWidth` → `max` 取 `mWidth`
 *   → `w = 0` → **`offsetX = 0`**。游戏内容**左对齐，不做居中留边**。
 * - **宽高比 < 4:3（窄屏）**：`contentW > screenW` → `max` 取 `contentW`
 *   → `offsetX` 为负，内容左右对称裁掉。
 *
 * `offsetY` 同理为 0（因为 `contentH = 屏高`）。
 *
 * ### 为什么这个差异非改不可
 *
 * 只影响**固定逻辑坐标的点击**和 **ROI**，不影响「识别到哪就点哪」：
 * - 「识别→点击命中位置」的换算里 offset 会**来回抵消**，所以旧实现看起来能用；
 * - 但 `coords.json` 里的固定点（如 `加速 (1171,35)`）和 pipeline 里的
 *   `roi`，offset 不抵消。在 2400×1080 上旧实现整整偏 **240 px**。
 *
 * 而 BatchSwipe（种植引擎）正是靠固定坐标驱动的 —— 不修的话移植完必错。
 *
 * ### 已知未实现
 *
 * 超长屏特例（宽高比 ≥ 2.1）在游戏里走 `FUN_023e7850` 分支做额外微调。
 * 该函数的设备判定条件尚未闭合（逆向文档列为待验项），
 * **这里不实现** —— 没有确证的算法宁可不要，免得引入猜测性偏移。
 *
 * > 依据：`pvz2算法/算法/分辨率适配算法.md`（A 级实证，全部来自原始反汇编）
 */
class ScreenAdapter(
    val screenWidth: Int,
    val screenHeight: Int
) {
    /** 物理像素 / 逻辑单位。 */
    val scale: Float = screenHeight / BASE_HEIGHT.toFloat()

    /**
     * 逻辑 x = 0 对应的物理 X。
     *
     * 按游戏的 `GetScreenContentOffset` 算，**不是** `(screenWidth - 1280*scale)/2`。
     * 宽高比 ≥ 4:3 时结果恒为 0。
     */
    val offsetX: Float = gameContentOffsetX(screenWidth, screenHeight)

    /** 逻辑 y = 0 对应的物理 Y。恒为 0（内容区高 == 屏高）。 */
    val offsetY: Float = gameContentOffsetY(screenHeight)

    /** 逻辑点 -> 物理像素。 */
    fun toScreen(logicalX: Float, logicalY: Float): PointF =
        PointF(offsetX + logicalX * scale, offsetY + logicalY * scale)

    /** 物理像素 -> 逻辑点。 */
    fun toLogical(screenX: Float, screenY: Float): PointF =
        PointF((screenX - offsetX) / scale, (screenY - offsetY) / scale)

    /**
     * 逻辑矩形 -> 物理矩形，向外取整并裁剪到屏幕内。
     * 完全落在屏幕外时返回 null。
     */
    fun toScreenRect(logical: Rect): org.opencv.core.Rect? {
        val left = floor(offsetX + logical.left * scale).toInt().coerceIn(0, screenWidth)
        val top = floor(offsetY + logical.top * scale).toInt().coerceIn(0, screenHeight)
        val right = ceil(offsetX + logical.right * scale).toInt().coerceIn(0, screenWidth)
        val bottom = ceil(offsetY + logical.bottom * scale).toInt().coerceIn(0, screenHeight)

        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return null
        return org.opencv.core.Rect(left, top, width, height)
    }

    override fun toString(): String =
        "ScreenAdapter(${screenWidth}x$screenHeight scale=$scale offset=$offsetX,$offsetY)"

    companion object {
        /** 模板的创作基准。逻辑画布高度恒为此值（短边归一 720）。 */
        const val BASE_HEIGHT = 720

        /**
         * 模板创作基准宽度，仅用于说明素材来源（16:9 设备上的截图）。
         *
         * **不参与偏移计算** —— 非 16:9 设备上逻辑画布的宽度不等于它，
         * 旧实现把它当固定宽度去算居中留边，正是这次修掉的错误。
         */
        const val BASE_WIDTH = 1280

        /** 游戏里硬编码的内容区宽高比 `1.3333334f`（ELF 0x22E40BC）。 */
        private const val CONTENT_ASPECT = 4f / 3f

        /**
         * `GetScreenContentOffset` 的 X 分量（ELF 0x22E7C24）。
         *
         * 严格照搬原实现的整数语义：`(int)` 截断 + 负数补偿 + 算术右移。
         */
        private fun gameContentOffsetX(w: Int, h: Int): Float {
            val contentW = h * CONTENT_ASPECT            // m_contentResolutionWidth
            val mWidth = w                               // = (int)(origW * h / origH) == 屏宽
            var delta = w - max(contentW, mWidth.toFloat()).toInt()
            if (delta < 0) delta += 1                    // 向零取整补偿
            return (delta shr 1).toFloat()               // 算术右移
        }

        /**
         * `GetScreenContentOffset` 的 Y 分量。
         *
         * `contentH = mHeight = 屏高`，所以差值恒为 0。留着算式是为了
         * 跟原实现一一对应，将来若内容区高不再等于屏高也不会漏改。
         */
        private fun gameContentOffsetY(h: Int): Float {
            var delta = h - h                            // (int)(屏高 - m_contentResolutionHeight)
            if (delta < 0) delta += 1
            return (delta shr 1).toFloat()
        }
    }
}
