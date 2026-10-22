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
 * - **图片匹配**：走 [TemplateMatcher]，已经验证过可用
 * - **文字**：暂不支持 —— OCR 引擎还没接进执行路径
 * - **颜色 / 节点**：暂不支持
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
    private val frame: () -> Mat?
) : ZExecutor.Matcher {

    private val TAG = "ZRealMatcher"

    /**
     * 最近一次因为"能力不支持"而返回空的原因。
     *
     * 执行器只看到"没命中"，但用户需要知道是**没找到**还是**我们做不了**。
     */
    var lastUnsupported: String? = null
        private set

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

    override fun findText(pos: Z.PosData, rect: IntArray): List<ZPosResolver.Hit> {
        // OCR 还没接进执行路径。返回空让执行器记"没匹配到"，
        // 同时把真实原因留下来。
        lastUnsupported = "文字识别还没接进执行"
        return emptyList()
    }

    override fun findColor(pos: Z.PosData, rect: IntArray): List<ZPosResolver.Hit> {
        lastUnsupported = "颜色匹配还没接进执行"
        return emptyList()
    }

    override fun findNode(pos: Z.PosData, rect: IntArray): List<ZPosResolver.Hit> {
        lastUnsupported = "节点查找还没接进执行"
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
