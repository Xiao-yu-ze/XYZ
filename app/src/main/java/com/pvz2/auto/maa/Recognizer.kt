package com.pvz2.auto.maa

import kotlin.math.roundToInt

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
        val rebuilt = HashMap<String, Mat>(originals.size)
        // ★ 必须从 **originals** 重建，不能从 scaled。
        //
        //   之前遍历的是 scaled（上一次的缩放结果），于是每次尺寸变化都在
        //   "已经缩放过"的图上再缩放一次：100px 的逻辑模板先按 scale=1.5
        //   变成 150px，尺寸再变到 scale=2.0 时以 150px 为源 → 300px，
        //   而正确答案是 200px。反复切分辨率还会累积插值失真。
        for ((path, original) in originals) {
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
            is Recognition.Ocr -> matchOcr(frame, recognition, current)
            is Recognition.Color -> matchColor(frame, recognition, current)
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

    /**
     * 把 pipeline 里的 `roi`（720p 逻辑坐标 `[x,y,w,h]`）换成物理像素矩形。
     *
     * ## 为什么需要这一步
     *
     * `RecognizeTarget` / `NodeDef` 的契约都写明"ROI 全是 720p 逻辑坐标"，
     * 而 `matchTemplate` / `matchColor` / `matchOcr` 三个匹配器拿到的 `frame`
     * 是**物理像素**的截图。两者之间必须过 [ScreenAdapter]，否则：
     *
     * - 1280×720 虚拟屏：scale=1、offsetX=0 → 两者恰好相等，看不出问题；
     * - 1080p 手机 / 模拟器：scale=1.5 → ROI 缩在左上角，识别全部落空；
     * - 命中框与点击点同样整体偏移。
     *
     * 旧路径 [TemplateMatcher] 一直是正确调用 `toScreenRect()` 的，
     * 而新写的 Recognizer 层把这一步丢了 —— 属于重构时漏掉的换算，
     * 被 720p 主路线掩盖着。
     *
     * @return 物理像素矩形；ROI 完全在屏外时返回 null
     */
    private fun roiToPhysical(
        roi: IntArray?,
        current: ScreenAdapter,
        frame: Mat
    ): org.opencv.core.Rect? {
        // roi 为空 = 全屏搜索
        if (roi == null || roi.size < 4) {
            return org.opencv.core.Rect(0, 0, frame.width(), frame.height())
        }
        val logical = android.graphics.Rect(
            roi[0], roi[1], roi[0] + roi[2], roi[1] + roi[3]
        )
        return current.toScreenRect(logical)
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

        // ★ ROI 是 **720p 逻辑坐标**，必须先经 ScreenAdapter 换成物理像素。
        //
        //   之前这里调的是 `Boxes.toCvRect(roi, frame.width(), frame.height())` ——
        //   把逻辑值直接当物理像素用。在 1280×720 的虚拟屏上 scale=1、offset=0，
        //   两者恰好相等，所以一直看不出来；一旦跑在 1080p 手机 / 模拟器上，
        //   scale≠1，搜索区会缩在左上角，识别全部落空。
        //
        //   旧路径 `TemplateMatcher` 是正确调用 `toScreenRect()` 的，
        //   新写的 Recognizer 层把它绕过去了 —— 属于重构时丢掉的换算。
        val roi = roiToPhysical(recognition.roi, current, frame)
            ?: return null
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

            // 这里有两套坐标系，务必分清：
            //   boxX/boxY/centerX/centerY —— **物理像素**（roi 和 maxLoc 都是物理的）
            //   Hit.box / Hit.centerX/Y   —— **720p 逻辑坐标**（对外统一口径）
            val boxX = roi.x + mm.maxLoc.x
            val boxY = roi.y + mm.maxLoc.y
            val centerXpx = boxX + template.width() / 2.0
            val centerYpx = boxY + template.height() / 2.0

            val centerLogical = current.toLogical(centerXpx.toFloat(), centerYpx.toFloat())
            val topLeftLogical = current.toLogical(boxX.toFloat(), boxY.toFloat())

            // 模板的**逻辑**尺寸。
            //
            // `template` 是 loadScaled() 返回的**已缩放**图，它的 width/height
            // 是物理像素。之前直接把这两个值当逻辑尺寸用，于是 scale≠1 时
            // 框比实际大出 scale 倍（1.5 倍设备上框放大 50%）。
            //
            // 逻辑尺寸 = 物理尺寸 / scale。用 current.scale 反算，
            // 而不是去 originals 里再查一遍 —— 两者的比值恒等于 scale。
            val logicalWidth = template.width() / current.scale
            val logicalHeight = template.height() / current.scale

            return Hit(
                score = mm.maxVal,
                centerX = centerLogical.x.toDouble(),
                centerY = centerLogical.y.toDouble(),
                box = android.graphics.Rect(
                    topLeftLogical.x.toInt(),
                    topLeftLogical.y.toInt(),
                    (topLeftLogical.x + logicalWidth).toInt(),
                    (topLeftLogical.y + logicalHeight).toInt()
                )
            )
        } finally {
            mask?.release()
            result.release()
            region.release()
        }
    }

    // ---------------------------------------------------------------- 找色

    private fun matchColor(frame: Mat, recognition: Recognition.Color, current: ScreenAdapter): Hit? {
        // ROI 同样是 720p 逻辑坐标，必须换算（见 roiToPhysical 的注释）。
        // 之前这里和 matchTemplate 用同一套"直接当物理像素"的写法，
        // 两层对坐标系的假设都是错的，只是 720p 上互相抵消了。
        val roi = roiToPhysical(recognition.roi, current, frame) ?: return null
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
            // ★ count 是 Maa 的"像素个数下限"，标定在 720p 上。
            //   但这里拿到的是**物理像素**数，而 ROI 的物理面积随 scale² 变化。
            //   720p 下 scale=1，两者恰好相等，所以一直看不出来；
            //   1080p（scale=1.5）同一 ROI 的像素数是 2.25 倍 ——
            //   按 720p 标的阈值在 1080p 上会大面积误命中。
            val needed = recognition.count * current.scale * current.scale
            if (count < needed) return null

            // 命中后返回 ROI 中心。对外统一是 **720p 逻辑坐标**，
            // 所以这里必须把物理中心换算回去 —— 之前直接返回 roi 的物理中心，
            // 在 scale≠1 的设备上调用方点下去会偏。
            val center = current.toLogical(
                (roi.x + roi.width / 2.0).toFloat(),
                (roi.y + roi.height / 2.0).toFloat()
            )
            // 命中框 = 整个 ROI（与 MaaFramework 语义一致）。
            //
            // 之前这里不给 box，默认空 Rect() —— 于是 `target_offset` 在空框上
            // 做偏移，直接算出屏幕外坐标。注意 ColorMatch 的"中心"是 ROI 中心
            // 而不是色块质心，所以「ColorMatch + 点击」点的是整块 ROI 的正中。
            val topLeft = current.toLogical(roi.x.toFloat(), roi.y.toFloat())
            val bottomRight = current.toLogical(
                (roi.x + roi.width).toFloat(),
                (roi.y + roi.height).toFloat()
            )
            return Hit(
                score = count.toDouble(),
                centerX = center.x.toDouble(),
                centerY = center.y.toDouble(),
                box = android.graphics.Rect(
                    topLeft.x.roundToInt(),
                    topLeft.y.roundToInt(),
                    bottomRight.x.roundToInt(),
                    bottomRight.y.roundToInt()
                )
            )
        } finally {
            mask.release()
            hsv.release()
            region.release()
        }
    }

    // ---------------------------------------------------------------- OCR

    private fun matchOcr(frame: Mat, recognition: Recognition.Ocr, current: ScreenAdapter): Hit? {
        val engine = ocr ?: return null
        val roi = roiToPhysical(recognition.roi, current, frame) ?: return null
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
            //
            // ★ 不要在这里写 `val current = adapter ?: return null` —— 那会
            //   **遮蔽同名参数 current**，用到一个可能过期的 adapter。
            //   单线程下两者同值，一旦并发（Pvz2Ui 与 NodeGraph 共用一个
            //   Recognizer）就会用错 scale，命中点整体按错误比例偏移。
            //   用入参就行。
            val frameX = roi.x + matched.centerX
            val frameY = roi.y + matched.centerY
            val logical = current.toLogical(frameX, frameY)

            // 命中框：把 ROI 内的框加回 ROI 原点，再换算成逻辑坐标。
            //
            // 之前这里是空的 Rect()，而 `target_offset` 是"在命中框上叠加
            // 偏移矩形"。空框起步直接算出屏幕外坐标，点击必然落空。
            val boxLeft = current.toLogical(roi.x + matched.boxX, roi.y + matched.boxY)
            val boxRight = current.toLogical(
                roi.x + matched.boxX + matched.boxWidth,
                roi.y + matched.boxY + matched.boxHeight
            )
            return Hit(
                score = matched.confidence,
                centerX = logical.x.toDouble(),
                centerY = logical.y.toDouble(),
                box = android.graphics.Rect(
                    boxLeft.x.roundToInt(),
                    boxLeft.y.roundToInt(),
                    boxRight.x.roundToInt(),
                    boxRight.y.roundToInt()
                ),
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
            // ★ 插值方式要按缩放方向选。
            //
            //   OpenCV 文档明确：INTER_AREA 适合**缩小**（抗混叠）；
            //   强行用于放大时它退化成最近邻，边缘变块状。
            //   720p 上 scale=1，resize 等价拷贝，所以一直没暴露；
            //   1080p 上 scale=1.5 是放大 —— 模板被块状放大，而截图是游戏
            //   真实 1.5 倍渲染，边缘结构对不上，分数系统性下降。
            //   15×15 这种小模板（僵王的头）尤其严重。
            val interp = if (current.scale < 1f) Imgproc.INTER_AREA else Imgproc.INTER_LINEAR
            Imgproc.resize(
                source, target,
                Size(),
                current.scale.toDouble(), current.scale.toDouble(),
                interp
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
