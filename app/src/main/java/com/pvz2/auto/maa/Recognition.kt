package com.pvz2.auto.maa

import android.graphics.Rect
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/** 识别方式。只实现 MaaPVZ 无尽挑战流程实际用到的几种。 */
sealed interface Recognition {

    /** 区域 `[x, y, w, h]`，720p 逻辑坐标；null 表示全屏。 */
    val roi: IntArray?

    /** 模板匹配。对应 MaaFramework 的 `TemplateMatch`。 */
    data class Template(
        val templates: List<String>,
        override val roi: IntArray? = null,
        val threshold: Double = 0.7,
        /** 绿色掩码：模板中纯绿 (0,255,0) 的像素不参与匹配。 */
        val greenMask: Boolean = false,
        /** OpenCV 匹配算法，默认 5 = TM_CCOEFF_NORMED。 */
        val method: Int = 5
    ) : Recognition

    /**
     * 纯色区域匹配。对应 `ColorMatch`，用于"找一段纯红色"这类判断。
     * method 40 = BGR2HSV（MaaPVZ 用它做纯红检测）。
     */
    data class Color(
        override val roi: IntArray? = null,
        val method: Int = 40,
        val lower: IntArray,
        val upper: IntArray,
        val count: Int = 1
    ) : Recognition

    /** OCR 文字识别。对应 `OCR`，expected 里每项都是正则。 */
    data class Ocr(
        val expected: List<String>,
        override val roi: IntArray? = null,
        val threshold: Double = 0.3
    ) : Recognition

    /** 逻辑或。命中任一子项即可。对应 `Or`。 */
    data class AnyOf(val items: List<Recognition>) : Recognition {
        override val roi: IntArray? get() = null
    }

    /** 直接命中，不做识别。对应 `DirectHit`，也是节点不写 recognition 时的默认值。 */
    data object DirectHit : Recognition {
        override val roi: IntArray? get() = null
    }
}

/** 一次识别的结果。 */
data class Hit(
    val score: Double,
    /** 命中框中心，720p 逻辑坐标。 */
    val centerX: Double,
    val centerY: Double,
    /** 命中框（左上角 + 宽高），720p 逻辑坐标；DirectHit 时为空框。 */
    val box: Rect = Rect(),
    /** OCR 命中的文本；其它识别方式为 null。 */
    val text: String? = null
)

/**
 * 识别结果 -> 用于点击的目标点。
 *
 * MaaFramework 的语义：
 *   `roi`     在哪里找
 *   `box`     找到了哪里
 *   `target`  点哪里，默认 = box 的中心
 */
object Targets {

    /**
     * 把 `target` 字段转成实际点击坐标（720p 逻辑）。
     *
     * @param target null 表示默认（用命中框中心）；
     *               `[x,y]` 表示绝对点；`[x,y,w,h]` 表示绝对矩形（取中心）。
     */
    fun resolve(hit: Hit, target: IntArray?): Pair<Double, Double> {
        if (target == null) return hit.centerX to hit.centerY
        return when (target.size) {
            2 -> target[0].toDouble() to target[1].toDouble()
            4 -> (target[0] + target[2] / 2.0) to (target[1] + target[3] / 2.0)
            else -> hit.centerX to hit.centerY
        }
    }

    /** 在命中框基础上叠加偏移，返回中心点。 */
    fun applyOffset(hit: Hit, offset: IntArray?): Pair<Double, Double> {
        if (offset == null || offset.size < 4) return hit.centerX to hit.centerY
        val box = hit.box
        val cx = box.left + offset[0] + (box.width() + offset[2]) / 2.0
        val cy = box.top + offset[1] + (box.height() + offset[3]) / 2.0
        return cx to cy
    }
}

/**
 * 坐标 / 矩形工具。MaaFramework 的 ROI 是 `[x, y, w, h]`（左上角 + 宽高），
 * 和 Android 的 [Rect]（left, top, right, bottom）语义不同，两者混用极易出错，
 * 所以统一在这里转换。
 */
object Boxes {

    /** `[x,y,w,h]` -> OpenCV Rect；null 或非法时返回全屏。 */
    fun toCvRect(roi: IntArray?, frameWidth: Int, frameHeight: Int): org.opencv.core.Rect {
        if (roi == null || roi.size < 4) {
            return org.opencv.core.Rect(0, 0, frameWidth, frameHeight)
        }
        // 裁剪到画面内，避免非法 ROI 让 matchTemplate 抛异常。
        val x = roi[0].coerceIn(0, maxOf(0, frameWidth - 1))
        val y = roi[1].coerceIn(0, maxOf(0, frameHeight - 1))
        val w = roi[2].coerceIn(1, frameWidth - x)
        val h = roi[3].coerceIn(1, frameHeight - y)
        return org.opencv.core.Rect(x, y, w, h)
    }

    /** `[x,y,w,h]` -> 中心点，720p 逻辑坐标。 */
    fun centerOf(roi: IntArray): Pair<Double, Double> =
        (roi[0] + roi[2] / 2.0) to (roi[1] + roi[3] / 2.0)

    fun describe(roi: IntArray?): String =
        roi?.takeIf { it.size >= 4 }?.let { "[${it[0]},${it[1]},${it[2]},${it[3]}]" } ?: "全屏"
}

/** 生成绿色掩码：把模板里纯绿 (0,255,0) 的像素标记为不参与匹配。 */
internal object GreenMask {

    /**
     * @return 掩码 Mat（CV_8UC1，255=参与匹配），或 null 表示不需要掩码
     *         （模板里没有绿色像素，或模板本身太小）。
     */
    fun build(template: Mat): Mat? {
        if (template.empty()) return null
        val mask = Mat()
        val lower = org.opencv.core.Scalar(0.0, 250.0, 0.0, 0.0)
        val upper = org.opencv.core.Scalar(10.0, 255.0, 10.0, 255.0)

        // 模板是 CV_8UC4，通道序 **R,G,B,A**（`bitmapToMat` 原样搬运，不交换）。
        // 要拿真正的 BGR 必须用 `COLOR_RGBA2BGR`（= 交换）。
        //
        // 之前写的 `COLOR_BGRA2BGR` 是"丢 alpha、不交换"，对 RGBA 输入得到的是
        // **RGB 顺序**的 3 通道，只是变量名叫 bgr 而已。这一处没炸是因为下游
        // 找的是纯绿 (0,255,0) —— R 与 B 对称，交换与否结果相同。
        // 属于"巧合正确"，不是写对了。
        val bgr = Mat()
        try {
            Imgproc.cvtColor(template, bgr, Imgproc.COLOR_RGBA2BGR)
            org.opencv.core.Core.inRange(bgr, lower, upper, mask)
            // inRange 给的是"绿色位置=255"，掩码语义正好相反，取反。
            org.opencv.core.Core.bitwise_not(mask, mask)

            // 数绿色像素：mask 取反后"非绿=255"，所以绿色数 = 总数 - countNonZero。
            //
            // 之前是另外建一个 Mat 再做一次 inRange 去数，而那个临时 Mat
            // 是 `Mat().also { ... }` 写出来的，**没有任何引用能 release 它**。
            // Mat 是 native 内存，长时间自动化里每轮匹配漏一块，最终 OOM。
            // 这里复用已有的 mask，一个临时对象都不需要。
            val total = bgr.rows() * bgr.cols()
            val greenPixels = total - org.opencv.core.Core.countNonZero(mask)
            // 绿色像素太少说明这不是掩码模板，用掩码反而降低匹配率。
            if (greenPixels <= 4) {
                mask.release()
                return null
            }
            return mask
        } catch (t: Throwable) {
            mask.release()
            return null
        } finally {
            bgr.release()
        }
    }
}
