package com.pvz2.auto.maa

import android.content.Context
import android.util.Log
import com.pvz2.auto.core.ScreenCapturer
import org.opencv.core.Mat

/**
 * 离线自检：在当前屏幕上依次识别一批模板，把**分数**打出来。
 *
 * 为什么要专门做这个：识别层没法靠"看截图"验证（我这边看不到图片），
 * 而模板匹配天然会给出一个 0~1 的分数 —— 分数本身就是证据。
 * 只要日志里出现 ≥ 阈值的分数，就说明模板、缩放、ROI、匹配算法这一整条链路都是对的。
 *
 * 不是产品功能，仅用于开发期标定，因此不做任何点击。
 */
class RecognitionProbe(
    private val context: Context,
    private val capturer: ScreenCapturer,
    private val recognizer: Recognizer
) {

    /**
     * 探测一组模板，返回每个模板的最佳分数（不设阈值，全都要看到）。
     *
     * @param candidates 相对 assets 的模板路径列表
     */
    fun probe(candidates: List<String>, roi: IntArray? = null): List<ProbeResult> {
        val frame = capturer.capture() ?: run {
            Log.w(TAG, "截图失败，无法探测")
            return emptyList()
        }

        return try {
            recognizer.setScreen(frame.width(), frame.height())
            Log.i(TAG, "帧尺寸 ${frame.width()}x${frame.height()}，缩放 ${recognizer.adapter?.scale}")

            candidates.map { path ->
                // 阈值设成 0，这样任何分数都能拿到 —— 探测的目的就是看真实分数。
                val recognition = Recognition.Template(
                    templates = listOf(path),
                    roi = roi,
                    threshold = 0.0
                )
                val hit = recognizer.recognize(frame, recognition)
                ProbeResult(
                    path = path,
                    score = hit?.score ?: -1.0,
                    logicalX = hit?.centerX,
                    logicalY = hit?.centerY
                )
            }
        } finally {
            frame.release()
        }
    }

    /** 逐条打印，方便直接读日志。 */
    fun probeAndLog(candidates: List<String>, roi: IntArray? = null) {
        val results = probe(candidates, roi)
        Log.i(TAG, "================ 识别探测结果 ================")
        for (r in results) {
            val line = "%-42s 分数=%.4f  位置=(%.0f,%.0f)".format(
                r.path, r.score, r.logicalX ?: -1.0, r.logicalY ?: -1.0
            )
            Log.i(TAG, "$line  ${verdict(r.score)}")
        }
        Log.i(TAG, "=============================================")
    }

    /**
     * 分级即"这个分数够不够用"。
     *
     * 注意这里的档位是**参考**，不是判定依据：每个节点的真实阈值由流水线自己带着
     * （无尽挑战用 0.7~0.8）。探测的目的是看到原始分数，再由流水线阈值决定去留。
     */
    private fun verdict(score: Double): String = when {
        score < 0 -> "未加载/尺寸不符"
        score >= 0.90 -> "极强"
        score >= 0.80 -> "达无尽挑战阈值"
        score >= 0.70 -> "达默认阈值 0.7"
        score >= 0.50 -> "偏弱"
        else -> "未命中"
    }

    data class ProbeResult(
        val path: String,
        val score: Double,
        val logicalX: Double?,
        val logicalY: Double?
    )

    private companion object {
        const val TAG = "RecognitionProbe"
    }
}

/** 探测用的候选清单：全部来自 MaaPVZ 无尽挑战流程实际引用的模板。 */
object ProbeTargets {

    /**
     * 路径**不加任何前缀**。
     *
     * 这些字符串和 pipeline JSON 里写的完全一致，因为 `assets/` 已与 MaaPVZ 的
     * `resource/image/` 一一对应（见 tools/sync-maapvz-assets.ps1）。
     * 探测路径与实跑路径共用同一套，避免"探测能过、实跑找不到"的分叉。
     */
    val ALL: List<String> = listOf(
        // 主界面 / 入口
        "create_yard/竞技.png",
        "Endless/无尽挑战.png",
        // 局内关键 UI
        "General/能量豆.png",
        "General/暂停.png",
        "Endless/frame/boss/僵王的头.png",
        "Endless/frame/boss/功夫僵王.png",
        "Endless/frame/僵尸头像.png",
        // 结算 / 失败
        "Endless/继续挑战_恭喜过关.png",
        "Endless/out.png",
        "Endless/yes.png",
        "Start_game/叉叉.png"
    )
}
