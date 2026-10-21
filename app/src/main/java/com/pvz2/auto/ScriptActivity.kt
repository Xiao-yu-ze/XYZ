package com.pvz2.auto

import android.os.Bundle
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
import com.pvz2.auto.core.ScriptRunState
import com.pvz2.auto.maa.PipelineRunner
import com.pvz2.auto.service.AutomationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 脚本运行页。
 *
 * ## 为什么单独一页
 *
 * 主界面要放虚拟屏预览，预览本身就该占掉大半屏。脚本运行的控件再塞进去，
 * 两边都挤 —— 所以按功能拆页：主界面管"屏"，这一页管"跑"。
 *
 * ## 这一页真正要解决的问题
 *
 * 脚本跑起来之后，用户能看到的只有游戏画面。而**画面不动**有两种含义，
 * 处理方式完全相反：
 *
 *  · 脚本卡死了 → 要停下来查
 *  · 脚本正常地等一个还没出现的画面 → 只需继续等
 *
 * 所以状态区不是装饰，它是唯一的判据：正在等哪个节点、跑了多少轮、
 * 每轮识别花多久、哪些节点命中过。有这些数字，"卡住"和"在等"一眼可分。
 */
class ScriptActivity : AppCompatActivity() {

    private lateinit var scriptList: LinearLayout
    private lateinit var etEntry: EditText
    private lateinit var swDryRun: SwitchMaterial
    private lateinit var tvMessage: TextView
    private lateinit var tvProgress: TextView
    private lateinit var tvHits: TextView
    private lateinit var btnRun: MaterialButton
    private lateinit var btnStop: MaterialButton

    /** 当前选中的脚本目录。 */
    private var selectedDir: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_script)

        scriptList = findViewById(R.id.scriptList)
        etEntry = findViewById(R.id.etEntry)
        swDryRun = findViewById(R.id.swDryRun)
        tvMessage = findViewById(R.id.tvScriptMessage)
        tvProgress = findViewById(R.id.tvScriptProgress)
        tvHits = findViewById(R.id.tvScriptHits)
        btnRun = findViewById(R.id.btnScriptRun)
        btnStop = findViewById(R.id.btnScriptStop)

        btnRun.setOnClickListener { startScript() }
        btnStop.setOnClickListener { AutomationService.scriptStop(this) }

        loadScriptList()
        observeStatus()
    }

    /**
     * 列出 assets 里的脚本。
     *
     * 读 assets 要碰 IO，放到后台线程；节点解析有一定开销（要读若干 JSON），
     * 在主线程做会卡住进入动画。
     */
    private fun loadScriptList() {
        lifecycleScope.launch {
            val scripts = withContext(Dispatchers.IO) {
                runCatching { PipelineRunner.listScripts(this@ScriptActivity) }
                    .getOrDefault(emptyList())
            }

            scriptList.removeAllViews()
            if (scripts.isEmpty()) {
                val tv = TextView(this@ScriptActivity)
                tv.text = getString(R.string.script_none)
                tv.setTextColor(ContextCompat.getColor(this@ScriptActivity, R.color.state_bad))
                scriptList.addView(tv)
                return@launch
            }

            for (info in scripts) {
                scriptList.addView(buildScriptRow(info))
            }
            // 默认选中第一个，并把它的入口节点填进去 —— 用户通常直接点运行。
            selectScript(scripts.first())
        }
    }

    private fun buildScriptRow(info: PipelineRunner.ScriptInfo): View {
        val row = TextView(this).apply {
            text = getString(R.string.script_row, info.dir, info.nodeCount)
            setTextAppearance(R.style.Text_Body)
            setPadding(0, dp(10), 0, dp(10))
            isClickable = true
            isFocusable = true
            // 用 tag 存目录名做精确匹配，不要拿渲染出来的文本去 startsWith ——
            // 目录名有前缀关系时（A 和 AB）会两行同时高亮，
            // 而且文案格式一改就失配。
            tag = info.dir
            setOnClickListener { selectScript(info) }
        }
        return row
    }

    private fun selectScript(info: PipelineRunner.ScriptInfo) {
        selectedDir = info.dir
        etEntry.setText(info.entry)
        // 选中态用颜色表达，不引入额外的选中背景资源。
        for (i in 0 until scriptList.childCount) {
            val child = scriptList.getChildAt(i) as? TextView ?: continue
            val selected = child.tag == info.dir
            child.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (selected) R.color.ink else R.color.ink_muted
                )
            )
        }
    }

    private fun startScript() {
        val dir = selectedDir
        if (dir.isNullOrBlank()) {
            tvMessage.text = getString(R.string.script_none)
            tvMessage.setTextColor(ContextCompat.getColor(this, R.color.state_bad))
            return
        }
        val entry = etEntry.text.toString().trim().takeIf { it.isNotEmpty() }
        AutomationService.scriptRun(this, dir, entry, swDryRun.isChecked)
    }

    /**
     * 订阅进程级运行状态。
     *
     * 用 [ScriptRunState] 而不是本地变量：服务随时可能重启，
     * 界面也可能被系统回收后重建，状态不能挂在 Activity 上。
     */
    private fun observeStatus() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ScriptRunState.state.collect { s ->
                    btnRun.isEnabled = !s.running
                    btnStop.isEnabled = s.running

                    tvMessage.text = s.message
                    tvMessage.setTextColor(
                        ContextCompat.getColor(
                            this@ScriptActivity,
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
                        getString(
                            R.string.script_progress,
                            s.round,
                            s.lastRoundMs
                        ) + "\n" + getString(R.string.script_waiting, s.currentNode)
                    }

                    // 命中统计按次数降序，只显示前 6 个 —— 多了会把页面撑长，
                    // 而用户真正关心的是"哪些节点在反复命中"。
                    tvHits.text = if (s.hits.isEmpty()) {
                        ""
                    } else {
                        s.hits.entries
                            .sortedByDescending { it.value }
                            .take(6)
                            .joinToString("\n") { "${it.value}×  ${it.key}" }
                    }
                }
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()


}
