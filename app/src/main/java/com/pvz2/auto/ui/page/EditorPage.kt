package com.pvz2.auto.ui.page

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pvz2.auto.core.PreviewPicker
import com.pvz2.auto.zjs.ZArea
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.pvz2.auto.R
import com.pvz2.auto.maa.NodeDef
import com.pvz2.auto.maa.NodeDescriber
import com.pvz2.auto.maa.PipelineRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 第 4 页：编辑。
 *
 * 展示脚本的节点结构：卡片列表，点一下展开看这个节点的全部参数。
 *
 * ## 为什么折叠
 *
 * 真实脚本有 101 个节点。全部展开的话一屏放不下两个节点，
 * 想找"哪个节点在等战斗界面"得滚半天。折叠起来先用「识别 · 动作」一行摘要定位，
 * 需要细看再展开 —— 这是 自动精灵 的做法，也是唯一能看上百个节点的排法。
 *
 * ## 为什么用 RecyclerView
 *
 * 101 个节点一次性 inflate 成 LinearLayout 的子 view，进出这一页会明显卡顿。
 * RecyclerView 只创建屏幕内的几个，滚动时才复用。
 */
class EditorPage(
    private val host: AppCompatActivity,
    private val root: View
) {

    private val spScript: Spinner = root.findViewById(R.id.spEditorScript)
    private val etFilter: EditText = root.findViewById(R.id.etNodeFilter)
    private val tvSummary: TextView = root.findViewById(R.id.tvEditorSummary)
    private val rvNodes: RecyclerView = root.findViewById(R.id.rvNodes)

    private val adapter = NodeAdapter()

    /** 当前脚本的全部节点（未过滤）。 */
    private var allNodes: Map<String, NodeDef> = emptyMap()

    /** 当前脚本目录名，用于排序显示。 */
    private var currentDir: String = ""

    fun bind() {
        setupPickers()
        rvNodes.layoutManager = LinearLayoutManager(host)
        rvNodes.adapter = adapter
        // 卡片自带下边距，去掉 RecyclerView 的默认动画能少一次闪烁。
        rvNodes.itemAnimator = null

        etFilter.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = applyFilter()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })

        loadScripts()
    }

    /**
     * 取点 / 框区域 / 截模板。
     *
     * 用户在预览画面上指，结果换算成百分比写回脚本 ——
     * 和 自动精灵 一致的交互（"请在脚本运行区域中完成选择"）。
     *
     * 坐标存百分比而不是像素，换分辨率不失效。
     */
    private fun setupPickers() {
        val tvResult = root.findViewById<TextView>(R.id.tvPickResult)

        fun arm(mode: PreviewPicker.Mode) {
            PreviewPicker.arm(mode)
            tvResult.text = PreviewPicker.hintText(mode)
        }

        root.findViewById<MaterialButton>(R.id.btnPickPoint).setOnClickListener {
            arm(PreviewPicker.Mode.POINT)
        }
        root.findViewById<MaterialButton>(R.id.btnPickRect).setOnClickListener {
            arm(PreviewPicker.Mode.RECT)
        }
        root.findViewById<MaterialButton>(R.id.btnPickTemplate).setOnClickListener {
            arm(PreviewPicker.Mode.TEMPLATE)
        }

        host.lifecycleScope.launch {
            host.repeatOnLifecycle(Lifecycle.State.STARTED) {
                PreviewPicker.result.collect { r ->
                    tvResult.text = describePick(r)
                }
            }
        }
    }

    /** 把取到的结果翻译成"脚本里会写成什么"。 */
    private fun describePick(r: PreviewPicker.Result): String {
        val area = ZArea.fromPxRect(r.x, r.y, r.w, r.h, r.screenWidth, r.screenHeight)
        return when (r.mode) {
            PreviewPicker.Mode.POINT -> {
                val pct = ZArea.fromPxRect(r.x, r.y, 0, 0, r.screenWidth, r.screenHeight)
                buildString {
                    appendLine("取到坐标 (${r.x}, ${r.y})，存入脚本：")
                    appendLine("  x = ${pct.left.raw}")
                    appendLine("  y = ${pct.top.raw}")
                    append("（百分比，换分辨率不会失效）")
                }
            }

            PreviewPicker.Mode.RECT -> buildString {
                appendLine("框到区域 ($(r.x), $(r.y)) ${r.w}×${r.h}，存入脚本：")
                appendLine("  limitArea = \"$area\"")
                append("（左 上 右 下，百分比）")
            }

            PreviewPicker.Mode.TEMPLATE -> buildString {
                val crop = r.crop
                appendLine("截到模板 ${crop?.width ?: 0}×${crop?.height ?: 0}，位置 ($(r.x), $(r.y))")
                appendLine("  imageData: PNG base64 内嵌进脚本")
                append("  limitArea = \"$area\"")
            }
        }
    }

    private fun loadScripts() {
        host.lifecycleScope.launch {
            val scripts = withContext(Dispatchers.IO) {
                runCatching { PipelineRunner.listScripts(host) }.getOrDefault(emptyList())
            }
            if (scripts.isEmpty()) {
                tvSummary.text = host.getString(R.string.script_none)
                return@launch
            }

            spScript.adapter = ArrayAdapter(
                host,
                android.R.layout.simple_spinner_dropdown_item,
                scripts.map { "${it.dir}（${it.nodeCount}）" }
            )
            spScript.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    loadNodes(scripts[pos].dir)
                }

                override fun onNothingSelected(p: AdapterView<*>?) = Unit
            }
        }
    }

    private fun loadNodes(dir: String) {
        host.lifecycleScope.launch {
            val nodes = withContext(Dispatchers.IO) {
                runCatching { PipelineRunner.loadNodes(host, dir) }.getOrDefault(emptyMap())
            }
            currentDir = dir
            allNodes = nodes
            applyFilter()
        }
    }

    private fun applyFilter() {
        val keyword = etFilter.text.toString().trim()
        val filtered = if (keyword.isEmpty()) {
            allNodes
        } else {
            allNodes.filterKeys { it.contains(keyword, ignoreCase = true) }
        }

        // 按名字排序，而不是按 JSON 里的顺序 —— 脚本里节点顺序是随手的，
        // 找节点时按名字排才可预期。入口名（不被引用的）单独标出来。
        val ordered = filtered.entries.sortedBy { it.key }.map { it.value }
        adapter.submit(ordered, keyword, allNodes.size)
        tvSummary.text = if (keyword.isEmpty()) {
            host.getString(R.string.editor_summary_all, allNodes.size)
        } else {
            host.getString(R.string.editor_summary_filtered, ordered.size, allNodes.size)
        }
    }

    private inner class NodeAdapter : RecyclerView.Adapter<NodeAdapter.Holder>() {

        private val items = ArrayList<NodeDef>()
        private val expanded = HashSet<String>()

        fun submit(nodes: List<NodeDef>, keyword: String, total: Int) {
            items.clear()
            items.addAll(nodes)
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(host).inflate(R.layout.item_node, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val node = items[position]
            holder.bind(node, expanded.contains(node.name))
        }

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            private val header: View = v.findViewById(R.id.nodeHeader)
            private val name: TextView = v.findViewById(R.id.tvNodeName)
            private val summary: TextView = v.findViewById(R.id.tvNodeSummary)
            private val chevron: TextView = v.findViewById(R.id.tvNodeChevron)
            private val body: LinearLayout = v.findViewById(R.id.nodeBody)

            fun bind(node: NodeDef, isExpanded: Boolean) {
                name.text = node.name
                summary.text = NodeDescriber.summary(node)
                chevron.text = host.getString(
                    if (isExpanded) R.string.chevron_expanded else R.string.chevron_collapsed
                )
                body.visibility = if (isExpanded) View.VISIBLE else View.GONE

                if (isExpanded) fillRows(node) else body.removeAllViews()

                header.setOnClickListener {
                    if (expanded.contains(node.name)) expanded.remove(node.name)
                    else expanded.add(node.name)
                    notifyItemChanged(bindingAdapterPosition)
                }
            }

            /** 参数行每次展开时重建 —— 折叠状态下没必要占内存。 */
            private fun fillRows(node: NodeDef) {
                body.removeAllViews()
                for (row in NodeDescriber.describe(node)) {
                    body.addView(buildRow(row))
                }
            }

            private fun buildRow(row: NodeDescriber.Row): View {
                val rowView = LinearLayout(host).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(3), 0, dp(3))
                }

                val label = TextView(host).apply {
                    text = row.label
                    setTextAppearance(R.style.Text_Hint)
                    setTextColor(ContextCompat.getColor(host, R.color.param_label))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = dp(12) }
                }

                val value = TextView(host).apply {
                    text = row.value
                    setTextAppearance(R.style.Text_Hint)
                    setTextColor(ContextCompat.getColor(host, R.color.ink_muted))
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { weight = 1f }
                }

                rowView.addView(label)
                rowView.addView(value)
                return rowView
            }

            private fun dp(v: Int): Int = (v * host.resources.displayMetrics.density).toInt()
        }
    }

    private fun dp(v: Int): Int = (v * host.resources.displayMetrics.density).toInt()
}
