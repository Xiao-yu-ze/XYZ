package com.pvz2.auto.zjs

import com.pvz2.auto.core.Pvz2NodeService

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

    /**
     * 上一帧还没回收的 Mat。
     *
     * ## 为什么要有这个
     *
     * [frame] 每次返回**新分配**的 720p Mat（3.7MB，native 内存），
     * 而 findXxx 里的早退路径很多，逐一记得 release 很容易漏。
     * 漏一块就是 3.7MB，而等待候选/识别重试是 200ms 一次的循环 ——
     * 那就是 37MB/s 的持续泄漏，几分钟后进程被系统杀掉。
     *
     * 这里用"取下一帧时回收上一帧"，把无界泄漏压成**常量级（最多 1 块）**。
     * 彻底的修法是每次调用都 try/finally release（已在待办里）。
     */
    private var pendingFrame: org.opencv.core.Mat? = null

    /**
     * 长度值做 `dp → px` 换算时用的**屏幕密度**。
     *
     * ★ 必须是**虚拟屏**的密度，不是宿主手机的。
     *   虚拟屏固定 1280×720 @ 240dpi（`VirtualDisplaySession.VD_DPI`），
     *   所以 1dp = 240/160 = **1.5px**。宿主手机普遍 2.75~3.5，按宿主算会让
     *   所有 dp 值偏大近一倍（100dp：应该 150px，实际 300px）—— 点位和 ROI 一起歪。
     *
     * 宿主若改成显式传 `ZPosResolver.Screen`，覆盖这个值即可；默认就是本项目虚拟屏的值。
     */
    var density: Float = DEFAULT_DENSITY

    private fun nextFrame(): org.opencv.core.Mat? {
        pendingFrame?.release()
        /*
         * ★ 这里必须调 frame()，**绝对不能**调 nextFrame()。
         *
         *   上一版误写成 `val m = nextFrame()` —— 等于自己调自己：
         *   签名完全一样，编译器不报错，但第一次调用就无限递归 →
         *   StackOverflowError。现象是**所有**文字/颜色/图片识别一起崩，
         *   而堆栈里全是 nextFrame 自己，看不出是谁调进来的。
         *
         *   直接 frame() 的代价是"每次漏一块 3.7MB native Mat"，
         *   所以本函数用"取下一帧时回收上一帧"把这个泄漏压到常量级（最多 1 块）。
         */
        val m = frame()
        pendingFrame = m
        return m
    }

    /** 用完记得调，否则最后一块要等 finalizer 兜底。 */
    fun close() {
        pendingFrame?.release()
        pendingFrame = null
    }

    /** 执行器问"为什么没命中"时走这里。 */
    override fun lastReason(): String? = lastUnsupported

    override fun findImage(pos: Z.PosData, rect: IntArray): List<ZPosResolver.Hit> {
        val img = pos.imageData
        if (img == null || img.data.isEmpty()) {
            lastUnsupported = "图片位置没有模板数据"
            return emptyList()
        }

        // ★ 走 nextFrame()：直接 frame() 每次漏一块 3.7MB native Mat，
        //   而识别是 200ms 级的循环，漏起来几分钟就能把进程撑爆。
        val m = nextFrame() ?: run {
            lastUnsupported = "拿不到当前帧"
            return emptyList()
        }

        // 本函数里 new 出来的每个 Mat 都往这里丢，finally 统一释放。
        // 早退分支很多（解码失败 / 模板比 ROI 大 / 滤镜不支持…），逐个 release 必漏。
        val garbage = ArrayList<org.opencv.core.Mat>()

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
            val tmpl0 = decodeTemplate(img.data) ?: run {
                lastUnsupported = "模板解码失败（base64 或 PNG 损坏）"
                return emptyList()
            }

            garbage.add(tmpl0)

            /*
             * ★ 模板缩放不再"一步定死"，改为按 `imageScaleType` 分派（见 scaleSpecs）。
             *
             *   原来只按"帧宽 / imageData.screenWidth"缩一次。但协议里这张图有
             *   **6 种**缩放策略，而且**默认值是 `dpi`**、不是按屏宽：
             *
             *     none                     → 不缩放（图本来就是当前分辨率截的）
             *     baseScreenWidth          → 帧宽 / screenWidth
             *     baseScreenHeight         → 帧高 / screenHeight
             *     dpi                      → 1 / screenDpi
             *     baseScreenWidthAndHeight → 宽高各算各的（可非等比）
             *     tryAll                   → 四种都试，取最好的
             *
             *   缩错的后果是"相关性永远上不去"，而且**不报错**，只表现为"没找到"，
             *   用户会去调阈值、换模板，方向全错。所以这里必须按字段真分派，
             *   取不到字段时才回落到原来的 baseScreenWidth（保持既有行为）。
             */

            // ROI **先裁再转 BGR**：只换算要搜的那一块，不再把整帧 1280×720 全转一遍
            //（整帧转是 3.7MB 读 + 2.7MB 写，而识别是 200ms 一轮的循环）。
            // 范围外的画面会给出假高分，这是模板匹配最常见的"明明没这东西却命中"的来源。
            val roiRect = clampRect(rect, m.width(), m.height())
            val left = roiRect[0]
            val top = roiRect[1]
            val roiW = roiRect[2] - roiRect[0]
            val roiH = roiRect[3] - roiRect[1]
            if (roiW < 2 || roiH < 2) {
                lastUnsupported = "搜索范围太小（${roiW}×${roiH}）"
                return emptyList()
            }

            val srcRegion = m.submat(org.opencv.core.Rect(left, top, roiW, roiH))
            val regionBgr = org.opencv.core.Mat()
            org.opencv.imgproc.Imgproc.cvtColor(
                srcRegion, regionBgr, org.opencv.imgproc.Imgproc.COLOR_RGBA2BGR
            )
            srcRegion.release()
            garbage.add(regionBgr)

            /*
             * 滤镜 / 搜索模式：两者都是"对**模板和 ROI 各套一次同一变换**"。
             *
             * 必须两边完全对称 —— 只给 ROI 加滤镜而模板不加（或反过来），
             * 两边像素语义就变了，相关系数被系统性拉低。表现还是"阈值不够"，
             * 而用户去调阈值只会越调越糟。
             */
            var note: String? = null

            val fType = filterType(pos)
            if (fType != null && fType !in SUPPORTED_FILTERS) {
                // 协议里 filter 有 9 种（bw/grey/outline/reverse/lightness/contrast/
                // rgb/changeColor/multi），我们只做 grey/bw/reverse。
                // 没实现的**不能装作生效**：记一条原因，然后按"无滤镜"继续匹配。
                note = "滤镜 $fType 未实现，按无滤镜执行"
            }

            val mode = (pos.extra["searchMode"] as? String)?.trim().orEmpty()
            val edgeMode = when {
                mode.isEmpty() || mode == "color_2.21" || mode == "COLOR" -> false
                mode == "outline_2.21" -> true
                else -> {
                    // HOG 等未实现：同样如实记录，再按默认模式继续。
                    note = (note?.plus("；") ?: "") + "搜索模式 $mode 未实现，按 color_2.21 执行"
                    false
                }
            }

            // `quickSearch`（快速搜图）只影响速度、不影响判定结果 —— 未实现，也**没有副作用**，
            // 所以这里刻意不读它。读了却不真加速，等于给性能问题加了层假装饰。
            /*
             * ★ `bw` 的二值化阈值必须**模板算一次、两边共用**。
             *
             *   OTSU 是自适应的：对模板小图算一个阈值、对 ROI 算另一个 ——
             *   同一个 UI 元素在两边被切到不同的黑白基准，相关系数与逐像素复核
             *   会被一起拉低。表现为"加了滤镜反而偶尔找不到"，而且完全不报错。
             *
             *   顺序：先用**模板**（标准答案）算阈值 → ROI 与所有尺度变体都用它。
             *   模板侧本来要进 tryAll 循环才处理，所以这里先单独算一遍阈值。
             */
            val bwThreshold = if (fType == "bw") otsuThreshold(tmpl0) else null

            // ROI 侧的变换只做一次，多尺度模板复用同一份（模板小，重复变换不心疼）。
            var region: org.opencv.core.Mat = regionBgr
            val roiFiltered = applyFilter(region, fType, bwThreshold)
            if (roiFiltered != null) {
                region = roiFiltered
                garbage.add(roiFiltered)
            }
            if (edgeMode) {
                val edges = toEdges(region)
                if (edges != null) {
                    region = edges
                    garbage.add(edges)
                }
            }

            /*
             * `tryAll` 要依次试多种缩放，取**有命中且分数最高**的那一档。
             *
             * 不能只看分数：某一档可能相关系数更高、但逐像素复核全被剔掉
             * （尺度差半个像素时很常见：形状对上了、边缘没对上）。
             * 那种必须让位给真正命中的那一档，否则会出现"明明找到了却报没找到"。
             */
            var bestHit: Attempt? = null
            var bestAny: Attempt? = null
            for (spec in scaleSpecs(pos, img, m.width(), m.height())) {
                val scaled = resizeTo(tmpl0, spec)
                if (scaled !== tmpl0) garbage.add(scaled)

                // 模板侧也要套同一份滤镜/边缘：和 ROI 保持像素语义一致。
                var tmpl: org.opencv.core.Mat = scaled
                val tmplFiltered = applyFilter(tmpl, fType, bwThreshold)
                if (tmplFiltered != null) {
                    tmpl = tmplFiltered
                    garbage.add(tmplFiltered)
                }
                if (edgeMode) {
                    val edges = toEdges(tmpl)
                    if (edges != null) {
                        tmpl = edges
                        garbage.add(edges)
                    }
                }

                val attempt = matchOnce(region, tmpl, threshold, left, top)
                if (bestAny == null || attempt.best > bestAny.best) bestAny = attempt
                if (attempt.hits.isNotEmpty() &&
                    (bestHit == null || attempt.best > bestHit.best)
                ) {
                    bestHit = attempt
                }
            }

            val chosen = bestHit ?: bestAny
            lastScore = (chosen?.best ?: 0.0).coerceIn(0.0, 1.0)

            if (chosen == null || chosen.hits.isEmpty()) {
                // reason 优先：它比"没找到"具体得多（模板比 ROI 大 / 全被逐像素复核剔除…）
                lastUnsupported = chosen?.reason ?: note ?: "没有匹配到目标"
                return emptyList()
            }
            // 命中时保留 note —— "滤镜未实现"这类信息仍然有用，不能被清掉。
            lastUnsupported = note
            chosen.hits
        } catch (t: Throwable) {
            Log.e(TAG, "图片匹配失败", t)
            lastUnsupported = "图片匹配异常：${t.message}"
            emptyList()
        } finally {
            // 统一释放：早退路径太多（解码失败/ROI 太小/滤镜不支持/模板比 ROI 大…），
            // 逐个 release 必漏一块。Mat 是 native 内存，不受 GC 管理。
            for (mat in garbage) runCatching { mat.release() }
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
    /**
     * 整块区域的文字识别（「识别屏幕」动作用）。
     *
     * 复用 findText 的那套 OCR 引擎，但**不做相似度比较** ——
     * 这里要的是"画面上的字都是什么"，不是"有没有我要找的那句"。
     */
    override fun ocrTexts(rect: IntArray): List<String> {
        val ctx = context ?: run {
            lastUnsupported = "文字识别缺少 Context（内部错误）"
            return emptyList()
        }
        val m = nextFrame() ?: run {
            lastUnsupported = "拿不到当前帧"
            return emptyList()
        }
        val engine = ocrEngine(ctx) ?: run {
            lastUnsupported = "OCR 模型加载失败（assets/ocr）"
            return emptyList()
        }
        val roi = clampRect(rect, m.width(), m.height())
        val region = m.submat(
            org.opencv.core.Rect(roi[0], roi[1], roi[2] - roi[0], roi[3] - roi[1])
        )
        return try {
            engine.recognize(region, 0.3).map { it.text }.filter { it.isNotBlank() }
        } finally {
            region.release()
        }
    }

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
        val m = nextFrame() ?: run {
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
            /*
         * 「找字滤镜」（协议 `text.filter`，FIELDS:202）。
         *
         * 原来滤镜只在 findImage 里套，找文字全程不用滤镜 —— 需要反色/二值化
         * 才认得出的艺术字**必然**找不到，而界面上连这一行都没有。
         * 三种滤镜（grey/bw/reverse）的实现是现成的，直接复用。
         */
        val fType = filterType(pos)
        val filtered = if (fType != null) applyFilter(region, fType) else null
        val ocrInput = filtered ?: region
        val lines = engine.recognize(ocrInput, 0.3)
        // 识别完就把滤镜产物放掉；后面只用 lines，不再碰这块 Mat
        filtered?.release()
            // 诊断：分不清"画面上没字"和"OCR 没跑起来"时，这行日志能分开
            runCatching {
                // 用 getExternalFilesDir：这是应用自己一定可写的位置，
                // 之前从 savedDir.parentFile 推导，异常被 runCatching 吞掉，一枚图都没落下来。
                val dir = java.io.File(ctx!!.getExternalFilesDir(null), "ocr_fail")
                check(dir.isDirectory || dir.mkdirs()) { "建目录失败：$dir" }
                val file = java.io.File(dir, "fail_${System.currentTimeMillis()}.png")
                val ok = org.opencv.imgcodecs.Imgcodecs.imwrite(file.absolutePath, region)
                check(ok) { "imwrite 返回 false" }
                Log.i(TAG, "OCR 失败样本已存：${file.absolutePath}")
            }.onFailure {
                // 不再静默：存不下来本身也是要查的问题
                Log.w(TAG, "存 OCR 失败样本出错：${it.message}")
            }
            if (lines.isEmpty()) {
                lastScore = 0.0
                lastUnsupported = "这一块里没识别出任何文字"
                /*
                 * 失败时把**喂给 OCR 的那一块**存成 PNG。
                 *
                 * 分不清"画面上没字"和"我们截错了地方"时，光看日志只能猜；
                 * 存下来直接看图就定了。路径在应用外部目录，adb 能拉走：
                 *   /sdcard/Android/data/com.pvz2.auto/files/ocr_fail/
                 */
                runCatching {
                    val dir = java.io.File(
                        com.pvz2.auto.zjs.ZjsRunner.savedDir(ctx).parentFile,
                        "ocr_fail"
                    )
                    dir.mkdirs()
                    val name = "fail_${System.currentTimeMillis()}.png"
                    org.opencv.imgcodecs.Imgcodecs.imwrite(
                        java.io.File(dir, name).absolutePath, region
                    )
                    Log.i(TAG, "OCR 失败样本已存：${java.io.File(dir, name).absolutePath}")
                }
                return emptyList()
            }

            /*
             * ★ `want` 可能不是字面文字，而是「正则」：
             *      {"type":"RegExp","source":"第\\d+关","flags":""}
             *
             *   协议的文字位置/条件支持这种写法（m18129 的 text 分支：以 `{` 开头、
             *   以 `}` 结尾就当 RegExp 解析，其余按 `indexOf` 找字面量）。
             *   **不认它会静默永不命中** —— 界面上只会说"没找到"，用户于是去怀疑
             *   OCR、去调阈值，方向全错。
             */
            // 正则解析统一走 ZPosResolver.textRegex（与 colorProbePoint 同理：
            // 两套实现迟早漂移，而"正则写法认不出"的表现是**静默永不命中**）。
            // 返回 null = 不是正则写法，退回包含/相似度匹配。
            val regex = ZPosResolver.textRegex(pos)
            val best: com.pvz2.auto.maa.OcrLine?
            val score: Double
            if (regex != null) {
                // 正则命中即 1.0：协议里正则分支给的是 similar=1，不参与"像不像"打分。
                best = lines.firstOrNull { regex.containsMatchIn(it.text) }
                score = if (best != null) 1.0 else 0.0
            } else {
                best = lines.maxByOrNull { textSimilarity(want, it.text) }
                score = if (best == null) 0.0 else textSimilarity(want, best.text)
            }
            lastScore = score

            if (best == null || score < threshold) {
                val seen = lines.take(4).joinToString("、") { it.text }
                val what = if (regex != null) "正则「${regex.pattern}」" else "「$want」"
                lastUnsupported = "识别到 ${lines.size} 行（$seen…），没有一行匹配 $what" +
                    "（最接近的相似度 ${"%.0f".format(score * 100)}%，" +
                    "低于阈值 ${"%.0f".format(threshold * 100)}%）"
                emptyList()
            } else {
                lastUnsupported = null
                // 行坐标是相对 ROI 的，加回 ROI 左上角才是屏幕坐标
                listOf(
                    ZPosResolver.Hit(
                        roi[0] + best.centerX.toInt(),
                        roi[1] + best.centerY.toInt(),
                        // 文字命中码给"文字相似度"（正则命中是 1.0），供执行器选最优命中。
                        score = score
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

        val m = nextFrame() ?: run {
            lastUnsupported = "拿不到当前帧"
            return emptyList()
        }

        /*
         * ★ 「指定位置取色」优先：只判这一个点，**忽略 limitArea**。
         *
         *   协议（FIELDS.md §3.4）里 color 位置有两种"只看一个点"的写法：
         *   `limitPosX/limitPosY`（指定位置，填了就忽略 limitArea）和取色点 `x/y`。
         *   坐标计算统一由 `ZPosResolver.colorProbePoint` 负责，本类只判这个像素。
         *
         *   原来一律按 ROI 扫描 + 重心，于是：
         *    · 用户指定了一个点，我们却在整块 limitArea 里找 —— 区域里**任意一处**
         *      有近似色就过闸，闸门被放大成"这块区域里有这个颜色"；
         *    · 返回的是重心，不是用户指定的那个点，点击位置也跟着偏。
         *   两处偏差都不报错，只表现为"条件过了但后面操作不对"，最难查。
         */
        ZPosResolver.colorProbePoint(pos, m.width(), m.height(), density)?.let { point ->
            return judgeProbePoint(pos, m, point, rgb)
        }

        val roi = clampRect(rect, m.width(), m.height())
        val region = m.submat(org.opencv.core.Rect(roi[0], roi[1], roi[2] - roi[0], roi[3] - roi[1]))
        val bgr = org.opencv.core.Mat()
        val mask = org.opencv.core.Mat()
        return try {
            org.opencv.imgproc.Imgproc.cvtColor(
                region, bgr, org.opencv.imgproc.Imgproc.COLOR_RGBA2BGR
            )
            /*
             * ★ 优先用脚本里的逐通道上下界（lower/upper），这是 Maa 的原始语义。
             *
             *   之前我把 lower/upper 压成"中心色 + 容差"，再拿它去比 —— 丢了两件事：
             *   上下界本来是非对称的（比如 R 237~255 而 G 202~229），
             *   压成对称容差之后判定范围完全不同。
             *
             *   没有 lower/upper 的老脚本才退回中心色 + 容差。
             */
            val lo = pos.colorLower
            val hi = pos.colorUpper
            if (lo != null && hi != null && lo.size >= 3 && hi.size >= 3) {
                org.opencv.core.Core.inRange(
                    bgr,
                    org.opencv.core.Scalar(lo[2].toDouble(), lo[1].toDouble(), lo[0].toDouble()),
                    org.opencv.core.Scalar(hi[2].toDouble(), hi[1].toDouble(), hi[0].toDouble()),
                    mask
                )
            } else {
                // 相似度 90 → 每通道允许差 25；相似度 100 → 0；0 → 255
                // 协议里颜色 similarPercent 默认 **100**（精确匹配这个色）。
                // 用 90 等于每通道放宽 ±25，闸门该拦的拦不住。
                val tol = ((100 - (pos.similarPercent ?: 100)) * 255 / 100).coerceIn(0, 255).toDouble()
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
            }

            /*
             * 判定就是**至少一个像素**（`nonzero > 0`），不是"足够比例"。
             *
             * ★ 上一版这段注释写的是"要求足够比例的像素"，代码却一直是
             *   `nonzero > 0` —— 注释和实现直接矛盾。后来人照着注释去加比例，
             *   只会把正常闸门卡死（实测过：要求一半像素，直接把"是否进入关内"
             *   这种正常判断判成不成立）。
             *
             * Maa 的 ColorMatch 默认 `count: 1`，语义就是"这个区域里有没有这个颜色"。
             * 抗"冷却后残留像素"要靠 lower/upper 逐通道上下界（见上面那段），
             * 不是靠收紧像素数量。下面的 ratio 只作诊断（进 lastScore 与日志）。
             */
            val nonzero = org.opencv.core.Core.countNonZero(mask)
            val total = (mask.rows() * mask.cols()).coerceAtLeast(1)
            val ratio = nonzero.toDouble() / total
            lastScore = ratio
            // Maa 语义：至少一个像素（count = 1）
            val ok = nonzero > 0
            if (!ok) {
                // 顺带报出这一块的平均色：只说"没找到"，用户只能猜一个颜色再试。
                val mv = org.opencv.core.Core.mean(bgr).`val`
                val r0 = mv[2].toInt()
                val g0 = mv[1].toInt()
                val b0 = mv[0].toInt()
                lastUnsupported = "没找到这个颜色（命中 0 像素，这一块平均色 #%02X%02X%02X）"
                    .format(r0, g0, b0)
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
            // 颜色没有"相关度"可谈，命中即 1.0（协议里颜色命中也是 similar=1）。
            // 命中像素占比留在 lastScore 里，只作诊断。
            listOf(ZPosResolver.Hit(cx, cy, 1.0))
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

    /**
     * 单点取色判定：只看 [point] 这一个像素。
     *
     * 直接读帧里的那一个像素（`Mat.get(row, col, byte[])`，一次 JNI、不产生任何拷贝），
     * 而不是裁 ROI 再 cvtColor —— 单点判定没必要为 1 个像素搬 2.7MB。
     *
     * 帧是 RGBA_8888，内存顺序 **R,G,B,A**（与 ROI 分支的 BGR 相反，别搞混）。
     */
    private fun judgeProbePoint(
        pos: Z.PosData,
        // 参数名故意不叫 frame：会遮住构造参数里的 `frame: () -> Mat?`，读代码时极易看串。
        shot: Mat,
        point: Pair<Int, Int>,
        rgb: IntArray
    ): List<ZPosResolver.Hit> {
        // 空帧上 get(0,0) 会直接抛 OpenCV 断言 —— 而这个函数在 findColor 的 try 之外，
        // 抛出去会变成"整条脚本执行异常"，代价远大于少判一次颜色。
        if (shot.empty()) {
            lastUnsupported = "帧是空的，无法单点取色"
            return emptyList()
        }
        val maxX = (shot.width() - 1).coerceAtLeast(0)
        val maxY = (shot.height() - 1).coerceAtLeast(0)
        val x = point.first.coerceIn(0, maxX)
        val y = point.second.coerceIn(0, maxY)

        val channels = shot.channels()
        val buf = ByteArray(channels)
        shot.get(y, x, buf)
        /*
         * ★ 通道顺序**必须按上游帧格式分派**，不能默认"前三个字节就是 R,G,B"。
         *
         *   4 通道 = CV_8UC4 / RGBA_8888（`snapshotForMatch()` → `readFrameFromRing2()`
         *   的产物；VirtualDisplaySession 里明确标注"CV_8UC4，通道顺序 RGBA"）
         *   → buf[0..2] 就是 R,G,B，直接可用，不需要任何转换。
         *
         *   3 通道 = OpenCV 默认的 **BGR** → 必须反序取。按 RGBA 的读法去读 BGR
         *   会**红蓝互换**：判红判蓝全错，而且不抛异常、不打日志 —— 历史踩过的坑。
         *
         *   其它通道数（1 / 2 / 5+）没有颜色语义，明确拒绝而不是瞎猜。
         */
        val (r, g, b) = when (channels) {
            4 -> Triple(
                buf[0].toInt() and 0xFF,
                buf[1].toInt() and 0xFF,
                buf[2].toInt() and 0xFF
            )
            3 -> Triple(
                buf[2].toInt() and 0xFF,
                buf[1].toInt() and 0xFF,
                buf[0].toInt() and 0xFF
            )
            else -> {
                lastUnsupported = "帧通道数 $channels 不支持颜色判定"
                return emptyList()
            }
        }

        val lo = pos.colorLower
        val hi = pos.colorUpper
        val ok: Boolean
        val rule: String
        if (lo != null && hi != null && lo.size >= 3 && hi.size >= 3) {
            // colorLower/colorUpper 是 RGB 顺序（与 ROI 分支的 Scalar(lo[2],lo[1],lo[0]) 一致）
            ok = r in lo[0]..hi[0] && g in lo[1]..hi[1] && b in lo[2]..hi[2]
            rule = "R${lo[0]}~${hi[0]} G${lo[1]}~${hi[1]} B${lo[2]}~${hi[2]}"
        } else {
            // 容差公式必须与 ROI 分支**逐字一致**：两边判定不同的话，
            // "区域里过、点上不过"就没法向用户解释，也没法排查。
            val tol = ((100 - (pos.similarPercent ?: 100)) * 255 / 100).coerceIn(0, 255)
            ok = kotlin.math.abs(r - rgb[0]) <= tol &&
                kotlin.math.abs(g - rgb[1]) <= tol &&
                kotlin.math.abs(b - rgb[2]) <= tol
            rule = "#%02X%02X%02X ±%d".format(rgb[0], rgb[1], rgb[2], tol)
        }

        lastScore = if (ok) 1.0 else 0.0
        if (!ok) {
            // 报出这个点**实际**是什么颜色：只说"没找到"，用户只能猜着改色值。
            val actual = "#%02X%02X%02X".format(r, g, b)
            lastUnsupported = "指定点 ($x,$y) 实际是 $actual，不在 $rule 内"
            return emptyList()
        }
        lastUnsupported = null
        // 单点判定没有"相关度"可言（就是等于/不等于），命中即 1.0。
        return listOf(ZPosResolver.Hit(x, y, 1.0))
    }

    /**
     * 两块同尺寸图的平均逐像素差（0~255）。
     *
     * 相关系数只看"形状起伏像不像"，对整体色差、纯色块不敏感；
     * 用它做命中后的复核，能把"匹配到一块纯色"剔掉。
     */
    private fun meanAbsDiff(a: Mat, b: Mat): Double {
        val diff = Mat()
        return try {
            org.opencv.core.Core.absdiff(a, b, diff)
            val m = org.opencv.core.Core.mean(diff).`val`
            val ch = minOf(3, m.size)
            (0 until ch).sumOf { m[it] } / ch
        } finally {
            diff.release()
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

    // =========================================== 缩放 / 滤镜 / 单次匹配

    /** 一档缩放策略。kx/ky 允许不等比（协议里 `baseScreenWidthAndHeight` 宽高各算）。 */
    private data class ScaleSpec(val label: String, val kx: Double, val ky: Double)

    /** 一次匹配的结果。`reason` 非 null = 比"没找到"更具体的原因。 */
    private class Attempt(
        val hits: List<ZPosResolver.Hit>,
        val best: Double,
        val reason: String?
    )

    /**
     * 按 `imageScaleType` 算出要尝试的缩放档位（对应协议 m18129 的 `image` 分支）。
     *
     * 字段还没在模型层加强类型，先从 `extra` 取；**取不到就按 `baseScreenWidth`**，
     * 也就是改动前的行为 —— 保证现有脚本的行为不变。
     */
    private fun scaleSpecs(
        pos: Z.PosData,
        img: Z.ImageData,
        frameW: Int,
        frameH: Int
    ): List<ScaleSpec> {
        val type = (pos.extra["imageScaleType"] as? String)?.trim().orEmpty()
            .ifEmpty { "baseScreenWidth" }

        val shotW = img.screenWidth ?: 0
        val shotH = img.screenHeight ?: 0
        val shotDpi = img.screenDpi ?: 0f

        fun kByWidth(): Double? =
            if (shotW > 0 && frameW > 0) frameW.toDouble() / shotW else null
        fun kByHeight(): Double? =
            if (shotH > 0 && frameH > 0) frameH.toDouble() / shotH else null
        /**
     * `dpi` 缩放档。
     *
     * 协议原文是 `imageWidth * (1 / screenDpi)`，它得到的是**物理尺寸（dp）**，
     * 而不是像素。只有在"当前屏密度 = 1"（PC / 1x 模拟器，正是自动精灵的主力
     * 环境）时，dp 才数值上等于像素，所以协议敢这么写。
     *
     * 我们的帧是虚拟屏的物理像素（1280x720，密度 1.5），要还原成"本帧里应有的
     * 像素尺寸"必须再乘当前屏密度：k = density / screenDpi。
     * 少了这个因子，模板会被缩到应有尺寸的 2/3（面积 44%），分数永远过不了阈值，
     * 且不报错，只表现为"没找到"。
     */
    fun kByDpi(): Double? = if (shotDpi > 0f) (density.toDouble() / shotDpi) else null
        fun one(label: String, k: Double?): ScaleSpec? = k?.let { ScaleSpec(label, it, it) }

        val specs: List<ScaleSpec> = when (type) {
            // 图本来就是按当前分辨率截的，再缩就错。
            "none" -> listOf(ScaleSpec("none", 1.0, 1.0))
            "baseScreenHeight" -> listOfNotNull(one(type, kByHeight()))
            "dpi" -> listOfNotNull(one(type, kByDpi()))
            "baseScreenWidthAndHeight" -> {
                val a = kByWidth()
                val b = kByHeight()
                if (a != null && b != null) {
                    listOf(ScaleSpec(type, a, b))
                } else {
                    listOfNotNull(one("baseScreenWidth", a))
                }
            }
            // 四种都试，由 findImage 取"有命中且分数最高"的一档。
            "tryAll" -> {
                val a = kByWidth()
                val b = kByHeight()
                val d = kByDpi()
                listOfNotNull(
                    one("dpi", d),
                    one("baseScreenWidth", a),
                    one("baseScreenHeight", b),
                    if (a != null && b != null) ScaleSpec("baseScreenWidthAndHeight", a, b) else null
                )
            }
            // 缺省与未知取值都按原行为（按屏宽）。
            else -> listOfNotNull(one("baseScreenWidth", kByWidth()))
        }
        // 一个都算不出来（缺 screenWidth/screenHeight/screenDpi）→ 按原尺寸，别瞎缩。
        return specs.ifEmpty { listOf(ScaleSpec("raw", 1.0, 1.0)) }
    }

    /**
     * 按档位缩放模板。
     *
     * ★ 插值方向必须跟着缩放方向走：
     *   缩小用 `INTER_AREA`（面积平均 = 低通，抗混叠）；
     *   放大必须用 `INTER_LINEAR` —— 放大时 `INTER_AREA` 会退化成最近邻，
     *   边缘全是锯齿，相关系数被系统性拉低。原来不分方向一律 AREA，
     *   等于"放大档"白给。
     */
    private fun resizeTo(src: Mat, spec: ScaleSpec): Mat {
        // 比例几乎为 1 就别缩：重采样哪怕一像素也会掉几分，纯损失。
        if (kotlin.math.abs(spec.kx - 1.0) < 0.01 && kotlin.math.abs(spec.ky - 1.0) < 0.01) {
            return src
        }
        val dst = Mat()
        val interp = if (spec.kx < 1.0 || spec.ky < 1.0) {
            org.opencv.imgproc.Imgproc.INTER_AREA
        } else {
            org.opencv.imgproc.Imgproc.INTER_LINEAR
        }
        org.opencv.imgproc.Imgproc.resize(
            src, dst, org.opencv.core.Size(), spec.kx, spec.ky, interp
        )
        return if (dst.empty()) {
            dst.release()
            src
        } else {
            dst
        }
    }

    /** 从 `imageFilter` / `filter` 里取滤镜类型；取不到返回 null。 */
    private fun filterType(pos: Z.PosData): String? {
        val raw = pos.extra["imageFilter"] ?: pos.extra["filter"] ?: return null
        val obj = raw as? org.json.JSONObject ?: return null
        return obj.optString("type").trim().ifEmpty { null }
    }

    /**
     * 用 OTSU 给一张图算二值化阈值（0~255）；空图/算不出来返回 null。
     *
     * ★ 只用来**给模板算一次**，算出的值再传给 [applyFilter] 让 ROI 复用。
     *
     *   直接让两边各自 OTSU 是错的：OTSU 看的是各自直方图 —— 模板是小图（往往只有
     *   目标本身），ROI 是大图（带背景、带别的 UI），两者会得到明显不同的阈值。
     *   同一个元素被切成两种黑白基准之后，相关系数被系统性拉低，逐像素复核也跟着失准。
     *   而用户只能看到"加了滤镜反而更难匹配"，查不出原因。
     *
     * `Imgproc.threshold` 在 OTSU 模式下的**返回值就是它选定的阈值**，正好拿来复用。
     */
    private fun otsuThreshold(src: Mat): Double? {
        if (src.empty()) return null
        val gray = Mat()
        val probe = Mat()
        return try {
            toGray(src, gray)
            val th = org.opencv.imgproc.Imgproc.threshold(
                gray, probe, 0.0, 255.0,
                org.opencv.imgproc.Imgproc.THRESH_BINARY +
                    org.opencv.imgproc.Imgproc.THRESH_OTSU
            )
            if (th.isFinite()) th else null
        } catch (t: Throwable) {
            Log.w(TAG, "OTSU 阈值计算失败", t)
            null
        } finally {
            gray.release()
            probe.release()
        }
    }

    /**
     * 套一层滤镜，返回**新的** Mat（调用方负责 release）；null = 没处理或不支持。
     *
     * 只实现 `grey` / `bw` / `reverse` —— 这三种是纯像素级、无参数的。
     * 其余（lightness/contrast/rgb/changeColor/multi）要读各自参数，而参数形状
     * 在不同版本里变过：**猜错的滤镜比不加滤镜更难查**，宁可不做，
     * 由调用方记一条"未实现"。
     *
     * 输出统一 3 通道 BGR：模板和 ROI 必须同类型，否则 matchTemplate 直接抛断言。
     *
     * @param bwThreshold `bw` 用的二值化阈值（0~255）。**必须由调用方用模板算一次再传进来**，
     *   让模板与 ROI 共用同一个阈值；只有拿不到（null）时才退回各自 OTSU（那会两边基准漂移）。
     */
    private fun applyFilter(src: Mat, type: String?, bwThreshold: Double? = null): Mat? {
        if (type == null || type !in SUPPORTED_FILTERS) return null
        if (src.empty()) return null
        val gray = Mat()
        return try {
            when (type) {
                "grey" -> {
                    toGray(src, gray)
                    val out = Mat()
                    org.opencv.imgproc.Imgproc.cvtColor(
                        gray, out, org.opencv.imgproc.Imgproc.COLOR_GRAY2BGR
                    )
                    out
                }
                "bw" -> {
                    toGray(src, gray)
                    val bin = Mat()
                    // ★ 优先用调用方传入的阈值（= 模板算出的那个），保证两边同一把尺子。
                    //   只有拿不到时才退回 OTSU —— 那是"每张图各算一个"，两边基准会漂。
                    if (bwThreshold != null) {
                        org.opencv.imgproc.Imgproc.threshold(
                            gray, bin, bwThreshold, 255.0,
                            org.opencv.imgproc.Imgproc.THRESH_BINARY
                        )
                    } else {
                        org.opencv.imgproc.Imgproc.threshold(
                            gray, bin, 0.0, 255.0,
                            org.opencv.imgproc.Imgproc.THRESH_BINARY +
                                org.opencv.imgproc.Imgproc.THRESH_OTSU
                        )
                    }
                    val out = Mat()
                    org.opencv.imgproc.Imgproc.cvtColor(
                        bin, out, org.opencv.imgproc.Imgproc.COLOR_GRAY2BGR
                    )
                    bin.release()
                    out
                }
                "reverse" -> {
                    val out = Mat()
                    org.opencv.core.Core.bitwise_not(src, out)
                    out
                }
                else -> null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "滤镜 $type 失败", t)
            null
        } finally {
            gray.release()
        }
    }

    /**
     * `searchMode = outline_2.21`：先 Canny 取轮廓再匹配。
     *
     * 阈值写死 50/150（Canny 常用 1:3 经验值），**不跟着相似度滑块变**：
     * 让滑块同时控制"边缘密度"会让同一脚本在不同阈值下走完全不同的判定，
     * 用户一调结果全变，反而没法排查。
     */
    private fun toEdges(src: Mat): Mat? {
        if (src.empty()) return null
        val gray = Mat()
        val edges = Mat()
        return try {
            toGray(src, gray)
            org.opencv.imgproc.Imgproc.Canny(gray, edges, 50.0, 150.0)
            val out = Mat()
            org.opencv.imgproc.Imgproc.cvtColor(
                edges, out, org.opencv.imgproc.Imgproc.COLOR_GRAY2BGR
            )
            out
        } catch (t: Throwable) {
            Log.w(TAG, "轮廓模式失败", t)
            null
        } finally {
            gray.release()
            edges.release()
        }
    }

    /** BGR / BGRA / GRAY → 单通道灰度。 */
    private fun toGray(src: Mat, dst: Mat) {
        when (src.channels()) {
            1 -> src.copyTo(dst)
            4 -> org.opencv.imgproc.Imgproc.cvtColor(
                src, dst, org.opencv.imgproc.Imgproc.COLOR_BGRA2GRAY
            )
            else -> org.opencv.imgproc.Imgproc.cvtColor(
                src, dst, org.opencv.imgproc.Imgproc.COLOR_BGR2GRAY
            )
        }
    }

    /**
     * 在**已经做过同样变换**的 [region] 上跑一次模板匹配。
     *
     * 为什么从 findImage 里抽出来：`tryAll` 要对同一块 ROI 用多个模板尺度各跑一遍，
     * 而"匹配 + 取峰 + NMS + 逐像素复核"必须完全一致 —— 复制两份迟早跑偏。
     *
     * @param left / top ROI 在整帧里的左上角，用来把区域坐标还原成屏幕坐标
     */
    private fun matchOnce(
        region: Mat,
        tmpl: Mat,
        threshold: Double,
        left: Int,
        top: Int
    ): Attempt {
        if (tmpl.width() > region.width() || tmpl.height() > region.height()) {
            return Attempt(
                emptyList(), -2.0,
                "模板 ${tmpl.width()}×${tmpl.height()} 比搜索范围 " +
                    "${region.width()}×${region.height()} 还大"
            )
        }
        val result = Mat()
        try {
            org.opencv.imgproc.Imgproc.matchTemplate(
                region, tmpl, result, org.opencv.imgproc.Imgproc.TM_CCOEFF_NORMED
            )
            val bestCorr = org.opencv.core.Core.minMaxLoc(result).maxVal

            /*
             * 不只取全局最高点。
             *
             * 只取一个点的话，画面里只要有一处"碰巧更像"就会选中它 ——
             * 而真实目标可能就在旁边，分数低一点点。取所有过线的峰再做
             * 非极大值抑制，才谈得上"找到几处"和"选第几个"。
             */
            val rows = result.rows()
            val cols = result.cols()
            val buf = FloatArray(rows * cols)
            result.get(0, 0, buf)

            val cands = ArrayList<Pair<org.opencv.core.Point, Double>>()
            for (y in 0 until rows) {
                for (x in 0 until cols) {
                    val v = buf[y * cols + x]
                    // 二值化（bw 滤镜）后模板方差可能为 0，CCOEFF 会给 NaN —— 必须挡掉。
                    if (v.isFinite() && v >= threshold) {
                        cands.add(org.opencv.core.Point(x.toDouble(), y.toDouble()) to v.toDouble())
                    }
                }
            }
            cands.sortByDescending { it.second }

            // 非极大值抑制：两个候选挨得太近就没意义（同一个图案）
            val minDist = maxOf(8, minOf(tmpl.width(), tmpl.height()) / 2).toDouble()
            // 峰要**带上自己的相关系数**：协议里 indexNum 缺省是"相似度最高"那一个，
            // 而执行器只能从 Hit.score 读到这个数 —— 只留坐标的话它只能退回"取第一个"。
            val peaks = ArrayList<Pair<org.opencv.core.Point, Double>>()
            for ((pt, sc) in cands) {
                if (peaks.size >= MAX_HITS) break
                val tooClose = peaks.any { k ->
                    Math.hypot(k.first.x - pt.x, k.first.y - pt.y) < minDist
                }
                if (!tooClose) peaks.add(pt to sc)
            }

            if (peaks.isEmpty()) return Attempt(emptyList(), bestCorr, null)

            /*
             * 命中后复核：过线只说明"相关系数够高"，那只反映形状趋势，
             * 大片纯色也能给出高分。再比一次逐像素差，把"看着像但其实是
             * 一块纯色"的假命中剔掉。
             *
             * 允许的差随阈值放宽：阈值 90 → 允许差 35，阈值 70 → 允许差 87。
             */
            val allowMad = 255.0 * (1.0 - threshold) + 10.0
            val hits = ArrayList<ZPosResolver.Hit>()
            var rejected = 0
            for ((pt, sc) in peaks) {
                val px = pt.x.toInt()
                val py = pt.y.toInt()
                // 坐标基准是 region（ROI 内），不是整帧 —— 所以最后统一加 left/top。
                val patch = region.submat(
                    org.opencv.core.Rect(px, py, tmpl.width(), tmpl.height())
                )
                // 复核失败按 0 处理（= 放行），与改动前的行为一致；patch 必须释放。
                val mad = try {
                    meanAbsDiff(patch, tmpl)
                } catch (t: Throwable) {
                    0.0
                } finally {
                    patch.release()
                }
                if (mad <= allowMad) {
                    hits.add(
                        ZPosResolver.Hit(
                            px + left + tmpl.width() / 2,
                            py + top + tmpl.height() / 2,
                            // 传这个峰自己的相关系数：多命中时执行器据此选"最像的那个"。
                            score = sc
                        )
                    )
                } else {
                    rejected++
                }
            }

            if (hits.isEmpty()) {
                return Attempt(
                    emptyList(), bestCorr,
                    "有 ${peaks.size} 处相关系数过线，但逐像素差都太大" +
                        "（允许 ${"%.0f".format(allowMad)}），像是匹配到了纯色区域"
                )
            }
            if (rejected > 0) {
                Log.i(TAG, "匹配：${hits.size} 处通过，$rejected 处被逐像素复核剔除")
            }
            /*
             * ★ 命中结果按"从上到下、从左到右"排序，不是按相关度。
             *
             *   协议（FIELDS.md:156）规定排序是 boundTop 升序、同高再 boundLeft 升序，
             *   因为 indexNum 选的是"第几个"—— 用户看着画面数是"第 2 个图标"，
             *   是按位置数的。按相关度排的话，indexNum="2" 点的是分数第 2 高的，
             *   在多目标场景下会**静默点错**，而且看起来像随机。
             *   相关度只用于阈值过滤和日志。
             */
            hits.sortWith(compareBy({ it.y }, { it.x }))
            return Attempt(hits, bestCorr, null)
        } finally {
            result.release()
        }
    }

    // ------------------------------------------------------ 颜色：单点取色
    //
    // 单点的**坐标计算**已移到 `ZPosResolver.colorProbePoint`（和位置语义同类，
    // 也避免两个人同时改这个文件里的同一段）。本类只保留**判定**（judgeProbePoint）。

    companion object {
        /**
         * OCR 引擎是进程级的：加载 20MB 模型要几百毫秒，
         * 每个动作重建一次会让脚本慢到没法用。
         */
        private var sharedOcr: com.pvz2.auto.maa.OcrEngine? = null

        /** 一次最多返回多少个命中。太多了没意义，用户也不会去选第 50 个。 */
        private const val MAX_HITS = 20

        /** 虚拟屏 240dpi → 1.5（见 [density] 的说明）。 */
        private const val DEFAULT_DENSITY = 1.5f

        /**
         * 已实现的滤镜类型（协议共 9 种）。
         *
         * 只收纯像素级、无参数的那三种；其余要读各自参数，参数形状跨版本变过 ——
         * 猜错的滤镜比不加滤镜更难查，所以明确不做，由 findImage 记一条"未实现"。
         */
        private val SUPPORTED_FILTERS = setOf("grey", "bw", "reverse")

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

            /*
             * ★ 「识别结果里包含要找的文字」= 命中，这是协议语义。
             *
             *   「出现文字」这个条件判断的是"这段文字出没出现"，
             *   不是"识别结果和它一模一样"。所以只要 b 里含 a，就该判成立。
             *
             *   原来这里按长短比例扣分：0.7 + 0.3×(短/长)。
             *   「开始战斗」出现在「开始战斗开始战斗」里（游戏按钮渲染了两遍）
             *   只有 0.7+0.3×(4/8) = 0.85，**低于默认阈值 0.90** ——
             *   差 0.05 分，整个脚本卡在第一步，而界面上只说"没匹配到"。
             *   实测就发生在刷金币的第 2 条。
             *
             *   单字仍然保留比例扣分：否则「1」会匹配上「123456」，
             *   那种误命中比漏命中更难查。
             */
            if (b.contains(a)) {
                if (a.length >= 2) return 0.95
                val short = minOf(a.length, b.length).toDouble()
                val long = maxOf(a.length, b.length).toDouble()
                return 0.7 + 0.3 * (short / long)
            }
            // 识别结果只是目标的一段（被遮挡/截断）—— 次一档
            if (a.contains(b)) {
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

        /**
     * 颜色判定要求的最小命中像素比例。
     *
     * ★ 必须是 0：Maa 的 ColorMatch 默认 `count: 1`，**只要有一个像素
     *   落在 lower~upper 之间就算命中**。我一度改成"要求一半像素"，
     *   直接把「是否进入关内」这种正常闸门卡死了 —— 那是改坏了语义。
     *
     *   冷却后卡片仍然命中的问题，正确解法是 lower/upper 逐通道上下界
     *   （见 findColor），不是收紧像素数量。
     */
    private const val COLOR_MIN_RATIO = 0.0

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
    /**
     * 找节点（无障碍）。
     *
     * 游戏跑在自建虚拟屏上，节点树只有无障碍框架有 —— 走 [Pvz2NodeService]。
     * 服务没启用时**明确报出来**，而不是笼统说"没找到"：
     * 前者让用户去开服务，后者会让他去调阈值、换模板。
     *
     * 坐标：虚拟屏那层窗口的 bounds 就是 `0,0 - 1280,720`，
     * 和虚拟屏分辨率一致，所以节点 bounds 直接当虚拟屏坐标用。
     */
    override fun findNode(pos: Z.PosData, rect: IntArray): List<ZPosResolver.Hit> {
        if (!Pvz2NodeService.isReady()) {
            lastUnsupported = "无障碍服务未启用（出现节点/点击节点需要它）：到 系统设置→无障碍" +
                " 里打开「PVZ2 自动化」"
            return emptyList()
        }
        /*
         * ★ 实测：无障碍服务只能拿到**它关联那块屏**（默认屏 display 0）的窗口。
         *
         *   服务连上时枚举过：6 个窗口全是 display 0（systemui / 游戏助手 / 本 app），
         *   而游戏在我们自建的虚拟屏上是 displayId=19，**不在 getWindows() 里**
         *   （`dumpsys accessibility` 会把它列在 Display[19] 下，但那只是系统内部
         *   的登记表，不发给服务）。
         *
         *   所以只要游戏还跑在虚拟屏，节点查找就永远查不到 —— 这不是阈值或配置问题，
         *   说清楚，别让用户去调别的。
         */
        val vdId = runCatching {
            com.pvz2.auto.service.VirtualDisplaySession.sharedState.value.displayId
        }.getOrDefault(0)
        if (vdId > 0) {
            lastUnsupported = "无障碍只看得到默认屏幕的窗口；游戏在虚拟屏（displayId=$vdId），" +
                "那一层拿不到 → 节点查找对本游戏无效，请改用「出现文字 / 出现颜色 / 出现图片」"
            return emptyList()
        }
        val e = pos.extra
        fun str(key: String): String? =
            (e[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }

        val text = pos.text?.takeIf { it.isNotBlank() } ?: str("text")
        val rectObj = android.graphics.Rect(rect[0], rect[1], rect[2], rect[3])
        val hits = Pvz2NodeService.find(
            text = text,
            textIsRegExp = boolOf(e["textIsRegExp"]),
            className = str("className"),
            idResName = str("idResName"),
            packageName = str("packageName"),
            desc = str("desc"),
            minDepth = (e["depth"] as? Number)?.toInt() ?: 0,
            matchContains = !eqOf(e["textMatchMode"]),
            rect = rectObj
        )
        if (hits.isEmpty()) {
            lastUnsupported = "没有找到这样的节点（当前界面 ${Pvz2NodeService.dumpWindows().lines().size} 个窗口）"
            return emptyList()
        }
        return hits.map { h ->
            ZPosResolver.Hit(h.centerX, h.centerY, score = 1.0)
        }
    }

    private fun boolOf(v: Any?): Boolean = when (v) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is String -> v.trim().let { it == "true" || it == "1" || it == "是" }
        else -> false
    }

    /** textMatchMode == "equal" 时用相等而不是包含。 */
    private fun eqOf(v: Any?): Boolean =
        (v as? String)?.trim()?.let { it == "equal" || it == "相等" } ?: false


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
