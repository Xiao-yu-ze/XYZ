package com.pvz2.auto.maa

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.pvz2.auto.core.ScreenAdapter
import com.pvz2.auto.core.argb8888Options
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * 执行 [Recognition]，返回 720p 逻辑坐标下的命中结果。
 *
 * 模板在首次使用时按当前屏幕尺寸缩放并缓存（`INTER_AREA`），
 * 和 [com.pvz2.auto.core.TemplateMatcher] 采用同一套缩放策略：
 * 高度对齐、水平居中的等比缩放，基准 1280x720。
 *
 * 非线程安全：由单个自动化循环协程独占，因此不需要加锁。
 */
class Recognizer(private val context: Context) {

    /** 模板原图（720p 基准），key = assets 内的相对路径。 */
    private val originals = HashMap<String, Mat>()

    /** 按当前屏幕缩放后的模板，屏幕尺寸变化时重建。 */
    private var scaled = HashMap<String, Mat>()

    /** 当前屏幕适配器；为 null 表示还没抓过帧。 */
    var adapter: ScreenAdapter? = null
        private set

    /** 可选的 OCR 引擎；未注入时所有 OCR 识别都返回未命中。 */
    var ocr: OcrEngine? = null

    val loadedTemplates: Int get() = originals.size

    /**
     * 指向当前屏幕尺寸。
     *
     * @return true 表示尺寸变化并重建了模板缓存。
     */
    fun setScreen(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        val current = adapter
        if (current != null && current.screenWidth == width && current.screenHeight == height) {
            return false
        }

        val next = ScreenAdapter(width, height)
        val rebuilt = HashMap<String, Mat>(scaled.size)
        for ((path, original) in scaled) {
            rebuilt[path] = scale(original, next) ?: continue
        }

        releaseScaled()
        scaled = rebuilt
        adapter = next
        Log.i(TAG, "$next，已重建 ${scaled.size} 个模板")
        return true
    }

    /** 识别一帧。命中返回 [Hit]，未命中或无适配器时返回 null。 */
    fun recognize(frame: Mat, recognition: Recognition): Hit? {
        val current = adapter ?: return null
        return recognize(frame, recognition, current)
    }

    private fun recognize(frame: Mat, recognition: Recognition, current: ScreenAdapter): Hit? {
        if (frame.empty()) return null
        return when (recognition) {
            is Recognition.Template -> matchTemplate(frame, recognition, current)
            is Recognition.Ocr -> matchOcr(frame, recognition)
            is Recognition.Color -> matchColor(frame, recognition)
            is Recognition.AnyOf -> recognition.items
                .firstNotNullOfOrNull { recognize(frame, it, current) }
            Recognition.DirectHit -> Hit(1.0, 0.0, 0.0)
        }
    }

    // ------------------------------------------------------------ 模板匹配

    private fun matchTemplate(
        frame: Mat,
        recognition: Recognition.Template,
        current: ScreenAdapter
    ): Hit? {
        if (recognition.templates.isEmpty()) return null

        // 多个模板时按顺序尝试，任一命中即返回（对应 Maa 的 template 数组语义）。
        var best: Hit? = null
        for (path in recognition.templates) {
            val hit = matchOne(frame, path, recognition, current) ?: continue
            if (best == null || hit.score > best.score) best = hit
            // 已经足够好就不必再看后面的模板。
            if (best.score >= TEMPLATE_SHORT_CIRCUIT) break
        }
        return best
    }

    private fun matchOne(
        frame: Mat,
        path: String,
        recognition: Recognition.Template,
        current: ScreenAdapter
    ): Hit? {
        val template = loadScaled(path, current) ?: return null
        if (template.empty()) return null
        if (template.width() > frame.width() || template.height() > frame.height()) return null

        val roi = Boxes.toCvRect(recognition.roi, frame.width(), frame.height())
        if (roi.width < template.width() || roi.height < template.height()) return null

        val region = frame.submat(roi)
        val result = Mat()
        var mask: Mat? = null
        try {
            if (recognition.greenMask) mask = GreenMask.build(template)

            if (mask != null) {
                Imgproc.matchTemplate(region, template, result, recognition.method, mask)
            } else {
                Imgproc.matchTemplate(region, template, result, recognition.method)
            }

            val mm = Core.minMaxLoc(result)
            if (mm.maxVal < recognition.threshold) return null

            // maxLoc 是相对 ROI 的最佳对齐左上角，转成 ROI 内的中心再映射回逻辑坐标。
            val boxX = roi.x + mm.maxLoc.x
            val boxY = roi.y + mm.maxLoc.y
            val centerX = boxX + template.width() / 2.0
            val centerY = boxY + template.height() / 2.0

            val logical = current.toLogical(centerX.toFloat(), centerY.toFloat())
            val topLeft = current.toLogical(boxX.toFloat(), boxY.toFloat())
            val size = template.width().toFloat() * current.scale
            val sizeY = template.height().toFloat() * current.scale

            return Hit(
                score = mm.maxVal,
                centerX = logical.x.toDouble(),
                centerY = logical.y.toDouble(),
                box = android.graphics.Rect(
                    topLeft.x.toInt(), topLeft.y.toInt(),
                    (topLeft.x + size / current.scale).toInt(),
                    (topLeft.y + sizeY / current.scale).toInt()
                )
            )
        } finally {
            mask?.release()
            result.release()
            region.release()
        }
    }

    // ---------------------------------------------------------------- 找色

    private fun matchColor(frame: Mat, recognition: Recognition.Color): Hit? {
        val roi = Boxes.toCvRect(recognition.roi, frame.width(), frame.height())
        val region = frame.submat(roi)
        val hsv = Mat()
        val mask = Mat()
        try {
            if (recognition.method == COLOR_METHOD_HSV) {
                Imgproc.cvtColor(region, hsv, Imgproc.COLOR_BGR2HSV)
            } else {
                region.copyTo(hsv)
            }
            Core.inRange(
                hsv,
                org.opencv.core.Scalar(DoubleArray(recognition.lower.size) { recognition.lower[it].toDouble() }),
                org.opencv.core.Scalar(DoubleArray(recognition.upper.size) { recognition.upper[it].toDouble() }),
                mask
            )
            val count = Core.countNonZero(mask)
            if (count < recognition.count) return null

            // 命中后返回 ROI 中心：调用方只关心"这段区域是不是这个颜色"。
            val cx = roi.x + roi.width / 2.0
            val cy = roi.y + roi.height / 2.0
            return Hit(count.toDouble(), cx, cy)
        } finally {
            mask.release()
            hsv.release()
            region.release()
        }
    }

    // ---------------------------------------------------------------- OCR

    private fun matchOcr(frame: Mat, recognition: Recognition.Ocr): Hit? {
        val engine = ocr ?: return null
        val roi = Boxes.toCvRect(recognition.roi, frame.width(), frame.height())
        val region = frame.submat(roi)
        try {
            val lines = engine.recognize(region, recognition.threshold)
            if (lines.isEmpty()) return null

            // expected 里每项都是正则；为空表示"任意文字"（MaaPVZ 用它做
            // "这个区域里有没有字"的判断，例如无尽_切换编队序号）。
            val wanted = recognition.expected
            val matched = if (wanted.isEmpty()) {
                lines.first()
            } else {
                lines.firstOrNull { line ->
                    wanted.any { pattern ->
                        runCatching { Regex(pattern).containsMatchIn(line.text) }
                            .getOrDefault(false)
                    }
                }
            } ?: return null

            // OCR 返回的是 ROI 内坐标，换算回逻辑坐标。
            val current = adapter ?: return null
            val frameX = roi.x + matched.centerX
            val frameY = roi.y + matched.centerY
            val logical = current.toLogical(frameX, frameY)
            return Hit(
                score = matched.confidence,
                centerX = logical.x.toDouble(),
                centerY = logical.y.toDouble(),
                text = matched.text
            )
        } finally {
            region.release()
        }
    }

    // ------------------------------------------------------------ 模板加载

    private fun loadScaled(path: String, current: ScreenAdapter): Mat? {
        scaled[path]?.let { return it }

        val original = originals[path] ?: loadOriginal(path) ?: return null
        val resized = scale(original, current) ?: return null
        scaled[path] = resized
        return resized
    }

    private fun scale(source: Mat, current: ScreenAdapter): Mat? {
        if (source.empty()) return null
        val target = Mat()
        return try {
            Imgproc.resize(
                source, target,
                Size(),
                current.scale.toDouble(), current.scale.toDouble(),
                Imgproc.INTER_AREA
            )
            if (target.empty()) {
                target.release()
                null
            } else {
                target
            }
        } catch (t: Throwable) {
            target.release()
            Log.w(TAG, "缩放模板失败", t)
            null
        }
    }

    private fun loadOriginal(path: String): Mat? {
        return try {
            val bitmap = context.assets.open(path).use { stream ->
                BitmapFactory.decodeStream(stream, null, argb8888Options())
            }
            if (bitmap == null) {
                Log.w(TAG, "无法解码模板：$path")
                return null
            }
            val mat = Mat()
            Utils.bitmapToMat(bitmap, mat)
            bitmap.recycle()
            originals[path] = mat
            mat
        } catch (t: Throwable) {
            Log.w(TAG, "无法加载模板：$path", t)
            null
        }
    }

    // ---------------------------------------------------------------- 资源

    fun release() {
        releaseScaled()
        for (mat in originals.values) mat.release()
        originals.clear()
        adapter = null
    }

    private fun releaseScaled() {
        for (mat in scaled.values) mat.release()
        scaled.clear()
    }

    companion object {
        private const val TAG = "Recognizer"

        /** 找到足够好的匹配就不再尝试同节点的其它模板。 */
        private const val TEMPLATE_SHORT_CIRCUIT = 0.95

        /** MaaPVZ 的 ColorMatch method=40 表示 BGR2HSV。 */
        const val COLOR_METHOD_HSV = 40
    }
}
