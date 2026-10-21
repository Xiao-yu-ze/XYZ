package com.pvz2.auto.ui.page

import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.pvz2.auto.R
import com.pvz2.auto.service.RecognitionSelfTest
import com.pvz2.auto.service.VirtualDisplaySession
import com.pvz2.auto.shizuku.ShizukuUserServiceClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.core.Mat

/**
 * 第 3 页：诊断。
 *
 * 两项自检，各管一件事，互不依赖：
 *
 *  · **注入自检** —— 事件能不能进到虚拟屏。不经过识别、不经过脚本，
 *    直接用 AIDL 往那块屏发一次点击。它失败说明通道坏了，
 *    而不是识别错了 —— 这两者的排查方向完全相反。
 *  · **识别自检** —— 找到了没有、分数多少、搜索区多大、花了多久。
 *    它不点任何东西，只报数字。
 *
 * 两项共用一个结果区：一次只跑一项，分成两块只会让页面变长。
 */
class DiagPage(
    private val host: AppCompatActivity,
    private val root: View
) {

    private val TAG = "DiagPage"

    private val etTapX: EditText = root.findViewById(R.id.etTapX)
    private val etTapY: EditText = root.findViewById(R.id.etTapY)
    private val tvResult: TextView = root.findViewById(R.id.tvTestTapResult)

    fun bind() {
        root.findViewById<MaterialButton>(R.id.btnTestTap).setOnClickListener {
            testVirtualDisplayTap()
        }
        root.findViewById<MaterialButton>(R.id.btnRecogTest).setOnClickListener {
            runRecognitionSelfTest()
        }
    }

    /** 注入自检：往虚拟屏正中（或指定坐标）发一次点击，直接报系统是否接受。 */
    private fun testVirtualDisplayTap() {
        val displayId = VirtualDisplaySession.sharedState.value.displayId
        if (displayId <= 0) {
            tvResult.setTextColor(ContextCompat.getColor(host, R.color.state_bad))
            tvResult.text = host.getString(R.string.recog_no_frame)
            return
        }

        // 坐标从输入框读，空着用屏幕正中。上界取会话的真实帧尺寸，
        // 而不是页面里的常量 —— 换分辨率时不会把坐标夹小。
        val st = VirtualDisplaySession.sharedState.value
        val w = st.frameWidth.takeIf { it > 0 } ?: VD_WIDTH
        val h = st.frameHeight.takeIf { it > 0 } ?: VD_HEIGHT
        val x = etTapX.text.toString().toIntOrNull()?.coerceIn(0, w - 1) ?: (w / 2)
        val y = etTapY.text.toString().toIntOrNull()?.coerceIn(0, h - 1) ?: (h / 2)

        tvResult.setTextColor(ContextCompat.getColor(host, R.color.ink_muted))
        tvResult.text = host.getString(R.string.test_tap_running, displayId)

        host.lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                val client = ShizukuUserServiceClient(host.applicationContext)
                try {
                    client.bind()
                    val service = client.await()
                        ?: return@withContext "ERROR: Shizuku 服务未连接（${client.lastError ?: "超时"}）"

                    val ok = runCatching { service.tapOnDisplay(displayId, x, y, 80) }
                        .onFailure { Log.w(TAG, "测试点击抛异常", it) }
                        .getOrDefault(false)

                    if (ok) {
                        "✓ 系统接受了这次点击（display=$displayId, $x,$y）\n" +
                            "说明注入通道是通的；若游戏仍无反应，问题在识别/坐标。"
                    } else {
                        "✗ 系统拒绝了这次点击（display=$displayId, $x,$y）\n" +
                            "常见原因：游戏窗口不在这块屏上 / 没有可触摸窗口 /\n" +
                            "窗口设了 FLAG_NOT_TOUCHABLE。"
                    }
                } catch (t: Throwable) {
                    "ERROR: ${t.message}"
                } finally {
                    runCatching { client.unbind(remove = false) }
                }
            }
            tvResult.text = text
            tvResult.setTextColor(ContextCompat.getColor(host, R.color.ink_muted))
        }
    }

    /**
     * 识别自检：跑一遍模板匹配，报出分数、耗时、搜索区面积。
     *
     * 每个模板测两次（全屏 / 限 ROI），是为了**量出 roi 到底值多少**：
     * 同一张图、同一个模板，只有搜索区不同，耗时差多少一目了然。
     */
    private fun runRecognitionSelfTest() {
        val bitmap = VirtualDisplaySession.previewFlow.value
        if (bitmap == null) {
            tvResult.setTextColor(ContextCompat.getColor(host, R.color.state_bad))
            tvResult.text = host.getString(R.string.recog_no_frame)
            return
        }

        tvResult.setTextColor(ContextCompat.getColor(host, R.color.ink_muted))
        tvResult.text = host.getString(R.string.recog_running)

        host.lifecycleScope.launch {
            val text = withContext(Dispatchers.Default) {
                try {
                    if (!org.opencv.android.OpenCVLoader.initLocal()) {
                        return@withContext "ERROR: OpenCV 加载失败"
                    }
                    // 不 recycle：这张 Bitmap 归 previewFlow 所有，
                    // 回收了 ImageView 会拿到一张废图。
                    val mat = Mat()
                    org.opencv.android.Utils.bitmapToMat(bitmap, mat)
                    val probes = try {
                        RecognitionSelfTest(host.applicationContext)
                            .run(frame = mat, templates = PROBE_TEMPLATES)
                    } finally {
                        mat.release()
                    }

                    if (probes.isEmpty()) {
                        "没有任何结果 —— 帧为空或模板全部没加载上。"
                    } else {
                        buildString {
                            appendLine("画面 ${bitmap.width}×${bitmap.height}，每个模板测两种搜索区")
                            appendLine("─────────────────────────────")
                            probes.chunked(2).forEach { pair ->
                                val full = pair.getOrNull(0)
                                val roi = pair.getOrNull(1)
                                if (full != null) {
                                    appendLine(full.name.substringAfterLast('/'))
                                    appendLine(full.toString())
                                }
                                if (roi != null) {
                                    appendLine(roi.toString())
                                    if (full != null && roi.costMs > 0) {
                                        appendLine(
                                            "     → ROI 快了 ${"%.1f".format(full.costMs / roi.costMs)} 倍" +
                                                "（${"%.1f".format(full.costMs)} → ${"%.1f".format(roi.costMs)} ms）"
                                        )
                                    }
                                }
                                appendLine()
                            }
                            appendLine("─────────────────────────────")
                            val fullTotal = probes.filterIndexed { i, _ -> i % 2 == 0 }.sumOf { it.costMs }
                            val roiTotal = probes.filterIndexed { i, _ -> i % 2 == 1 }.sumOf { it.costMs }
                            appendLine("全屏总耗时 ${"%.1f".format(fullTotal)} ms")
                            appendLine("带 ROI 总耗时 ${"%.1f".format(roiTotal)} ms")
                            appendLine()
                            appendLine("代价 ≈ 搜索区面积 × 模板面积。全屏 921600 px 是 71 万个候选位置，")
                            appendLine("每个位置算一次模板面积大小的乘加 —— 这就是慢的唯一原因。")
                        }
                    }
                } catch (t: Throwable) {
                    "ERROR: ${t.message}"
                }
            }
            tvResult.text = text
            tvResult.setTextColor(ContextCompat.getColor(host, R.color.ink_muted))
        }
    }

    private companion object {
        /** 识别自检用的模板：画面里很容易出现的特征。 */
        val PROBE_TEMPLATES = listOf(
            "Start_game/开始游戏.png",
            "Start_game/叉叉.png",
            "General/暂停.png"
        )

        /** 会话还没报尺寸时的兜底（虚拟屏固定 1280×720）。 */
        const val VD_WIDTH = 1280
        const val VD_HEIGHT = 720
    }
}
