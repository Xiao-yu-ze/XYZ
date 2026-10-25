package com.pvz2.auto.ui.page

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.pvz2.auto.R
import com.pvz2.auto.core.ZjsLog
import com.pvz2.auto.zjs.ZRunLogParser
import java.io.File

/**
 * 第 4 页：运行日志。
 *
 * ## 只显示"顺序 + 用时 + 状态"，点进去才看细节
 *
 * 用户的原话：看不到节点运行的顺序、状态，不知道该怎么调试。
 * 日志文件是流水账，几百行在手机上是滚不动的；而调脚本时真正要看的就三样：
 * 跑到第几条、每条多久、哪条没成。所以列表只放这三列，细节收进点击。
 *
 * 数据来源是 [ZjsLog] 每次运行写的那个文件，解析见 [ZRunLogParser]。
 */
class RunLogPage(
    private val host: AppCompatActivity,
    private val root: View
) {

    private lateinit var head: TextView
    private lateinit var path: TextView
    private lateinit var list: LinearLayout

    /** 当前看的是哪个文件；null = 最新一份。 */
    private var current: File? = null

    private val colorOk = Color.parseColor("#2E7D32")
    private val colorJump = Color.parseColor("#1565C0")
    private val colorSkip = Color.parseColor("#EF6C00")
    private val colorFail = Color.parseColor("#C62828")
    private val colorNone = Color.parseColor("#757575")

    fun bind() {
        head = root.findViewById(R.id.tvRunLogHead)
        path = root.findViewById(R.id.tvRunLogPath)
        list = root.findViewById(R.id.runLogList)

        root.findViewById<View>(R.id.btnRunLogRefresh).setOnClickListener {
            current = current?.takeIf { it.isFile } ?: newest()
            render()
        }
        root.findViewById<View>(R.id.btnRunLogPick).setOnClickListener { pickLog() }

        // 页面被销毁时把定时刷新停掉，否则一直空转
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                startAutoRefresh()
            }

            override fun onViewDetachedFromWindow(v: View) {
                stopAutoRefresh()
            }
        })
        if (root.isAttachedToWindow) startAutoRefresh()
        current = newest()
        render()
    }

    // ------------------------------------------------------------ 自动刷新

    private val tick = object : Runnable {
        override fun run() {
            // 只在运行中跟新：跑完就没必要一直读了
            if (com.pvz2.auto.core.ScriptRunState.state.value.running) {
                if (current == null || current?.name?.contains("-") == true) current = newest()
                render()
            }
            root.postDelayed(this, 1500)
        }
    }

    private fun startAutoRefresh() {
        root.removeCallbacks(tick)
        root.postDelayed(tick, 1500)
    }

    private fun stopAutoRefresh() {
        root.removeCallbacks(tick)
    }

    // ------------------------------------------------------------ 文件

    private fun allLogs(): List<File> =
        ZjsLog.runsDir().listFiles { f -> f.isFile && f.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    private fun newest(): File? = allLogs().firstOrNull()

    private fun pickLog() {
        val files = allLogs()
        if (files.isEmpty()) {
            head.text = "还没有运行日志。跑一次脚本，这里就会出现。"
            list.removeAllViews()
            return
        }
        val labels = files.map { f ->
            val t = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date(f.lastModified()))
            // 文件名: run-20261025-130839_塔香蕉_无尽.zjs.log
            val name = f.name.removePrefix("run-").removeSuffix(".log")
            "$t  $name  (${f.length() / 1024}KB)"
        }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(host)
            .setTitle("看哪一次运行")
            .setItems(labels) { d, i ->
                d.dismiss()
                current = files[i]
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------ 渲染

    private fun render() {
        val f = current ?: newest()
        if (f == null || !f.isFile) {
            head.text = "还没有运行日志。跑一次脚本，这里就会出现。"
            path.text = ZjsLog.runsDir().absolutePath
            list.removeAllViews()
            return
        }
        val text = runCatching { f.readText() }.getOrElse { "读不出日志：${it.message}" }
        val r = ZRunLogParser.parse(text)

        val ok = r.nodes.count { it.outcome == ZRunLogParser.Outcome.OK }
        val jumped = r.nodes.count { it.outcome == ZRunLogParser.Outcome.JUMPED }
        val bad = r.nodes.count {
            it.outcome == ZRunLogParser.Outcome.FAILED || it.outcome == ZRunLogParser.Outcome.SKIPPED
        }
        val total = if (r.nodes.isNotEmpty()) r.nodes.maxOf { it.total } else 0
        head.text = buildString {
            append(r.scriptName.ifBlank { f.name })
            append("  ·  ")
            append(r.startedAt.ifBlank { "?" })
            append("\n共 $total 条，日志里记录 ${r.nodes.size} 条：")
            append("成功 $ok")
            if (jumped > 0) append(" · 跳转 $jumped")
            if (bad > 0) append(" · 异常 $bad")
            if (com.pvz2.auto.core.ScriptRunState.state.value.running) append("  · 运行中…")
        }
        path.text = f.absolutePath

        list.removeAllViews()
        if (r.nodes.isEmpty()) {
            list.addView(hint("这份日志里还没有节点记录（刚开跑？点「刷新」）。"))
            return
        }
        for (n in r.nodes) list.addView(row(n))
    }

    private fun row(n: ZRunLogParser.NodeLog): View {
        val ctx = host
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
        }
        // 序号
        line.addView(TextView(ctx).apply {
            text = n.index.toString()
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#9E9E9E"))
            layoutParams = LinearLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        // 类型 · 名字
        line.addView(TextView(ctx).apply {
            text = "${n.typeName} · ${n.name}"
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        // 用时
        line.addView(TextView(ctx).apply {
            text = n.summary()
            textSize = 12f
            gravity = Gravity.END
            setTextColor(if (n.costMs == null) colorNone else Color.parseColor("#424242"))
            layoutParams = LinearLayout.LayoutParams(dp(74), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        // 状态
        line.addView(TextView(ctx).apply {
            text = n.outcome.label
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(
                when (n.outcome) {
                    ZRunLogParser.Outcome.OK -> colorOk
                    ZRunLogParser.Outcome.JUMPED -> colorJump
                    ZRunLogParser.Outcome.SKIPPED -> colorSkip
                    ZRunLogParser.Outcome.FAILED -> colorFail
                    ZRunLogParser.Outcome.UNFINISHED -> colorNone
                }
            )
            layoutParams = LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        // 分隔线（用背景框住，省一个 View）
        line.background = GradientDrawable().apply {
            setColor(Color.parseColor("#FAFAFA"))
            setStroke(dp(1), Color.parseColor("#EEEEEE"))
            cornerRadius = dp(6).toFloat()
        }
        line.setOnClickListener { showDetail(n) }
        return line
    }

    /** 点一条：显示这条的全部细节（识别到什么、点在哪、为什么跳过）。 */
    private fun showDetail(n: ZRunLogParser.NodeLog) {
        val body = buildString {
            append("[${n.index}/${n.total}] ${n.typeName} · ${n.name}")
            append("\n状态：${n.outcome.label}")
            append("　用时：${n.costMs?.let { "${it}ms" } ?: "未完成"}")
            n.recognizeMs?.let { append("　识别：${it}ms × ${n.recognizeCount ?: 0} 次") }
            append("\n")
            append("─".repeat(24))
            append("\n")
            if (n.lines.isEmpty()) {
                append("（这条没有更多记录）")
            } else {
                n.lines.forEach { append(it.trim()).append("\n") }
            }
        }
        val tv = TextView(host).apply {
            text = body
            textSize = 11f
            setTextIsSelectable(true)   // 允许长按复制，方便发给别人
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val sv = ScrollView(host).apply { addView(tv) }
        androidx.appcompat.app.AlertDialog.Builder(host)
            .setTitle("第 ${n.index} 条")
            .setView(sv)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun hint(t: String): View = TextView(host).apply {
        text = t
        textSize = 12f
        setTextColor(colorNone)
        setPadding(dp(4), dp(12), dp(4), dp(12))
    }

    private fun dp(v: Int): Int = (v * host.resources.displayMetrics.density).toInt()
}
