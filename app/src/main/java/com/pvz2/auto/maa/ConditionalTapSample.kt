package com.pvz2.auto.maa

import android.content.Context
import android.util.Log
import com.pvz2.auto.core.ScreenCapturer
import com.pvz2.auto.core.TapJitter
import com.pvz2.auto.core.TouchInjector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * ============================================================================
 * 示例：识别到就点 A，没识别到就点 B
 * ============================================================================
 *
 * 这个文件是**教学示例**，不接入主流程，可以直接照抄这一段的写法。
 *
 * 核心就三步：
 *   1. 抓一帧                      capturer.capture()
 *   2. 在帧里找模板                recognizer.recognize(frame, Recognition.Template(...))
 *   3. 按结果点不同坐标            injector.tap(...)
 *
 * 注意第 2 步返回的坐标是 **720p 逻辑坐标**，必须过 ScreenAdapter 换算成物理像素
 * 才能点击。这是最容易漏的一步。
 */
class ConditionalTapSample(
    private val capturer: ScreenCapturer,
    private val injector: TouchInjector,
    private val recognizer: Recognizer
) {

    /**
     * 单次执行：识别 [template]，
     *   - 命中 → 点 [hitX], [hitY]
     *   - 未命中 → 点 [missX], [missY]
     *
     * @param template assets 里的模板路径，例如 "maapvz/Endless/无尽挑战.png"
     * @param roi      可选搜索区域 `[x,y,w,h]`（720p 逻辑坐标）；null = 全屏
     * @param threshold 匹配阈值，0~1，越大越严格
     * @return 本次是否命中
     */
    suspend fun tapByRecognition(
        template: String,
        hitX: Int,
        hitY: Int,
        missX: Int,
        missY: Int,
        roi: IntArray? = null,
        threshold: Double = 0.80
    ): Boolean {

        // ---------- 1. 抓一帧 ----------
        // capture() 是阻塞调用（要走 Shizuku 的 shell 往返），
        // 所以本方法必须在 Dispatchers.IO 协程里调用。
        val frame = capturer.capture() ?: run {
            Log.w(TAG, "截图失败，本次跳过")
            return false
        }

        try {
            // ---------- 2. 识别 ----------
            // setScreen 用**真实帧尺寸**校准缩放。
            // 如果设备不是 1280x720，模板会在这里按比例缩放；
            // 返回的命中坐标也已经是 720p 逻辑坐标。
            recognizer.setScreen(frame.width(), frame.height())

            val hit = recognizer.recognize(
                frame,
                Recognition.Template(
                    templates = listOf(template),
                    roi = roi,
                    threshold = threshold
                )
            )

            // ---------- 3. 按结果选择要点的坐标 ----------
            val (targetX, targetY) = if (hit != null) {
                Log.i(TAG, "命中 $template  分数=%.4f  逻辑坐标=(%.0f,%.0f)"
                    .format(hit.score, hit.centerX, hit.centerY))
                hitX to hitY
            } else {
                Log.i(TAG, "未命中 $template，走兜底分支")
                missX to missY
            }

            tap(targetX, targetY)
            return hit != null

        } finally {
            // ---------- 4. 释放 ----------
            // Mat 是 native 内存，不受 GC 管理。
            // 长时间循环里漏掉这一句会 OOM，必须放 finally。
            frame.release()
        }
    }

    /**
     * 把 720p 逻辑坐标换算成物理像素并点击。
     *
     * 换算公式（见 ScreenAdapter）：
     *     物理X = offsetX + 逻辑X * scale
     *     物理Y =           逻辑Y * scale
     * 其中 scale = 屏高/720，offsetX = (屏宽 - 1280*scale)/2
     */
    private fun tap(logicalX: Int, logicalY: Int) {
        val adapter = recognizer.adapter ?: run {
            Log.w(TAG, "还没有屏幕适配器（没抓到过帧），无法换算坐标")
            return
        }

        val point = adapter.toScreen(logicalX.toFloat(), logicalY.toFloat())

        // ±5px 随机抖动：固定像素的重复点击容易被反自动化检测识别出来
        val px = point.x.roundToInt() + TapJitter.dx()
        val py = point.y.roundToInt() + TapJitter.dy()

        Log.i(TAG, "点击 逻辑($logicalX,$logicalY) -> 物理($px,$py)")
        injector.tap(px.toFloat(), py.toFloat())
    }

    /**
     * 循环版：每隔 [intervalMs] 执行一次上面的判断，直到协程被取消。
     *
     * 每个循环体单独 try/catch —— 单次失败（截图超时、识别异常）只跳过本轮，
     * 不能让整个循环崩掉。
     */
    suspend fun loop(
        template: String,
        hitX: Int,
        hitY: Int,
        missX: Int,
        missY: Int,
        roi: IntArray? = null,
        threshold: Double = 0.80,
        intervalMs: Long = 500
    ) {
        Log.i(TAG, "开始循环：命中点($hitX,$hitY) 未命中点($missX,$missY)")
        while (true) {
            try {
                tapByRecognition(template, hitX, hitY, missX, missY, roi, threshold)
            } catch (c: CancellationException) {
                throw c          // 取消要透传，不能被吞掉
            } catch (t: Throwable) {
                Log.w(TAG, "本轮失败，跳过", t)
            }
            delay(intervalMs)
        }
    }

    private companion object {
        const val TAG = "ConditionalTapSample"
    }
}
