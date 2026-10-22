package com.pvz2.auto.zjs

import android.util.Log
import com.pvz2.auto.core.TemplateMatcher
import org.opencv.core.Mat
import java.util.Base64

/**
 * 把我们的识别能力和 [ZExecutor] 接起来。
 *
 * ## 只做真能做的
 *
 * - **图片匹配**：OpenCV matchTemplate，模板来自脚本里的 base64
 * - **文字**：PP-OCR（assets/ocr 的 det/rec 模型），按"包含"匹配
 * - **颜色**：RGB 容差，相似度换算成每通道允许的差值
 * - **节点**：做不到 —— 那是自动精灵自己应用里的的 UI 树，我们在虚拟屏上看不到
 *
 * 不支持时返回**空列表**，执行器会记一条"没有匹配到目标"。
 * 这和"匹配了但没找到"在用户看来一样，但日志里会区分（见 [lastUnsupported]）。
 *
 * ## 为什么不用猜的方式返回一个假命中
 *
 * 假命中会让脚本"跑通"，但点的是错的地方。用户看到脚本在动，
 * 却不知道每个动作都没真正生效 —— 这比直接不跑更难查。
 */
class ZRealMatcher(
    private val matcher: TemplateMatcher,
    private val frame: () -> Mat?,
    /**
     * OCR 要 Context 才能读 assets 里的模型。
     *
     * 可选：图片/颜色识别不需要它，只有文字识别需要。传 null 时
     * 文字识别会明确报"缺少 Context"，而不是悄悄返回没找到。
     */
    private val context: android.content.Context? = null
) : ZExecutor.Matcher {

    private val TAG = "ZRealMatcher"

    /**
     * 最近一次因为"能力不支持"而返回空的原因。
     *
     * 执行器只看到"没命中"，但用户需要知道是**没找到**还是**我们做不了**。
     */
    var lastUnsupported: String? = null
        private set

    /** 执行器问"为什么没命中"时走这里。 */
    override fun lastReason(): String? = lastUnsupported

    override fun findImage(pos: Z.PosData, rect: IntArray): List<ZPosResolver.Hit> {
        val img = pos.imageData
        if (img == null || img.data.isEmpty()) {
            lastUnsupported = "图片位置没有模板数据"
            return emptyList()
        }

        val m = frame()
        if (m == null) {
            lastUnsupported = "拿不到当前帧"
            return emptyList()
        }

        val threshold = (pos.minSimilarPercent ?: 90) / 100.0

        return try {
            /*
             * ## 为什么不用 TemplateMatcher
             *
             * 它的 `match()` 只认 `load()` 时从 assets 预加载并缩放过的模板
             * （`scaled[template] ?: return null`）。脚本里的模板是 base64、
             * 尺寸任意，传进去**永远返回 null** —— 而 null 和"没找到"
             * 在界面上长得一模一样，所以这个坑很难发现。
             *
             * 这里直接解码 + matchTemplate，模板来自脚本，不依赖任何预加载。
             */
            val tmpl = decodeTemplate(img.data) ?: run {
                lastUnsupported = "模板解码失败（base64 或 PNG 损坏）"
                return emptyList()
            }

            // 帧是 RGBA，模板可能是 RGB/RGBA/灰度，统一成 BGR 才能比
            val frameBgr = org.opencv.core.Mat()
            org.opencv.imgproc.Imgproc.cvtColor(
                m, frameBgr, org.opencv.imgproc.Imgproc.COLOR_RGBA2BGR
            )

            // 只在实际要搜的范围内匹配。范围外的画面会给出假高分，
            // 这是模板匹配最常见的"明明没这东西却命中了"的来源。
            val left = rect[0].coerceIn(0, (frameBgr.width() - 1).coerceAtLeast(0))
            val top = rect[1].coerceIn(0, (frameBgr.height() - 1).coerceAtLeast(0))
            val right = rect[2].coerceIn(left + 1, frameBgr.width())
            val bottom = rect[3].coerceIn(top + 1, frameBgr.height())
            val roiW = right - left
            val roiH = bottom - top

            if (tmpl.width() > roiW || tmpl.height() > roiH) {
                lastUnsupported = "模板 ${tmpl.width()}×${tmpl.height()} 比搜索范围 ${roiW}×${roiH} 还大"
                return emptyList()
            }

            val region = frameBgr.submat(org.opencv.core.Rect(left, top, roiW, roiH))
            val result = org.opencv.core.Mat()
            var best = Double.NEGATIVE_INFINITY
            var bestX = 0
            var bestY = 0
            try {
                org.opencv.imgproc.Imgproc.matchTemplate(
                    region, tmpl, result, org.opencv.imgproc.Imgproc.TM_CCOEFF_NORMED
                )
                val mm = org.opencv.core.Core.minMaxLoc(result)
                best = mm.maxVal
                // minMaxLoc 给的是结果矩阵里的点，加回 ROI 左上角才是屏幕坐标
                bestX = mm.maxLoc.x.toInt() + left
                bestY = mm.maxLoc.y.toInt() + top
            } finally {
                region.release()
                result.release()
                frameBgr.release()
                tmpl.release()
            }

            if (best < threshold) {
                // 把"最高多少"报出来 —— 用户据此决定是降阈值还是换模板，
                // 只说"没找到"等于让他盲调。
                lastUnsupported = null
                lastScore = best
                return emptyList()
            }

            lastUnsupported = null
            lastScore = best
            // 命中的是模板左上角；用户要的是中心
            listOf(
                ZPosResolver.Hit(
                    bestX + tmpl.width() / 2,
                    bestY + tmpl.height() / 2
                )
            )
        } catch (t: Throwable) {
            Log.e(TAG, "图片匹配失败", t)
            lastUnsupported = "图片匹配异常：${t.message}"
            emptyList()
        }
    }

    /** 最近一次匹配的最高相似度（0~1）。调试和界面提示用。 */
    var lastScore: Double = 0.0
        private set

    /**
     * 把 base64 PNG 解成 BGR Mat。
     *
     * 模板里的 alpha 通道不能直接参与匹配 —— 半透明边缘会让
     * 相关系数整体偏低，看起来像"阈值不够"。
     */
    private fun decodeTemplate(b64: String): org.opencv.core.Mat? {
        val bytes = try {
            java.util.Base64.getDecoder().decode(b64)
        } catch (t: Throwable) {
            Log.e(TAG, "base64 解码失败", t)
            return null
        }
        val buf = org.opencv.core.MatOfByte(*bytes)
        val raw = org.opencv.imgcodecs.Imgcodecs.imdecode(buf, org.opencv.imgcodecs.Imgcodecs.IMREAD_UNCHANGED)
        buf.release()
        if (raw == null || raw.empty()) return null

        val bgr = org.opencv.core.Mat()
        when (raw.channels()) {
            4 -> org.opencv.imgproc.Imgproc.cvtColor(raw, bgr, org.opencv.imgproc.Imgproc.COLOR_BGRA2BGR)
            3 -> raw.copyTo(bgr)
            1 -> org.opencv.imgproc.Imgproc.cvtColor(raw, bgr, org.opencv.imgproc.Imgproc.COLOR_GRAY2BGR)
            else -> raw.copyTo(bgr)
        }
        raw.release()
        return bgr
    }

    /**
     * 文字识别：OCR 出所有文本行，挑出包含目标文字的那一行，返回它的中心。
     *
     * ## 为什么是"包含"而不是"等于"
     *
     * 游戏里的文本经常带着数字和符号（"第 3 关"、"阳光 250"）。要求整段相等
     * 等于要求用户把变化的部分也写进去，那是不可能维护的。
     *
     * ## 相似度在这里的含义
     *
     * 不是像素相似度，是 **OCR 置信度下限**。映射到 0.3~0.9：
     * 相似度 90 只认很确定的识别结果，50 放宽。写死一个值的话，
     * 用户调了滑块却没有任何效果 —— 那比不给这个滑块更糟。
     */
    override fun findText(pos: Z.PosData, rect: IntArray): List<ZPosResolver.Hit> {
        val want = pos.text?.trim().orEmpty()
        if (want.isEmpty()) {
            lastUnsupported = "文字位置没有填要识别的文字"
            return emptyList()
        }
        val ctx = context ?: run {
            lastUnsupported = "文字识别缺少 Context（内部错误）"
            return emptyList()
        }
        val m = frame() ?: run {
            lastUnsupported = "拿不到当前帧"
            return emptyList()
        }
        val engine = ocrEngine(ctx) ?: run {
            lastUnsupported = "OCR 模型加载失败（assets/ocr）"
            return emptyList()
        }

        val roi = clampRect(rect, m.width(), m.height())
        val region = m.submat(org.opencv.core.Rect(roi[0], roi[1], roi[2] - roi[0], roi[3] - roi[1]))
        val threshold = (pos.similarPercent ?: 90) / 100.0
        return try {
            /*
             * 相似度是**文字相似度**，不是 OCR 置信度。
             *
             * 一开始我把它当成置信度下限（90 → 0.84），结果游戏里的艺术字
             * 置信度普遍 0.5~0.8，全被滤掉，报"这一块里没识别出任何文字" ——
             * 而画面里明明全是字。用户会去调相似度，越调越低，越低越不对。
             *
             * 所以：置信度用一个很低的下限（0.3）把候选都取回来，
             * 再用相似度判断"这段文字是不是我要找的"。
             */
            val lines = engine.recognize(region, 0.3)
            // 诊断：分不清"画面上没字"和"OCR 没跑起来"时，这行日志能分开
            runCatching {
                val mean = org.opencv.core.Core.mean(region)
                Log.i(
                    TAG,
                    "OCR 区域 ${region.width()}x${region.height()} " +
                        "ch=${region.channels()} 均值=$mean 行数=${lines.size}"
                )
            }
            if (lines.isEmpty()) {
                lastScore = 0.0
                lastUnsupported = "这一块里没识别出任何文字"
                return emptyList()
            }

            val best = lines.maxByOrNull { textSimilarity(want, it.text) }
            val score = if (best == null) 0.0 else textSimilarity(want, best.text)
            lastScore = score

            if (best == null || score < threshold) {
                val seen = lines.take(4).joinToString("、") { it.text }
                lastUnsupported = "识别到 ${lines.size} 行（$seen…），" +
                    "最接近「$want」的是「${best?.text ?: ""}」相似度 " +
                    "${"%.0f".format(score * 100)}%，低于阈值 ${"%.0f".format(threshold * 100)}%"
                emptyList()
            } else {
                lastUnsupported = null
                // 行坐标是相对 ROI 的，加回 ROI 左上角才是屏幕坐标
                listOf(
                    ZPosResolver.Hit(
                        roi[0] + best.centerX.toInt(),
                        roi[1] + best.centerY.toInt()
                    )
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "文字识别失败", t)
            lastUnsupported = "文字识别异常：${t.message}"
            emptyList()
        } finally {
            region.release()
        }
    }

    /**
     * 颜色匹配：在 ROI 里找和给定颜色足够接近的像素，返回它们的重心。
     *
     * ## 为什么用 RGB 容差而不是 HSV 区间
     *
     * 用户填的是 `#FF0000` 这种 RGB，相似度滑块的心智也是"多接近"。
     * 换成 HSV 区间后，同一个滑块要同时控制色相和饱和度两个量，
     * 用户拖到 70 根本不知道自己在放宽什么。RGB 容差是"每个通道允许差多少"，
     * 一句话说得清。
     *
     * 代价是纯亮度变化（同一块颜色变暗）会判成不匹配。颜色条件通常用在
     * 纯色 UI 元素上，这个代价可以接受；真需要抗光照时应该用模板匹配。
     */
    override fun findColor(pos: Z.PosData, rect: IntArray): List<ZPosResolver.Hit> {
        val rgb = parseHexRgb(pos.color) ?: run {
            lastUnsupported = "颜色「${pos.color ?: ""}」不是有效的 #RRGGBB"
            return emptyList()
        }
        val r = rgb[0]
        val g = rgb[1]
        val b = rgb[2]

        val m = frame() ?: run {
            lastUnsupported = "拿不到当前帧"
            return emptyList()
        }
        val roi = clampRect(rect, m.width(), m.height())
        val region = m.submat(org.opencv.core.Rect(roi[0], roi[1], roi[2] - roi[0], roi[3] - roi[1]))
        val bgr = org.opencv.core.Mat()
        val mask = org.opencv.core.Mat()
        return try {
            org.opencv.imgproc.Imgproc.cvtColor(
                region, bgr, org.opencv.imgproc.Imgproc.COLOR_RGBA2BGR
            )
            // 相似度 90 → 每通道允许差 25；相似度 100 → 0；0 → 255
            val tol = ((100 - (pos.similarPercent ?: 90)) * 255 / 100).coerceIn(0, 255).toDouble()
            org.opencv.core.Core.inRange(
                bgr,
                org.opencv.core.Scalar(
                    (b - tol).coerceAtLeast(0.0), (g - tol).coerceAtLeast(0.0), (r - tol).coerceAtLeast(0.0)
                ),
                org.opencv.core.Scalar(
                    (b + tol).coerceAtMost(255.0), (g + tol).coerceAtMost(255.0), (r + tol).coerceAtMost(255.0)
                ),
                mask
            )

            val nonzero = org.opencv.core.Core.countNonZero(mask)
            lastScore = if (nonzero == 0) 0.0 else 1.0
            if (nonzero == 0) {
                // 顺带报出这一块的平均色：只说"没找到"，用户只能猜一个颜色再试。
                val mv = org.opencv.core.Core.mean(bgr).`val`
                val r0 = mv[2].toInt()
                val g0 = mv[1].toInt()
                val b0 = mv[0].toInt()
                lastUnsupported = "没找到这个颜色（这一块平均色 #" +
                    "%02X%02X%02X".format(r0, g0, b0) + "）"
                return emptyList()
            }

            // 重心：颜色块通常是一个连通的 UI 元素，重心比"第一个像素"
            // 更接近用户想点的位置。
            val moments = org.opencv.imgproc.Imgproc.moments(mask, true)
            if (moments.m00 <= 0.0) {
                lastUnsupported = null
                return emptyList()
            }
            val cx = roi[0] + (moments.m10 / moments.m00).toInt()
            val cy = roi[1] + (moments.m01 / moments.m00).toInt()
            lastUnsupported = null
            listOf(ZPosResolver.Hit(cx, cy))
        } catch (t: Throwable) {
            Log.e(TAG, "颜色匹配失败", t)
            lastUnsupported = "颜色匹配异常：${t.message}"
            emptyList()
        } finally {
            region.release()
            bgr.release()
            mask.release()
        }
    }

    /** ROI 夹到帧内，越界会让 OpenCV 直接崩。 */
    private fun clampRect(rect: IntArray, w: Int, h: Int): IntArray {
        val l = rect[0].coerceIn(0, (w - 1).coerceAtLeast(0))
        val t = rect[1].coerceIn(0, (h - 1).coerceAtLeast(0))
        val r = rect[2].coerceIn(l + 1, w)
        val b = rect[3].coerceIn(t + 1, h)
        return intArrayOf(l, t, r, b)
    }

    companion object {
        /**
         * OCR 引擎是进程级的：加载 20MB 模型要几百毫秒，
         * 每个动作重建一次会让脚本慢到没法用。
         */
        private var sharedOcr: com.pvz2.auto.maa.OcrEngine? = null

        /**
         * `#RRGGBB` / `RRGGBB` / `0xAARRGGBB` → `[r, g, b]`。认不出返回 null。
         *
         * 单独抽出来是因为它会静默出错：`"FF0000".toLongOrNull(16)` 看着没问题，
         * 但少一位、多一位、带不带 `#` 都影响结果，不测很容易把红色读成蓝色。
         */
        /**
         * 两段文字的相似度（0~1）。
         *
         * - 完全包含 → 1.0（"第 3 关" 里找 "3 关" 就该是满分）
         * - 否则按编辑距离归一化
         *
         * 用包含优先而不是纯编辑距离，是因为游戏文本常带前后缀
         * （"关闭" vs "关闭按钮"），纯编辑距离会给 0.6 左右，
         * 用户拖到 90 永远匹配不上。
         */
        internal fun textSimilarity(want: String, got: String): Double {
            if (want.isEmpty() || got.isEmpty()) return 0.0
            val a = want.trim().lowercase()
            val b = got.trim().lowercase()
            if (a == b) return 1.0
            if (b.contains(a) || a.contains(b)) {
                // 长短差异越大越扣分，避免"1"匹配上"123456"
                val short = minOf(a.length, b.length).toDouble()
                val long = maxOf(a.length, b.length).toDouble()
                return 0.7 + 0.3 * (short / long)
            }
            val dist = levenshtein(a, b)
            return (1.0 - dist.toDouble() / maxOf(a.length, b.length)).coerceIn(0.0, 1.0)
        }

        private fun levenshtein(a: String, b: String): Int {
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length
            var prev = IntArray(b.length + 1) { it }
            var cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
                }
                val tmp = prev; prev = cur; cur = tmp
            }
            return prev[b.length]
        }

        internal fun parseHexRgb(raw: String?): IntArray? {
            val hex = raw?.trim()?.removePrefix("#")?.removePrefix("0x")?.removePrefix("0X")
                ?: return null
            // 8 位是 AARRGGBB，颜色在低 6 位
            val body = when (hex.length) {
                6 -> hex
                8 -> hex.substring(2)
                else -> return null
            }
            if (!body.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
            val v = body.toLongOrNull(16) ?: return null
            return intArrayOf(
                ((v shr 16) and 0xFF).toInt(),
                ((v shr 8) and 0xFF).toInt(),
                (v and 0xFF).toInt()
            )
        }

        private fun ocrEngine(context: android.content.Context): com.pvz2.auto.maa.OcrEngine? {
            sharedOcr?.let { return it }
            return runCatching {
                com.pvz2.auto.maa.OcrEngine(context.applicationContext).also {
                    it.load()
                    sharedOcr = it
                }
            }.onFailure { Log.e("ZRealMatcher", "OCR 初始化失败", it) }.getOrNull()
        }
    }

    /**
     * 节点查找：**做不到，而且不是没时间做**。
     *
     * 自动精灵的「节点」是它自己那个应用的内部 UI 树（它自己跑在无障碍里，
     * 拿得到目标 App 的节点）。我们跑在虚拟屏上，画面是一张图，
     * 没有节点树可查。硬用一个假命中顶上只会让脚本点错地方。
     *
     * 要做同类能力，只能用 OCR 或模板匹配，也就是把位置类型换成文字/图片。
     */
    override fun findNode(pos: Z.PosData, rect: IntArray): List<ZPosResolver.Hit> {
        lastUnsupported = "节点查找只能在自动精灵自己的节点树上用；" +
            "我们拿不到节点树，请改用「文字」或「图片」位置"
        return emptyList()
    }

    // ------------------------------------------------------------ 模板文件

    private val templateCache = HashMap<String, String>()

    /**
     * 把 base64 模板写到缓存目录，返回路径。
     *
     * 同一个模板只写一次 —— 连点二十次不该写二十次盘。
     */
    private fun ensureTemplateFile(img: Z.ImageData): String? {
        val key = img.data.hashCode().toString()
        templateCache[key]?.let { return it }

        return try {
            val dir = java.io.File(cacheDir(), "zjs_templates")
            dir.mkdirs()
            val f = java.io.File(dir, "tpl_$key.png")
            if (!f.exists()) {
                val bytes = Base64.getDecoder().decode(img.data)
                f.writeBytes(bytes)
            }
            templateCache[key] = f.absolutePath
            f.absolutePath
        } catch (t: Throwable) {
            Log.e(TAG, "写模板失败", t)
            null
        }
    }

    /** 缓存目录由外部注入（Context 不能跨进程）。 */
    var cacheDirProvider: () -> String = { "/data/local/tmp/pvz2/templates" }

    private fun cacheDir(): String = cacheDirProvider()
}
