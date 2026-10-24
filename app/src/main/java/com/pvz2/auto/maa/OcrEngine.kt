package com.pvz2.auto.maa

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/** 一行 OCR 结果。坐标是传入图像内的像素坐标。 */
data class OcrLine(
    val text: String,
    val confidence: Double,
    val centerX: Float,
    val centerY: Float,
    /**
     * 文本框在**传入图像（即 ROI）内**的左上角与尺寸。
     *
     * 为什么要带出来：`target_offset` 的语义是"在命中框上叠加偏移"，
     * 而 `Hit.box` 对 OCR 结果原本是空的 —— 空框上做偏移会算出屏幕外坐标
     * （例如 `无尽挑战_选取神器` 的 offset [12,69,-58,-21] 会得到 cx=-17）。
     */
    val boxX: Float = 0f,
    val boxY: Float = 0f,
    val boxWidth: Float = 0f,
    val boxHeight: Float = 0f,
)

/**
 * PaddleOCR PP-OCRv5 mobile 推理（det 检测 + rec 识别）。
 *
 * 模型来自 MaaPVZ 的 `resource/model/ocr/`，见 assets/ocr/README.md：
 *   det.onnx  PP-OCRv5_mobile_det
 *   rec.onnx  PP-OCRv5_mobile_rec
 *   keys.txt  rec 的 CTC 字典，18383 行
 *
 * 两个模型的输入都叫 `x`，输出都叫 `fetch_name_0`（已解析 onnx 确认）。
 *
 * 只在自动化循环的 IO 协程里使用，非线程安全。
 */
class OcrEngine(private val context: Context) : AutoCloseable {

    private var env: OrtEnvironment? = null
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private var charset: List<String> = emptyList()

    val isReady: Boolean get() = detSession != null && recSession != null

    /** 加载模型与字典。加载失败不抛异常，只是 [isReady] 保持 false，OCR 识别全部返回未命中。 */
    fun load(assetDir: String = DEFAULT_DIR) {
        close()
        try {
            charset = loadCharset("$assetDir/$KEYS_FILE")
            Log.i(TAG, "字典字符数 = ${charset.size}")

            val environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }

            detSession = environment.createSession(readAsset("$assetDir/$DET_FILE"), options)
            recSession = environment.createSession(readAsset("$assetDir/$REC_FILE"), options)
            env = environment

            Log.i(TAG, "OCR 已就绪：${detSession?.inputNames} / ${recSession?.inputNames}")
        } catch (t: Throwable) {
            Log.e(TAG, "OCR 加载失败，将跳过所有文字识别", t)
            close()
        }
    }

    /**
     * 读取 CTC 字典。
     *
     * **必须逐行剥掉 `\r`**：keys.txt 是 CRLF 换行（18383 处），若直接用
     * `readLines()`，Kotlin 只会按 `\n` 切分并保留行尾的 `\r`，
     * 于是字典里每个字符都变成 "字\r"，识别出的文本会带满回车 —— 而
     * `expected` 正则永远匹配不上，表现为"OCR 完全没反应"，极难排查。
     *
     * 另外末尾的空行要去掉：模型输出类别数是
     * `1(CTC blank) + 18383(字典) + 1(PaddleOCR 追加的空格) = 18385`，
     * 索引 1..18383 映射 charset[0..18382]，落差的那一位是句尾空格，
     * 不作为可打印字符输出。
     */
    private fun loadCharset(path: String): List<String> =
        context.assets.open(path).bufferedReader(Charsets.UTF_8)
            .readLines()
            .map { it.trimEnd('\r', '\n') }
            .filter { it.isNotEmpty() }

    private fun readAsset(path: String): ByteArray =
        context.assets.open(path).use { it.readBytes() }

    /**
     * 识别整块区域内的所有文字。
     *
     * @param region BGR / BGRA Mat（截图裁剪后的区域）
     * @param minConfidence rec 置信度下限
     */
    fun recognize(region: Mat, minConfidence: Double): List<OcrLine> {
        val environment = env ?: return emptyList()
        val det = detSession ?: return emptyList()
        val rec = recSession ?: return emptyList()
        if (region.empty()) return emptyList()

        /*
         * ★ 一次识别不成，就换几种"看这张图的方式"再试。
         *
         *   PP-OCR 的检测头是在"浅底深字"的文档图上训出来的。
         *   游戏里大量按钮是**深底浅字**，或者字与背景亮度接近 ——
         *   这时检测概率图整体偏弱，二值化后一个框都没有，
         *   于是报"这一块里没识别出任何文字"，而画面里明明全是字。
         *   用户会去调相似度阈值，越调越偏。
         *
         *   四种看法，顺序有讲究：
         *     ① 原图      —— 绝大多数情况一次就成，不增加开销
         *     ② 反色      —— 深底浅字 → 浅底深字，最有效的一次补试
         *     ③ 灰度拉伸  —— 字与背景对比度低时，把动态范围拉到 0~255
         *     ④ 反色拉伸  —— 两种组合
         *   命中即返回，不做无谓的后续尝试。
         */
        val variants = ArrayList<Pair<String, Mat>>(4)
        fun add(name: String, m: Mat) {
            if (!m.empty()) variants.add(name to m)
        }
        return try {
            add("原图", region)
            add("反色", invertMat(region))
            add("灰度拉伸", stretchMat(region))
            add("反色拉伸", stretchMat(invertMat(region)))

            for ((name, m) in variants) {
                val lines = try {
                    val boxes = detect(environment, det, m)
                    boxes.mapNotNull { box -> recognizeBox(environment, rec, m, box, minConfidence) }
                } catch (t: Throwable) {
                    Log.w(TAG, "OCR 检测失败（$name）", t)
                    emptyList()
                }
                if (lines.isNotEmpty()) {
                    if (name != "原图") Log.i(TAG, "OCR 用「$name」命中 ${lines.size} 行")
                    return lines
                }
            }

            /*
             * ★ 整块兜底：检测框没框出来时，直接把整个区域当成一行文字送去识别。
             *
             *   用户框的往往就是一个按钮，区域里本来就只有一行字 ——
             *   这时检测是多余的，识别网络单独也能认。
             *   只对小区域启用：大区域整块送识别会出乱码，而且慢。
             */
            if (region.width() <= WHOLE_MAX_W && region.height() <= WHOLE_MAX_H) {
                val whole = TextBox(0, 0, region.width(), region.height())
                for ((name, m) in variants) {
                    val line = try {
                        recognizeBox(environment, rec, m, whole, minConfidence)
                    } catch (t: Throwable) {
                        Log.w(TAG, "OCR 整块识别失败（$name）", t)
                        null
                    }
                    if (line != null) {
                        Log.i(TAG, "OCR 整块兜底命中（$name）：${line.text}")
                        return listOf(line)
                    }
                }
            }
            emptyList()
        } finally {
            // 中间图必须显式释放：识别是 200ms 级循环，漏一块就攒一块
            for ((name, m) in variants) {
                if (name != "原图") runCatching { m.release() }
            }
        }
    }

    /** 反色：深底浅字 ↔ 浅底深字。 */
    private fun invertMat(src: Mat): Mat {
        val out = Mat()
        return runCatching { org.opencv.core.Core.bitwise_not(src, out); out }
            .getOrElse { runCatching { out.release() }; Mat() }
    }

    /**
     * 灰度 + 对比度拉伸到 0~255。
     *
     * 字与背景亮度接近时检测头的概率图很弱，先把动态范围拉开，
     * 相当于替它做一步增强。输出 BGR 三通道，检测与识别两条路径都吃。
     */
    private fun stretchMat(src: Mat): Mat {
        val gray = Mat()
        val norm = Mat()
        val out = Mat()
        val ok = runCatching {
            val code = if (src.channels() == 4) Imgproc.COLOR_RGBA2GRAY else Imgproc.COLOR_BGR2GRAY
            Imgproc.cvtColor(src, gray, code)
            org.opencv.core.Core.normalize(gray, norm, 0.0, 255.0, org.opencv.core.Core.NORM_MINMAX)
            Imgproc.cvtColor(norm, out, Imgproc.COLOR_GRAY2BGR)
        }.isSuccess
        runCatching { gray.release() }
        runCatching { norm.release() }
        if (!ok) { runCatching { out.release() }; return Mat() }
        return out
    }

    // ------------------------------------------------------------ 文本检测

    private data class TextBox(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
        val width get() = x1 - x0
        val height get() = y1 - y0
    }

    /**
     * DB 文本检测。
     *
     * 先缩放到 [DET_LIMIT] 限定的尺寸（保持长宽比，边长对齐到 32 的倍数），
     * 推理后把概率图按同样比例放回原尺寸，再二值化找出文本框。
     */
    private fun detect(environment: OrtEnvironment, session: OrtSession, region: Mat): List<TextBox> {
        val srcW = region.width()
        val srcH = region.height()

        /*
         * 缩放比例。
         *
         * 标准 PP-OCR 预处理只会**下采样**（长边限到 960），不会上采样。
         * 但脚本里的搜索范围经常很小 —— 比如「开始战斗」按钮在我们
         * 1280×720 的画面上只有 201×59。det 模型拿到这么一小撮像素，
         * 特征根本不够，表现就是"识别到一行却读成乱码"（实测读成「HN」）。
         *
         * 所以小图要放大：长边不足 [DET_MIN_SIDE] 时按比例放大，
         * 上限 [DET_UPSCALE_MAX] 倍。文本框坐标最后会按同一比例缩回原尺寸，
         * 见下方 resize(mask → srcW×srcH)，与 ratio 无关。
         */
        val maxSide = max(srcW, srcH)
        val ratio = when {
            maxSide > DET_LIMIT -> DET_LIMIT.toDouble() / maxSide
            maxSide in 1 until DET_MIN_SIDE ->
                kotlin.math.min(DET_UPSCALE_MAX, DET_MIN_SIDE.toDouble() / maxSide)
            else -> 1.0
        }
        val dstW = (srcW * ratio).roundToInt().let { alignUp(it, 32) }.coerceAtLeast(32)
        val dstH = (srcH * ratio).roundToInt().let { alignUp(it, 32) }.coerceAtLeast(32)

        val resized = Mat()
        val rgb = Mat()
        try {
            Imgproc.resize(region, resized, Size(dstW.toDouble(), dstH.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)

            /*
             * ★ det 要 **BGR**，不是 RGB。
             *
             * PaddleOCR 的检测管线是 cv2.imread（BGR）→ Resize → Normalize，
             * 中间**不转 RGB**，所以 ImageNet 的 mean[0]=0.485 是对 B 通道的。
             * 我们若把 R 当第一通道喂进去，均值和标准差就错位到 B/R 上。
             *
             * rec（识别）用的是对称的 0.5/0.5，通道顺序不影响，那边保持 RGB。
             */
            val code = if (resized.channels() == 4) Imgproc.COLOR_RGBA2BGR else -1
            if (code >= 0) Imgproc.cvtColor(resized, rgb, code) else resized.copyTo(rgb)

            val input = MatToTensor.nchw(rgb, DET_MEAN_RGB, DET_STD_RGB)
            OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(input),
                longArrayOf(1, 3, dstH.toLong(), dstW.toLong())
            ).use { tensor ->
                session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val out = result[0].value as Array<Array<Array<FloatArray>>>
                    val prob = out[0][0]   // [dstH][dstW]

                    // 概率图还原到原图尺寸后再找框，避免缩放误差累积到坐标上。
                    val mask = Mat(dstH, dstW, org.opencv.core.CvType.CV_8UC1)
                    try {
                        // 逐行打包成 byte[] 后用 Mat.put 写入。
                        // 不能用 mask.data —— 那是公开的 long 型 native 指针字段，
                        // 不是 ByteBuffer。
                        val rowBytes = ByteArray(dstW)
                        for (y in 0 until dstH) {
                            val row = prob[y]
                            for (x in 0 until dstW) {
                                rowBytes[x] = if (row[x] >= DET_BINARY_THRESHOLD) 0xFF.toByte() else 0
                            }
                            mask.put(y, 0, rowBytes)
                        }
                        return findBoxes(mask, srcW, srcH, ratio)
                    } finally {
                        mask.release()
                    }
                }
            }
        } finally {
            rgb.release()
            resized.release()
        }
    }

    /**
     * 从二值概率图里提取文本框。
     *
     * 简化实现：用形态学膨胀把同一行的字连起来，再取连通域外接矩形。
     * 相比完整 DB 后处理（轮廓 + 分数筛选 + unclip），这里少了 unclip 膨胀，
     * 对"按钮上的一行短文字"这种场景足够，也是 MaaPVZ 实际用到的全部情形。
     */
    private fun findBoxes(mask: Mat, srcW: Int, srcH: Int, ratio: Double): List<TextBox> {
        val scaled = Mat()
        val hierarchy = Mat()
        try {
            Imgproc.resize(mask, scaled, Size(srcW.toDouble(), srcH.toDouble()), 0.0, 0.0, Imgproc.INTER_NEAREST)

            // 横向膨胀，把同一行的字符连成一个框。
            val kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(DILATE_WIDTH.toDouble(), DILATE_HEIGHT.toDouble())
            )
            try {
                Imgproc.dilate(scaled, scaled, kernel)
            } finally {
                kernel.release()
            }

            val contours = ArrayList<org.opencv.core.MatOfPoint>()
            Imgproc.findContours(scaled, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            val boxes = ArrayList<TextBox>()
            for (contour in contours) {
                val r = Imgproc.boundingRect(contour)
                if (r.width < MIN_BOX_WIDTH || r.height < MIN_BOX_HEIGHT) continue

                /*
                 * ★ DB 的 unclip —— 这一步不能省。
                 *
                 * DB（Differentiable Binarization）训练时，概率图的目标是
                 * **按 unclip_ratio 收缩过的文字核**，不是文字的完整外接框。
                 * 官方后处理因此必须反过来把框**扩大**：
                 *
                 *     distance = area * unclip_ratio / perimeter
                 *     把框四边各外扩 distance
                 *
                 * 不做这一步，裁出来的是"字的中间一条带"：
                 * 中文上下结构（例如「维信」）上下都被切掉，
                 * 识别模型只能看到水平中段笔画，输出的就是
                 * 「CADHSCADHSE」这种乱码 —— 之前一直误以为是模型不行。
                 *
                 * 原来的固定 2px 边距远远不够：distance 通常在 3~10px 量级。
                 */
                val area = Imgproc.contourArea(contour)
                val peri = run {
                    val pts = org.opencv.core.MatOfPoint2f(*contour.toArray())
                    try {
                        Imgproc.arcLength(pts, true)
                    } finally {
                        pts.release()
                    }
                }
                val unclip = if (peri > 1.0) (area * UNCLIP_RATIO / peri) else 0.0
                val pad = (unclip + BOX_PAD).toInt()

                val x0 = (r.x - pad).coerceAtLeast(0)
                val y0 = (r.y - pad).coerceAtLeast(0)
                val x1 = (r.x + r.width + pad).coerceAtMost(srcW)
                val y1 = (r.y + r.height + pad).coerceAtMost(srcH)
                if (x1 - x0 < MIN_BOX_WIDTH || y1 - y0 < MIN_BOX_HEIGHT) continue
                boxes.add(TextBox(x0, y0, x1, y1))
            }
            for (c in contours) c.release()

            // 按阅读顺序（先上后下、先左后右）排序，便于 order_by 语义。
            boxes.sortWith(compareBy({ it.y0 / 10 }, { it.x0 }))
            return boxes
        } finally {
            hierarchy.release()
            scaled.release()
        }
    }

    // ------------------------------------------------------------ 文本识别

    private fun recognizeBox(
        environment: OrtEnvironment,
        session: OrtSession,
        region: Mat,
        box: TextBox,
        minConfidence: Double
    ): OcrLine? {
        val crop = region.submat(org.opencv.core.Rect(box.x0, box.y0, box.width, box.height))
        val normalized = Mat()
        val rgb = Mat()
        try {
            // rec 模型要求固定高度、宽度按比例缩放。
            val dstW = max(1, (box.width.toDouble() * REC_HEIGHT / box.height).roundToInt())
                .coerceAtMost(REC_MAX_WIDTH)
            Imgproc.resize(crop, normalized, Size(dstW.toDouble(), REC_HEIGHT.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)

            /*
             * ★ rec 也要 **BGR**。
             *
             * PaddleOCR 的识别管线同样是 cv2.imread（BGR）→ Resize → Normalize，
             * 中途不转 RGB。虽然它用的是对称的 0.5/0.5 归一化，
             * 但**模型权重是分通道的**，R/B 互换会稳定掉精度。
             *
             * 实测症状：版权行里的 "Electronic" 被读成 "EecroniAsic" ——
             * 拉丁字母系统性错位，而中文（份有限公司）反而基本正确。
             */
            val code = if (normalized.channels() == 4) Imgproc.COLOR_RGBA2BGR else -1
            if (code >= 0) Imgproc.cvtColor(normalized, rgb, code) else normalized.copyTo(rgb)
            // 只转一次。原来这里多了一行无条件的 cvtColor：
            // 3 通道输入时 code 是 -1，cvtColor 直接抛异常 →
            // 整行被 mapNotNull 吞掉 → 表现为「这一块识别不出文字」。

            val data = MatToTensor.nchw(rgb, mean = REC_MEAN, scale = REC_SCALE)
            // 右侧补 0 到 REC_MAX_WIDTH，宽度归一化后位置编码才稳定。
            val padded = FloatArray(3 * REC_HEIGHT * REC_MAX_WIDTH)
            val planeSize = REC_HEIGHT * REC_MAX_WIDTH
            for (c in 0 until 3) {
                System.arraycopy(data, c * REC_HEIGHT * dstW, padded, c * planeSize, REC_HEIGHT * dstW)
            }

            OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(padded),
                longArrayOf(1, 3, REC_HEIGHT.toLong(), REC_MAX_WIDTH.toLong())
            ).use { tensor ->
                session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    // rec 输出 [1][T][numClasses]
                    val out = result[0].value as Array<Array<FloatArray>>
                    val decoded = ctcDecode(out[0]) ?: return null
                    if (decoded.second < minConfidence) return null
                    return OcrLine(
                        text = decoded.first,
                        confidence = decoded.second,
                        centerX = (box.x0 + box.width / 2f),
                        centerY = (box.y0 + box.height / 2f),
                        boxX = box.x0.toFloat(),
                        boxY = box.y0.toFloat(),
                        boxWidth = box.width.toFloat(),
                        boxHeight = box.height.toFloat()
                    )
                }
            }
        } finally {
            rgb.release()
            normalized.release()
            crop.release()
        }
    }

    /**
     * CTC 贪心解码。
     *
     * @param logits [T][numClasses]
     * @return 文本 + 平均置信度；全为 blank 时返回 null
     */
    private fun ctcDecode(logits: Array<FloatArray>): Pair<String, Double>? {
        val builder = StringBuilder()
        var previous = -1
        var confidenceSum = 0.0
        var kept = 0

        for (step in logits) {
            var best = 0
            for (i in 1 until step.size) {
                if (step[i] > step[best]) best = i
            }
            // 0 是 CTC blank；重复字符要去重（除非中间隔了 blank）。
            if (best != 0 && best != previous) {
                val ch = charset.getOrNull(best - 1)
                if (ch != null) {
                    builder.append(ch)
                    confidenceSum += step[best]
                    kept++
                }
            }
            previous = best
        }

        if (builder.isEmpty()) return null
        return builder.toString() to if (kept == 0) 0.0 else confidenceSum / kept
    }

    override fun close() {
        runCatching { detSession?.close() }
        runCatching { recSession?.close() }
        detSession = null
        recSession = null
        env = null
    }

    private companion object {
        const val TAG = "OcrEngine"

        const val DEFAULT_DIR = "ocr"
        const val DET_FILE = "det.onnx"
        const val REC_FILE = "rec.onnx"
        const val KEYS_FILE = "keys.txt"

        /** 检测输入长边上限，PP-OCR 标准值。 */
        const val DET_LIMIT = 960

    /** 长边不足这个值时放大后再检测（PP-OCR 本身只缩不放）。 */
    const val DET_MIN_SIDE = 480

    /** 放大倍数上限，别把噪声也一起放大。 */
    const val DET_UPSCALE_MAX = 3.0

        /**
         * det 的归一化：**ImageNet**，BGR 通道顺序。
         *
         * 不是识别模型那套 (x-0.5)/0.5。用错这一处，概率图整体偏移，
         * 二值化后要么框错位、要么只检出一个大框 —— 表现是"识别到 1 行"，
         * 且识别结果是乱码（实测把按钮读成「2」「HN」）。
         *
         * 依据：MaaFramework 自带的 fastdeploy_ppocr_maa.dll 里，
         * 0.485/0.456/0.406 与 0.229/0.224/0.225 各以 float32 出现 3 次；
         * PaddleOCR 的 det 管线也是这一套（rec 才是 0.5/0.5）。
         */
        val DET_MEAN_RGB = floatArrayOf(0.485f, 0.456f, 0.406f)
        val DET_STD_RGB = floatArrayOf(0.229f, 0.224f, 0.225f)
        const val DET_BINARY_THRESHOLD = 0.3f

        /** 整块兜底的最大尺寸：再大就不该整块送识别（出乱码且慢）。 */
        const val WHOLE_MAX_W = 600
        const val WHOLE_MAX_H = 160

        /** 识别输入高度固定 48，宽度上限 320。 */
        const val REC_HEIGHT = 48
        const val REC_MAX_WIDTH = 320
        const val REC_MEAN = 0.5f
        const val REC_SCALE = 2.0f

        /**
     * DB 后处理的 unclip 比例。
     *
     * PaddleOCR 默认 1.5；不扩大就会裁到"文字的收缩核"（中间一条带），
     * 中文上下结构被切掉，识别结果变乱码。
     */
    const val UNCLIP_RATIO = 1.5

    const val DILATE_WIDTH = 5
        const val DILATE_HEIGHT = 3
        const val BOX_PAD = 2
        const val MIN_BOX_WIDTH = 6
        const val MIN_BOX_HEIGHT = 6

        fun alignUp(value: Int, multiple: Int): Int =
            ((value + multiple - 1) / multiple) * multiple
    }
}

/**
 * Mat -> NCHW float 数组。
 *
 * PP-OCR 的归一化是 `(pixel / 255 - mean) / scale`，
 * 其中 mean=0.5、scale=0.5（等价于 (pixel/255 - 0.5) * 2）。
 */
internal object MatToTensor {

    /**
     * Mat -> NCHW float 数组，尺寸 3 × width × height。
     *
     * PP-OCR 的归一化是 `(pixel / 255 - mean) / scale`，
     * 其中 mean=0.5、scale=0.5（等价于 (pixel/255 - 0.5) * 2）。
     */
    fun nchw(src: Mat, mean: Float, scale: Float): FloatArray =
        nchw(src, floatArrayOf(mean, mean, mean), floatArrayOf(scale, scale, scale))

    /**
     * 逐通道归一化 —— det 模型必须用这个。
     *
     * PP-OCR 的检测模型用的是 **ImageNet 归一化**：
     *   mean = [0.485, 0.456, 0.406]（对应 BGR 通道顺序）
     *   std  = [0.229, 0.224, 0.225]
     * 而不是识别模型那套 (x-0.5)/0.5。用错这一处，概率图整体偏移，
     * 二值化后要么框错位、要么只检出一个大框 —— 表现就是"识别到 1 行"，
     * 而且识别出来的是乱码（实测把按钮读成「2」「HN」）。
     *
     * 这条常量是从 MaaFramework 自带的 fastdeploy_ppocr_maa.dll 里核对的：
     * 0.485/0.456/0.406 与 0.229/0.224/0.225 各自以 float32 出现 3 次。
     */
    fun nchw(src: Mat, means: FloatArray, stds: FloatArray): FloatArray {
        val w = src.width()
        val h = src.height()
        val channels = src.channels()
        val out = FloatArray(3 * w * h)
        val plane = w * h

        // 一次性取出整块像素，再在内存里重排；逐像素调用 Mat.get 会慢一个数量级。
        val buffer = ByteArray(channels * w * h)
        src.get(0, 0, buffer)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val srcIndex = (y * w + x) * channels
                val dstIndex = y * w + x
                for (c in 0 until 3) {
                    val raw = if (c < channels) buffer[srcIndex + c].toInt() and 0xFF else 0
                    out[c * plane + dstIndex] = (raw / 255.0f - means[c]) / stds[c]
                }
            }
        }
        return out
    }
}
