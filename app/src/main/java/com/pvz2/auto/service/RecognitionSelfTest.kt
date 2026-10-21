package com.pvz2.auto.service

import android.content.Context
import android.util.Log
import com.pvz2.auto.maa.Recognition
import com.pvz2.auto.maa.Recognizer
import org.opencv.core.Mat

/**
 * 识别自检：测**真实耗时**、看分数、看命中位置，并对比"全屏 vs 带 ROI"。
 *
 * ## 为什么必须报"搜索区面积"
 *
 * `matchTemplate` 的代价 ≈ **搜索区面积 × 模板面积**。
 * 1280×720 全屏（921600 px）搜一个 185×73 的模板，是
 * `(1280−185+1) × (720−73+1) ≈ 71 万个位置`，每个位置算 13505 次乘加，
 * 合计约 **96 亿次运算** —— 手机上就是一百多毫秒。
 *
 * 而真实 pipeline 每个节点都带 `roi`，搜索区可能只有 640×360（23 万 px），
 * 位置数少 4 倍，耗时跟着少几倍。
 *
 * ## 为什么每个模板跑两次
 *
 * 一次全屏、一次限定 ROI。**这不是为了凑数据，是为了量出 roi 的收益**：
 * 同一张图、同一个模板，只有搜索区不同，耗时差多少一目了然。
 * 有了这个数字才知道"给节点加 roi"值多少，而不用靠猜。
 *
 * 之前这里也跑两次，但那是因为代码写重了（`scoreStats` + `recognize`
 * 各一次），报出来是真实值的两倍 —— 那是测量假象，方向都会被带偏。
 * 现在两次是**主动的对照实验**，分别计时，各自有效。
 */
class RecognitionSelfTest(private val context: Context) {

    /** 单个模板在**一种搜索区**下的结果。 */
    data class Probe(
        val name: String,
        val roiDesc: String,
        val score: Double,
        val hitX: Double,
        val hitY: Double,
        val boxDesc: String,
        val threshold: Double,
        /** 完整识别一次的耗时（毫秒）。 */
        val costMs: Double,
        /** 搜索区面积（像素）。全屏 = 整帧。 */
        val searchPixels: Int,
        /** 模板面积（像素）。代价 ≈ 搜索区面积 × 模板面积。 */
        val templatePixels: Int
    ) {
        val workUnits: Long
            get() = searchPixels.toLong() * templatePixels.toLong()

        val passed: Boolean get() = score >= threshold

        override fun toString(): String {
            if (score < 0) return "  ✗ $roiDesc  模板未加载（路径错）"
            val mark = if (passed) "✓" else "✗"
            return "$mark $roiDesc  分数 ${"%.3f".format(score)}  耗时 ${"%.2f".format(costMs)} ms\n" +
                "     搜索区 $searchPixels px × 模板 $templatePixels px" +
                " = ${workUnits / 1_000_000} M 次运算\n" +
                "     命中 ($hitX, $hitY)  框 $boxDesc"
        }
    }

    /**
     * 跑一批模板，每个模板测「全屏」与「限定 ROI」两种搜索区。
     *
     * @param frame 虚拟屏帧（CV_8UC4，RGBA）。**调用方负责 release**。
     * @param probeRoi 对照实验用的 ROI（720p 逻辑 `[x,y,w,h]`）。
     *   默认取屏幕中央一半面积 —— 那是一个现实里很常见的 ROI 大小。
     */
    fun run(
        frame: Mat,
        templates: List<String>,
        threshold: Double = 0.7,
        probeRoi: IntArray = intArrayOf(
            frame.width() / 4, frame.height() / 4,
            frame.width() / 2, frame.height() / 2
        )
    ): List<Probe> {
        if (frame.empty()) return emptyList()

        val recognizer = Recognizer(context)
        try {
            recognizer.setScreen(frame.width(), frame.height())
            if (recognizer.adapter == null) {
                Log.w(TAG, "setScreen 后 adapter 仍为空，无法换算坐标")
                return emptyList()
            }

            // 预热：首次要从 assets 解 PNG 再 resize，是一次性开销。
            // 不预热的话它会算进第一个模板，看起来像那个模板特别慢。
            templates.firstOrNull()?.let { first ->
                runCatching { recognizeOnce(recognizer, frame, first, null) }
            }

            val full = intArrayOf(0, 0, frame.width(), frame.height())
            val probes = ArrayList<Probe>(templates.size * 2)

            for (path in templates) {
                probes += measure(recognizer, frame, path, null, "全屏", threshold, full)
                probes += measure(recognizer, frame, path, probeRoi, "ROI", threshold, probeRoi)
            }
            return probes
        } finally {
            recognizer.release()
        }
    }

    private fun measure(
        recognizer: Recognizer,
        frame: Mat,
        path: String,
        roi: IntArray?,
        label: String,
        threshold: Double,
        searchRect: IntArray
    ): Probe {
        val searchPixels = searchRect[2] * searchRect[3]
        val start = System.nanoTime()
        val hit = recognizeOnce(recognizer, frame, path, roi)
        val costMs = (System.nanoTime() - start) / 1_000_000.0

        return if (hit == null) {
            Probe(path, label, -1.0, -1.0, -1.0, "模板未加载", threshold, costMs, searchPixels, 0)
        } else {
            val b = hit.box
            val tw = b.width().coerceAtLeast(0)
            val th = b.height().coerceAtLeast(0)
            Probe(
                name = path,
                roiDesc = label,
                score = hit.score,
                hitX = hit.centerX,
                hitY = hit.centerY,
                boxDesc = if (tw == 0 || th == 0) "（空）" else "(${b.left},${b.top}) ${tw}×${th}",
                threshold = threshold,
                costMs = costMs,
                searchPixels = searchPixels,
                templatePixels = tw * th
            )
        }
    }

    /** 单次识别。阈值 0 → 一定返回结果，能拿到原始分数。 */
    private fun recognizeOnce(
        recognizer: Recognizer,
        frame: Mat,
        path: String,
        roi: IntArray?
    ) = runCatching {
        recognizer.recognize(
            frame,
            Recognition.Template(templates = listOf(path), roi = roi, threshold = 0.0)
        )
    }.onFailure { Log.w(TAG, "识别 $path 抛异常", it) }
        .getOrNull()


    private companion object {
        const val TAG = "RecognitionSelfTest"
    }
}
