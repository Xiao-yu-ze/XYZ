package com.pvz2.auto

import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.pvz2.auto.service.RecognitionSelfTest
import com.pvz2.auto.service.VirtualDisplaySession
import com.pvz2.auto.shizuku.ShizukuUserServiceClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.core.Mat

/**
 * 诊断页：两项自检，各管一件事。
 *
 * ## 为什么要独立一页
 *
 * 这些不是日常操作，是"某个环节坏了，要确认到底哪一环"时才用。
 * 塞在主界面里会让主路径看不清 —— 而主界面要放虚拟屏预览，本来就占掉大半屏。
 *
 * ## 两项自检的分工
 *
 *  · **注入自检**：事件能不能进到虚拟屏。不经过识别、不经过脚本，
 *    直接用 AIDL 往那块屏发一次点击。它失败说明通道坏了，
 *    而不是识别错了 —— 这两者的排查方向完全相反。
 *  · **识别自检**：找到了没有、分数多少、搜索区多大、花了多久。
 *    它不点任何东西，只报数字。
 *
 * 两项共用一个结果区：一次只跑一项，分成两块只会让页面变长。
 */
class DiagnosticsActivity : AppCompatActivity() {

    private val TAG = "DiagnosticsActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)

        findViewById<MaterialButton>(R.id.btnTestTap).setOnClickListener {
            testVirtualDisplayTap()
        }
        findViewById<MaterialButton>(R.id.btnRecogTest).setOnClickListener {
            runRecognitionSelfTest()
        }
    }
    private fun mark(ok: Boolean): String = if (ok) "✓ 接受" else "✗ 被拒"

    /**
     * 识别自检：把虚拟屏当前的画面跑一遍模板匹配，报出**分数和命中位置**。
     *
     * ## 为什么要它
     *
     * 注入已验证能进虚拟屏。接下来要回答"识别准不准"，而这件事没法从日志推断：
     * 分数 0.65 是模板不对还是阈值定高了，只能看具体数字；
     * 命中了却点不中，是框算错了还是坐标没换算，只能看命中框。
     *
     * 这个自检**不点任何东西**，只报数字。
     */
    private fun runRecognitionSelfTest() {
        val resultView = findViewById<TextView>(R.id.tvTestTapResult)
        val bitmap = VirtualDisplaySession.previewFlow.value

        if (bitmap == null) {
            resultView.setTextColor(ContextCompat.getColor(this, R.color.state_bad))
            resultView.text = getString(R.string.recog_no_frame)
            return
        }

        resultView.setTextColor(ContextCompat.getColor(this, R.color.ink_muted))
        resultView.text = getString(R.string.recog_running)

        lifecycleScope.launch {
            val text = withContext(Dispatchers.Default) {
                try {
                    if (!org.opencv.android.OpenCVLoader.initLocal()) {
                        return@withContext "ERROR: OpenCV 加载失败"
                    }

                    // Bitmap -> Mat。**不 recycle**：这张 Bitmap 归 previewFlow 所有，
                    // 回收了 ImageView 会拿到一张废图。
                    val mat = Mat()
                    org.opencv.android.Utils.bitmapToMat(bitmap, mat)

                    val probes = try {
                        RecognitionSelfTest(applicationContext).run(
                            frame = mat,
                            templates = PROBE_TEMPLATES
                        )
                    } finally {
                        mat.release()
                    }

                    if (probes.isEmpty()) {
                        "没有任何结果 —— 帧为空或模板全部没加载上。"
                    } else {
                        buildString {
                            appendLine("画面 ${bitmap.width}×${bitmap.height}，每个模板测两种搜索区")
                            appendLine("─────────────────────────────")
                            // 按模板分组，全屏与 ROI 并排，方便直接对比。
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
                                        val ratio = full.costMs / roi.costMs
                                        appendLine(
                                            "     → ROI 快了 ${"%.1f".format(ratio)} 倍" +
                                                "（${"%.1f".format(full.costMs)} → ${"%.1f".format(roi.costMs)} ms）"
                                        )
                                    }
                                }
                                appendLine()
                            }
                            appendLine("─────────────────────────────")
                            val fullTotal = probes.filterIndexed { i, _ -> i % 2 == 0 }.sumOf { it.costMs }
                            val roiTotal = probes.filterIndexed { i, _ -> i % 2 == 1 }.sumOf { it.costMs }
                            appendLine("全屏总耗时 ${"%.1f".format(fullTotal)} ms / ${probes.size / 2} 个模板")
                            appendLine("带 ROI 总耗时 ${"%.1f".format(roiTotal)} ms / ${probes.size / 2} 个模板")
                            appendLine()
                            appendLine("代价 ≈ 搜索区面积 × 模板面积。全屏 921600 px 是 71 万个候选位置，")
                            appendLine("每个位置算一次模板面积大小的乘加 —— 这就是慢的唯一原因。")
                            appendLine("真实 pipeline 每个节点都有 roi，搜索区小一个数量级，耗时跟着降。")
                            appendLine()
                            appendLine("注意 ROI 那一行的分数只有在目标确实落在 ROI 内时才可比；")
                            appendLine("中央一半的 ROI 里没有的东西，分数自然低。")
                        }
                    }
                } catch (t: Throwable) {
                    "ERROR: ${t.message}"
                }
            }
            resultView.text = text
            resultView.setTextColor(ContextCompat.getColor(this@DiagnosticsActivity, R.color.ink_muted))
        }
    }

    private fun testVirtualDisplayTap() {
        val resultView = findViewById<TextView>(R.id.tvTestTapResult)
        val displayId = VirtualDisplaySession.sharedState.value.displayId

        if (displayId <= 0) {
            resultView.setTextColor(ContextCompat.getColor(this, R.color.state_bad))
            resultView.text = getString(R.string.test_tap_no_display)
            return
        }

        // 坐标从输入框读，空着就用屏幕正中。
        // 虚拟屏是 1280×720，中心 (640, 360)。
        val x = findViewById<EditText>(R.id.etTapX).text.toString().toIntOrNull()
            ?.coerceIn(0, VD_WIDTH - 1) ?: (VD_WIDTH / 2)
        val y = findViewById<EditText>(R.id.etTapY).text.toString().toIntOrNull()
            ?.coerceIn(0, VD_HEIGHT - 1) ?: (VD_HEIGHT / 2)

        resultView.setTextColor(ContextCompat.getColor(this, R.color.ink_muted))
        resultView.text = getString(R.string.test_tap_running, displayId)

        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                val client = ShizukuUserServiceClient(applicationContext)
                try {
                    client.bind()
                    val service = client.await()
                        ?: return@withContext "ERROR: Shizuku 服务未连接（${client.lastError ?: "超时"}）"

                    // 四条路径同时试。它们失败的**方式**不一样，正好能把原因分开：
                    //
                    //   A. injectInputEvent + setDisplayId(虚拟屏)  ← 线上用的这条
                    //   B. injectInputEvent + display 0             ← 对照组：注入本身通不通
                    //   C. `input -d <id> tap`                      ← shell 的官方路径
                    //   D. `input tap`                              ← 对照组
                    //
                    // 判读：
                    //   A✗ B✓ C✓ → 是 setDisplayId/副屏路由的问题
                    //   A✗ B✗ C✗ → 权限或 InputManager 层的问题
                    //   A✗ C✓    → 我们的注入实现有 bug，改用 shell 路径
                    val report = StringBuilder()
                    report.appendLine("display=$displayId  坐标 ($x, $y)")
                    report.appendLine()

                    val a = runCatching { service.tapOnDisplay(displayId, x, y, 80) }
                        .getOrDefault(false)
                    report.appendLine("A 直注 InputInjector → $displayId : ${mark(a)}")

                    val b = runCatching { service.tapOnDisplay(0, x, y, 80) }
                        .getOrDefault(false)
                    report.appendLine("B 直注 → display 0（对照）        : ${mark(b)}")

                    val cOut = runCatching {
                        service.exec("input -d $displayId tap $x $y")
                    }.getOrDefault("")
                    // `input` 失败只往 stderr 写、退出码仍是 0，只能看输出。
                    val c = cOut.isBlank()
                    report.appendLine("C shell input -d $displayId（对照）: ${mark(c)}")
                    if (!c) report.appendLine("   └ $cOut")

                    val dOut = runCatching { service.exec("input tap $x $y") }.getOrDefault("")
                    val d = dOut.isBlank()
                    report.appendLine("D shell input（display 0）        : ${mark(d)}")
                    if (!d) report.appendLine("   └ $dOut")

                    report.appendLine()
                    report.appendLine("判读：")
                    report.appendLine(
                        when {
                            a -> "✓ A 通 —— 直注已能被系统接受，可以用它做自动化的动作层。"
                            !a && c -> "A 不通但 C 通 → 直注实现仍有问题，继续用 shell。"
                            !a && !c -> "两条都不通，看下面的窗口分布和权限。"
                            else -> "状态异常。"
                        }
                    )

                    val perm = runCatching {
                        service.exec("dumpsys package com.android.shell | grep -i INJECT_EVENTS")
                    }.getOrDefault("")
                    report.appendLine()
                    report.appendLine("shell 的 INJECT_EVENTS：")
                    report.appendLine(if (perm.isBlank()) "  （查不到，可能没这个权限）" else "  ${perm.trim()}")

                    val win = runCatching { service.exec("dumpsys window windows | grep -E 'Window #|mDisplayId'") }
                        .getOrDefault("")
                    report.appendLine()
                    report.appendLine("窗口分布：")
                    report.appendLine(win.trim().take(600))

                    report.toString()
                } catch (t: Throwable) {
                    "ERROR: ${t.message}"
                } finally {
                    runCatching { client.unbind(remove = false) }
                }
            }

            resultView.text = text
            resultView.setTextColor(ContextCompat.getColor(this@DiagnosticsActivity, R.color.ink_muted))
        }
    }
    private companion object {
        /**
         * 识别自检用的模板。
         *
         * 挑的是"画面里很容易出现"的特征：开始游戏按钮、通用叉叉、暂停。
         * 它们的分数能直接说明识别链路（模板加载 → 缩放 → ROI → 匹配）通不通。
         */
        val PROBE_TEMPLATES = listOf(
            "Start_game/开始游戏.png",
            "Start_game/叉叉.png",
            "General/暂停.png"
        )

        /** 虚拟屏尺寸，仅用于坐标输入框的上界。 */
        const val VD_WIDTH = 1280
        const val VD_HEIGHT = 720
    }
}