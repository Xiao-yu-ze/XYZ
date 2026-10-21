package com.pvz2.auto.service

import android.content.Context
import android.util.Log
import com.pvz2.auto.maa.Recognizer
import org.opencv.core.Mat

/**
 * 识别自检：拿虚拟屏的实时帧跑模板匹配，报出**分数、命中位置、峰值孤立度**。
 *
 * ## 为什么不能只看最高分
 *
 * `matchTemplate` 在搜索区每个位置都算一个分数再取最大值。全屏搜索时
 * 那是约 **120 万个**位置（(1280−42+1)×(720−44+1)）——
 * 从 120 万个样本里取 max，**即使模板根本不在画面里，最大值也会明显偏高**。
 * 这是极值统计的必然结果，不是 bug。
 *
 * 真机实测印证：画面上不存在的"叉叉"模板，全屏搜索拿到 0.674；
 * 真实存在并确实命中的"开始游戏"是 0.974。两者只差 0.3，
 * 单靠阈值 0.7 几乎没有区分度。
 *
 * ## 判据：峰值孤不孤立
 *
 * 所以除了最高分，还要看**次高分**（扣掉峰值邻域后的最大值）：
 *
 *   孤立度 = 最高分 − 次高分
 *
 *  · 孤立度高（> 0.1）→ 有一个明显高于周围的锐利峰值 = 真命中
 *  · 孤立度低（< 0.05）→ "到处都是差不多高的分" = 噪声里的最大值
 *
 * 真实 pipeline 里每个节点都有 `roi`，搜索区只有几百像素宽，
 * 候选位置少两个数量级，虚高问题本来就不存在。
 * 自检默认全屏，是**最坏情况**，用来暴露问题而不是代表生产表现。
 */
class RecognitionSelfTest(private val context: Context) {

    /** 单个模板的一次检测结果。 */
    data class Probe(
        val name: String,
        val best: Double,
        val secondBest: Double,
        val hitX: Double,
        val hitY: Double,
        val boxDesc: String,
        val threshold: Double
    ) {
        /** 峰值孤立度。高 = 真命中，低 = 噪声。 */
        val margin: Double get() = best - secondBest

        val scorePassed: Boolean get() = best >= threshold
        val isolated: Boolean get() = margin >= MARGIN_THRESHOLD

        /** 两个条件都满足才算可信。 */
        val trustworthy: Boolean get() = scorePassed && isolated

        override fun toString(): String {
            if (best < 0) return "✗ $name  模板未加载（路径错）"
            val mark = when {
                trustworthy -> "✓"
                scorePassed -> "⚠"
                else -> "✗"
            }
            val verdict = when {
                trustworthy -> "可信"
                scorePassed -> "分数够但峰值不孤立 —— 很可能是噪声里的最大值"
                else -> "未达阈值"
            }
            return "$mark $name  分数 ${"%.3f".format(best)}（阈值 ${"%.2f".format(threshold)}）\n" +
                "   次高 ${"%.3f".format(secondBest)}  孤立度 ${"%.3f".format(margin)} → $verdict\n" +
                "   命中 ($hitX, $hitY)  框 $boxDesc"
        }
    }

    /**
     * 用 [frame] 跑一批模板。
     *
     * @param templates 模板在 assets 里的相对路径，例如 `Start_game/开始游戏.png`
     * @param rois 与 templates 一一对应的 720p 逻辑 ROI（`[x,y,w,h]`）；
     *   传 null 表示该模板全屏搜索。**全屏是最坏情况**，生产里应当都给 ROI。
     * @param frame 虚拟屏帧（CV_8UC4，RGBA）。**调用方负责 release**。
     */
    fun run(
        frame: Mat,
        templates: List<String>,
        rois: List<IntArray?> = emptyList(),
        threshold: Double = 0.7
    ): List<Probe> {
        if (frame.empty()) return emptyList()

        val recognizer = Recognizer(context)
        try {
            recognizer.setScreen(frame.width(), frame.height())
            if (recognizer.adapter == null) {
                Log.w(TAG, "setScreen 后 adapter 仍为空，无法换算坐标")
                return emptyList()
            }

            return templates.mapIndexed { i, path ->
                val roi = rois.getOrNull(i)
                val stats = runCatching { recognizer.scoreStats(frame, path, roi) }
                    .onFailure { Log.w(TAG, "scoreStats $path 抛异常", it) }
                    .getOrNull()

                if (stats == null) {
                    Probe(path, -1.0, -1.0, -1.0, -1.0, "模板未加载", threshold)
                } else {
                    // 再取一次命中位置（scoreStats 只给分数，位置要从 recognize 拿）。
                    val hit = runCatching {
                        recognizer.recognize(
                            frame,
                            com.pvz2.auto.maa.Recognition.Template(
                                templates = listOf(path),
                                roi = roi,
                                threshold = 0.0
                            )
                        )
                    }.getOrNull()

                    val b = hit?.box
                    Probe(
                        name = path,
                        best = stats.best,
                        secondBest = stats.secondBest,
                        hitX = hit?.centerX ?: -1.0,
                        hitY = hit?.centerY ?: -1.0,
                        boxDesc = when {
                            b == null || (b.width() == 0 && b.height() == 0) -> "（空）"
                            else -> "(${b.left},${b.top}) ${b.width()}×${b.height()}"
                        },
                        threshold = threshold
                    )
                }
            }
        } finally {
            recognizer.release()
        }
    }

    private companion object {
        const val TAG = "RecognitionSelfTest"

        /**
         * 孤立度下限。
         *
         * 0.05 是从"同一物体在相邻位置也给高分"这个事实反推的：
         * 峰值邻域已经被抹掉，剩下的次高分如果还只低 0.05 以内，
         * 说明整片搜索区都差不多，那个"最高分"没有意义。
         */
        const val MARGIN_THRESHOLD = 0.05
    }
}
