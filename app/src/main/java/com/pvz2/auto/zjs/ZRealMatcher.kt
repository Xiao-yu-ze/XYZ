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

        // 模板以 PNG base64 存在协议里（SPEC.md:677），落盘到临时文件
        // 才能交给 TemplateMatcher（它按路径加载）。
        val path = ensureTemplateFile(img)
        if (path == null) {
            lastUnsupported = "模板写到磁盘失败"
            return emptyList()
        }

        val threshold = (pos.minSimilarPercent ?: 90) / 100.0
        // roi 用 android.graphics.Rect —— TemplateMatcher 内部自己转 720p 逻辑坐标
        val roi = android.graphics.Rect(rect[0], rect[1], rect[2], rect[3])

        return try {
            val hit = matcher.match(m, path, roi, threshold) ?: run {
                lastUnsupported = null
                return emptyList()
            }
            lastUnsupported = null
            // 命中的是模板左上角（720p 逻辑坐标）；用户想点的是**中心**。
            // 模板宽高在协议里也是逻辑坐标，所以可以直接加。
            listOf(
                ZPosResolver.Hit(
                    (hit.logicalX + (img.width ?: 0) / 2f).toInt(),
                    (hit.logicalY + (img.height ?: 0) / 2f).toInt()
                )
            )
        } catch (t: Throwable) {
            Log.e(TAG, "图片匹配失败", t)
            lastUnsupported = "图片匹配异常：${t.message}"
            emptyList()
        }
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
