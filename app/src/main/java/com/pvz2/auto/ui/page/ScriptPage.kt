package com.pvz2.auto.ui.page

import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.pvz2.auto.R
import com.pvz2.auto.core.ScriptRunState
import com.pvz2.auto.maa.PipelineRunner
import com.pvz2.auto.service.AutomationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 第 2 页：脚本。
 *
 * ## 为什么是"页面"而不是 Activity
 *
 * 预览必须始终可见，所以它固定在主界面上方；功能页在下方用 ViewPager2 切换。
 * 如果脚本是一个独立 Activity，翻到它的时候预览就看不见了 ——
 * 而脚本运行时恰恰最需要看画面。
 *
 * ## 这一页真正要解决的问题
 *
 * 脚本跑起来后用户只能看到游戏画面，而**画面不动**有两种含义，
 * 处理方式完全相反：脚本卡死了要停下查，正在等一个还没出现的画面只需继续等。
 * 所以状态区不是装饰：正在等哪个节点、第几轮、上轮耗时、哪些节点命中过 ——
 * 有这些数字才能区分"卡住"和"在等"。
 */
class ScriptPage(
    private val host: AppCompatActivity,
    private val root: View
) {

    private val scriptList: LinearLayout = root.findViewById(R.id.scriptList)
    private val etEntry: EditText = root.findViewById(R.id.etEntry)
    private val swDryRun: SwitchMaterial = root.findViewById(R.id.swDryRun)
    private val tvMessage: TextView = root.findViewById(R.id.tvScriptMessage)
    private val tvProgress: TextView = root.findViewById(R.id.tvScriptProgress)
    private val tvHits: TextView = root.findViewById(R.id.tvScriptHits)
    private val btnRun: MaterialButton = root.findViewById(R.id.btnScriptRun)
    private val btnStop: MaterialButton = root.findViewById(R.id.btnScriptStop)

    /** 当前选中的脚本目录。 */
    private var selectedDir: String? = null

    fun bind() {
        btnRun.setOnClickListener { startScript() }
        btnStop.setOnClickListener { AutomationService.scriptStop(host) }
        loadScriptList()
        observeStatus()
    }

    /** 列出 assets 里的脚本。读 assets 是 IO，解析 JSON 也有开销，放后台。 */
    private fun loadScriptList() {
        host.lifecycleScope.launch {
            val scripts = withContext(Dispatchers.IO) {
                runCatching { PipelineRunner.listScripts(host) }.getOrDefault(emptyList())
            }

            scriptList.removeAllViews()
            if (scripts.isEmpty()) {
                val tv = TextView(host).apply {
                    text = host.getString(R.string.script_none)
                    setTextColor(ContextCompat.getColor(host, R.color.state_bad))
                }
                scriptList.addView(tv)
                return@launch
            }

            for (info in scripts) scriptList.addView(buildRow(info))
            // 默认选中第一个并把入口填进去 —— 用户通常直接点运行。
            selectScript(scripts.first())
        }
    }

    private fun buildRow(info: PipelineRunner.ScriptInfo): View = TextView(host).apply {
        text = host.getString(R.string.script_row, info.dir, info.nodeCount)
        setTextAppearance(R.style.Text_Body)
        setPadding(0, dp(10), 0, dp(10))
        isClickable = true
        isFocusable = true
        // 用 tag 存目录名做精确匹配，不要拿渲染文本去 startsWith：
        // 目录名有前缀关系时（A 与 AB）会两行同时高亮，文案一改也会失配。
        tag = info.dir
        setOnClickListener { selectScript(info) }
    }

    private fun selectScript(info: PipelineRunner.ScriptInfo) {
        selectedDir = info.dir
        etEntry.setText(info.entry)
        for (i in 0 until scriptList.childCount) {
            val child = scriptList.getChildAt(i) as? TextView ?: continue
            child.setTextColor(
                ContextCompat.getColor(
                    host,
                    if (child.tag == info.dir) R.color.ink else R.color.ink_muted
                )
            )
        }
    }

    private fun startScript() {
        val dir = selectedDir
        if (dir.isNullOrBlank()) {
            tvMessage.text = host.getString(R.string.script_none)
            tvMessage.setTextColor(ContextCompat.getColor(host, R.color.state_bad))
            return
        }
        val entry = etEntry.text.toString().trim().takeIf { it.isNotEmpty() }
        AutomationService.scriptRun(host, dir, entry, swDryRun.isChecked)
    }

    /** 订阅进程级运行状态。服务可能重启、界面可能重建，状态不能挂在页面实例上。 */
    private fun observeStatus() {
        host.lifecycleScope.launch {
            host.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ScriptRunState.state.collect { s ->
                    btnRun.isEnabled = !s.running
                    btnStop.isEnabled = s.running

                    tvMessage.text = s.message
                    tvMessage.setTextColor(
                        ContextCompat.getColor(
                            host,
                            when {
                                !s.running -> R.color.ink_muted
                                s.message.startsWith("出错") -> R.color.state_bad
                                else -> R.color.ink
                            }
                        )
                    )

                    tvProgress.text = if (!s.running) {
                        ""
                    } else {
                        host.getString(R.string.script_progress, s.round, s.lastRoundMs) +
                            "\n" + host.getString(R.string.script_waiting, s.currentNode)
                    }

                    // 只显示命中次数最多的 6 个：多了会把页面撑长，
                    // 而用户真正关心的是"哪些节点在反复命中"。
                    tvHits.text = if (s.hits.isEmpty()) "" else {
                        s.hits.entries.sortedByDescending { it.value }.take(6)
                            .joinToString("\n") { "${it.value}×  ${it.key}" }
                    }
                }
            }
        }
    }

    private fun dp(v: Int): Int = (v * host.resources.displayMetrics.density).toInt()
}
