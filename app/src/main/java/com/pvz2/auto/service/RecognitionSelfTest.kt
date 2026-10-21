package com.pvz2.auto.service

import android.content.Context
import android.util.Log
import com.pvz2.auto.maa.Recognition
import com.pvz2.auto.maa.Recognizer
import org.opencv.core.Mat

/**
 * 识别自检：拿虚拟屏的实时帧跑一遍模板匹配，把**分数和命中位置**报出来。
 *
 * ## 为什么需要它
 *
 * 注入已经验证能进虚拟屏了（`input -d` 通、直注也通）。接下来要回答的是
 * **"识别准不准"** —— 而这件事没法从日志推断：
 *
 *  · 分数 0.65 是"模板不对"还是"阈值定高了"，只能看具体数字；
 *  · 命中了但点不中，是"框算错了"还是"坐标没换算"，只能看命中框；
 *  · 虚拟屏是 1280×720 而模板是 720p 基准，缩放对不对也只有实测。
 *
 * 这个自检不点任何东西（只报数字），把上面三件事一次说清。
 * 确认分数和位置都对之后，再让脚本去点。
 */
class RecognitionSelfTest(private val context: Context) {

    /** 单个模板的一次检测结果。 */
    data class Probe(
        val name: String,
        val score: Double,
        val hitX: Double,
        val hitY: Double,
        val boxDesc: String,
        val threshold: Double
    ) {
        val passed: Boolean get() = score >= threshold

        override fun toString(): String {
            val mark = if (passed) "✓" else "✗"
            return "$mark $name  分数 ${"%.3f".format(score)}（阈值 ${"%.2f".format(threshold)}）\n" +
                "   命中 ($hitX, $hitY)  框 $boxDesc"
        }
    }

    /**
     * 用 [frame] 跑一批模板。
     *
     * ## 为什么要跑两遍
     *
     * `Recognizer.recognize()` 在分数低于阈值时直接返回 null —— 也就是说
     * **失败时看不到分数**。而"分数 0.65"和"完全没找到"是两种完全不同的
     * 问题（前者是模板或缩放的偏差，后者是 ROI 或资源路径错了），
     * 分不清就没法修。
     *
     * 所以每个模板跑两次：先用阈值 0 拿到原始分数与位置，
     * 再用真实阈值判断是否达成。前者一定命中（除非帧为空）。
     *
     * @param templates 模板在 assets 里的相对路径，例如 `Start_game/开始游戏.png`
     * @param frame 虚拟屏帧（CV_8UC4，RGBA）。**调用方负责 release**。
     */
    fun run(frame: Mat, templates: List<String>, threshold: Double = 0.7): List<Probe> {
        if (frame.empty()) return emptyList()

        val recognizer = Recognizer(context)
        try {
            recognizer.setScreen(frame.width(), frame.height())
            if (recognizer.adapter == null) {
                Log.w(TAG, "setScreen 后 adapter 仍为空，无法换算坐标")
                return emptyList()
            }

            return templates.map { path ->
                // 第一遍：阈值 0，只为拿到原始分数。
                val raw = runCatching {
                    recognizer.recognize(
                        frame,
                        Recognition.Template(templates = listOf(path), threshold = 0.0)
                    )
                }.onFailure { Log.w(TAG, "识别 $path 抛异常", it) }
                    .getOrNull()

                if (raw == null) {
                    // 连阈值 0 都没命中 —— 说明模板压根没加载上（路径错）。
                    Probe(path, -1.0, -1.0, -1.0, "模板未加载", threshold)
                } else {
                    val b = raw.box
                    Probe(
                        name = path,
                        score = raw.score,
                        hitX = raw.centerX,
                        hitY = raw.centerY,
                        boxDesc = if (b.width() == 0 && b.height() == 0) {
                            "（空）"
                        } else {
                            "(${b.left},${b.top}) ${b.width()}×${b.height()}"
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
    }
}
