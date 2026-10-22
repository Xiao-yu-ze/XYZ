package com.pvz2.auto.ui.page

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pvz2.auto.R
import com.pvz2.auto.zjs.Z
import com.pvz2.auto.zjs.ZActionDescriber
import com.pvz2.auto.zjs.ZScriptIO
import com.pvz2.auto.zjs.ZjsRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 自动精灵格式的动作浏览器。
 *
 * ## 和 [EditorPage] 的关系
 *
 * [EditorPage] 看的是 MaaFramework 格式（一个节点一个文件、靠 next 连边）。
 * 这里是自动精灵格式（一个脚本一串动作）。两套格式完全不通，
 * 混在一个列表里会让人分不清手上这个脚本到底是哪种。
 *
 * 所以这一页单独存在，由界面上的脚本来源决定进哪一个。
 *
 * ## 折叠 + 展开
 *
 * 真实脚本上百个动作。全部展开一屏放不下两个，想找某个动作得滚半天。
 * 先用「序号 + 标签」定位，需要看参数再点开。
 */
class ZjsEditorPage(
    private val host: AppCompatActivity,
    private val root: View
) {

    private val rvActions: RecyclerView = root.findViewById(R.id.rvZjsActions)
    private val tvSummary: TextView = root.findViewById(R.id.tvZjsSummary)

    private val adapter = ActionAdapter()

    /** 当前展开的动作下标。-1 = 全收起。 */
    private var expandedIndex = -1

    fun bind() {
        rvActions.layoutManager = LinearLayoutManager(host)
        rvActions.adapter = adapter
        rvActions.itemAnimator = null
        load()
    }

    private fun load() {
        host.lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) {
                runCatching { ZjsRunner.listScripts(host) }.getOrDefault(emptyList())
            }
            if (entries.isEmpty()) {
                tvSummary.text = "assets/zjs 下没有脚本"
                adapter.submit(null)
                return@launch
            }
            // 目前只展示第一个；多个脚本时再加下拉切换。
            val first = entries.first()
            val script = withContext(Dispatchers.IO) {
                runCatching { ZjsRunner.loadScript(host, first.fileName) }.getOrNull()
            }
            if (script == null) {
                tvSummary.text = "读不出 ${first.fileName}"
                adapter.submit(null)
                return@launch
            }
            showScript(first.fileName, script)
        }
    }

    private fun showScript(fileName: String, script: Z.Script) {
        val issues = ZScriptIO.validate(script)
        val size = script.actions.size
        tvSummary.text = buildString {
            append(fileName)
            append(" · ")
            append("$size 个动作")
            if (script.baseInfo.description != null) {
                append(" · ")
                append(script.baseInfo.description)
            }
            if (issues.isNotEmpty()) {
                append("\n⚠ ")
                append(issues.take(3).joinToString("；"))
                if (issues.size > 3) append("（还有 ${issues.size - 3} 条）")
            }
        }
        adapter.submit(script.actions)
    }

    // ------------------------------------------------------------ 适配器

    private inner class ActionAdapter : RecyclerView.Adapter<ActionAdapter.Holder>() {

        private var items: List<Z.Action> = emptyList()

        fun submit(actions: List<Z.Action>?) {
            items = actions.orEmpty()
            expandedIndex = -1
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_zjs_action, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(position, items[position], position == expandedIndex)
        }
        private inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            private val tvIndex: TextView = v.findViewById(R.id.tvActionIndex)
            private val tvLabel: TextView = v.findViewById(R.id.tvActionLabel)
            private val tvBadge: TextView = v.findViewById(R.id.tvActionBadge)
            private val llFields: LinearLayout = v.findViewById(R.id.llActionFields)

            fun bind(index: Int, action: Z.Action, expanded: Boolean) {
                tvIndex.text = (index + 1).toString()
                tvLabel.text = ZActionDescriber.title(action)

                // 角标用来标出"这条跑不了"，比藏在展开区里更显眼。
                tvBadge.text = when {
                    action.common.disabled -> "已禁用"
                    !action.type.executable -> "不支持"
                    action.common.vars.isNotEmpty() -> "含变量"
                    else -> ""
                }

                llFields.removeAllViews()
                llFields.visibility = if (expanded) View.VISIBLE else View.GONE

                if (expanded) {
                    ZActionDescriber.describe(action).forEach { row ->
                        llFields.addView(makeRow(row))
                    }
                }

                itemView.setOnClickListener {
                    val prev = expandedIndex
                    expandedIndex = if (prev == index) -1 else index
                    if (prev >= 0) notifyItemChanged(prev)
                    notifyItemChanged(index)
                }
            }

            /**
             * 一行字段。
             *
             * 用两个 TextView 而不是 RecyclerView：一个动作最多十几行，
             * 嵌一层 RecyclerView 得不偿失。
             */
            private fun makeRow(row: ZActionDescriber.Row): View {
                val ctx = host
                val line = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(0, dp(3), 0, dp(3))
                }
                val label = TextView(ctx).apply {
                    text = row.label
                    setTextAppearance(R.style.Text_Hint)
                    layoutParams = LinearLayout.LayoutParams(dp(96), LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                val value = TextView(ctx).apply {
                    text = row.value.ifEmpty { "—" }
                    setTextAppearance(R.style.Text_Hint)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    // 字段值可能很长（比如 base64），让它能换行而不是被截断
                    setSingleLine(false)
                }
                line.addView(label)
                line.addView(value)
                return line
            }

            private fun dp(v: Int): Int =
                (v * host.resources.displayMetrics.density).toInt()
        }
    }

}
