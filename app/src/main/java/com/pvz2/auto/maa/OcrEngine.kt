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

        return try {
            val boxes = detect(environment, det, region)
            if (boxes.isEmpty()) return emptyList()

            boxes.mapNotNull { box ->
                recognizeBox(environment, rec, region, box, minConfidence)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "OCR 推理失败", t)
            emptyList()
        }
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

            // 模型吃 RGB；截图是 BGRA。
            // ★ 帧是 RGBA（AImageReader / bitmapToMat 都是 R,G,B,A），要得到 RGB 必须用
            //   `COLOR_RGBA2RGB`（= 丢 alpha、不交换）。
            //   之前写的 `COLOR_BGRA2RGB` 等价于"交换"，把 RGBA 变成 BGR 交给
            //   PP-OCR —— 模型收到的是红蓝反转的图，检测框和识别率静默下降。
            val code = if (resized.channels() == 4) Imgproc.COLOR_RGBA2RGB else Imgproc.COLOR_BGR2RGB
            Imgproc.cvtColor(resized, rgb, code)

            val input = MatToTensor.nchw(rgb, mean = DET_MEAN, scale = DET_SCALE)
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
                // 加一点边距，避免 rec 吃掉贴边的笔画。
                val x0 = (r.x - BOX_PAD).coerceAtLeast(0)
                val y0 = (r.y - BOX_PAD).coerceAtLeast(0)
                val x1 = (r.x + r.width + BOX_PAD).coerceAtMost(srcW)
                val y1 = (r.y + r.height + BOX_PAD).coerceAtMost(srcH)
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

            val code = if (normalized.channels() == 4) Imgproc.COLOR_RGBA2RGB else Imgproc.COLOR_BGR2RGB
            Imgproc.cvtColor(normalized, rgb, code)

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
        const val DET_MEAN = 0.5f
        const val DET_SCALE = 2.0f
        const val DET_BINARY_THRESHOLD = 0.3f

        /** 识别输入高度固定 48，宽度上限 320。 */
        const val REC_HEIGHT = 48
        const val REC_MAX_WIDTH = 320
        const val REC_MEAN = 0.5f
        const val REC_SCALE = 2.0f

        const val DILATE_WIDTH = 9
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
    fun nchw(src: Mat, mean: Float, scale: Float): FloatArray {
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
                    out[c * plane + dstIndex] = (raw / 255.0f - mean) / scale
                }
            }
        }
        return out
    }
}
