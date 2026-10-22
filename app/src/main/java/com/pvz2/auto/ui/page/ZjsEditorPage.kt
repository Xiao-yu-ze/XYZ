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
import com.pvz2.auto.zjs.ZFieldEditor
import com.pvz2.auto.zjs.ZScriptIO
import com.pvz2.auto.zjs.ZjsRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.firstOrNull
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

    /** 当前打开的脚本。编辑都改这一份，界面从它重绘。 */
    private var script: Z.Script? = null

    /** 当前脚本文件名，保存时用。 */
    private var fileName: String = ""

    /**
     * 某一行被点了「编辑」。
     *
     * 弹一个单行输入框，确认后按 [row] 的路径写回模型并重绘这一条。
     *
     * ## 为什么不做成内嵌输入框
     *
     * 一个动作十几行，每行一个 EditText 会带来十几套焦点/键盘/光标状态，
     * 而且展开收起时全要重建。弹窗一次只处理一个字段，简单且不会出错。
     */
    private fun onEditRequested(row: ZActionDescriber.Row, actionIndex: Int) {
        val cur = script ?: return
        val action = cur.actions.getOrNull(actionIndex) ?: return

        // 有限选项的字段直接列出来让用户选。
        //
        // ## 为什么不让用户手输
        //
        // 「快速重复」只能是否，「等待单位」只能是 ms/s/min/h。
        // 让用户打字的话，打错了（"是的"、"毫秒"、"1s"）程序只能拒收
        // 或者静默当默认值 —— 两种都不好。列出来点一下，不可能输错。
        if (row.kind == ZActionDescriber.Kind.ENUM && row.options.isNotEmpty()) {
            val current = row.options.indexOfFirst { it == row.value }.coerceAtLeast(0)
            androidx.appcompat.app.AlertDialog.Builder(host)
                .setTitle(row.label)
                .setSingleChoiceItems(row.options.toTypedArray(), current) { dlg, which ->
                    dlg.dismiss()
                    commitField(cur, action, actionIndex, row, row.options[which])
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        val input = android.widget.EditText(host).apply {
            setText(row.value)
            setSingleLine(false)
            setPadding(32, 24, 32, 24)
            // 数字类字段弹数字键盘
            inputType = if (row.kind == ZActionDescriber.Kind.NUMBER) {
                android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            } else {
                android.text.InputType.TYPE_CLASS_TEXT
            }
        }

        androidx.appcompat.app.AlertDialog.Builder(host)
            .setTitle(row.label)
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                commitField(cur, action, actionIndex, row, input.text?.toString().orEmpty())
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 把一个字段的新值写回模型并重绘这一条。
     *
     * 下拉选择和文本输入都走这里 —— 两条路径共用一份写回逻辑，
     * 免得将来只有一条被改对。
     */
    private fun commitField(
        cur: Z.Script,
        action: Z.Action,
        actionIndex: Int,
        row: ZActionDescriber.Row,
        newValue: String
    ) {
        val updated = ZFieldEditor.apply(action, row.field, newValue)
        if (updated == null) {
            // 值不合法（比如区域格式不对）。明确告诉用户，
            // 不要静默丢弃 —— 他会以为自己改成功了。
            android.widget.Toast.makeText(
                host, "「${row.label}」的值不合法，未保存", android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        val list = cur.actions.toMutableList()
        list[actionIndex] = updated
        script = cur.copy(actions = list)
        adapter.submit(script!!.actions)
        // 保持展开，用户接着改别的字段
        expandedIndex = actionIndex
        adapter.notifyItemChanged(actionIndex)
    }

    fun bind() {
        rvActions.layoutManager = LinearLayoutManager(host)
        rvActions.adapter = adapter
        rvActions.itemAnimator = null
        root.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnZjsSave)
            .setOnClickListener { saveTo(defaultSavePath()) }
        root.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnZjsSaveAs)
            .setOnClickListener { promptSaveAs() }
        load()
    }

    /**
     * 默认保存路径。
     *
     * 放在 App 自己的外部目录 —— 不需要任何存储权限，而且用户能用文件管理器
     * 找得到。写到 assets 里是不行的（只读），写到 /sdcard 根目录在
     * Android 11+ 要 MANAGE_EXTERNAL_STORAGE。
     */
    private fun defaultSavePath(): String {
        val dir = java.io.File(host.getExternalFilesDir(null), "zjs")
        dir.mkdirs()
        val base = fileName.substringBeforeLast('.').ifBlank { "script" }
        return java.io.File(dir, "$base.zjs").absolutePath
    }

    /** 另存为：弹输入框填文件名。 */
    private fun promptSaveAs() {
        val dir = java.io.File(host.getExternalFilesDir(null), "zjs")
        dir.mkdirs()
        val base = fileName.substringBeforeLast('.').ifBlank { "script" }

        val input = android.widget.EditText(host).apply {
            setText("${base}_已编辑.zjs")
            setSingleLine(true)
            setPadding(32, 24, 32, 24)
        }
        androidx.appcompat.app.AlertDialog.Builder(host)
            .setTitle("另存为")
            .setMessage(dir.absolutePath)
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text?.toString().orEmpty().trim()
                if (name.isBlank()) return@setPositiveButton
                saveTo(java.io.File(dir, name).absolutePath)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 保存，并把结果告诉用户。
     *
     * ## 为什么必须给反馈
     *
     * 保存是最怕"看起来成功了"的操作。写失败（路径不可写、磁盘满）时
     * 静默返回，用户会关掉 App 然后发现改动没了。
     */
    private fun saveTo(path: String) {
        val script = this.script ?: return
        val ok = ZScriptIO.save(script, path)
        if (ok) {
            android.widget.Toast.makeText(
                host, host.getString(R.string.zjs_saved, path), android.widget.Toast.LENGTH_LONG
            ).show()
        } else {
            android.widget.Toast.makeText(
                host, host.getString(R.string.zjs_save_failed, "写入失败，看日志"), android.widget.Toast.LENGTH_LONG
            ).show()
        }
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

    private fun showScript(name: String, script: Z.Script) {
        this.script = script
        this.fileName = name
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
                        llFields.addView(makeRow(row, index))
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
            private fun makeRow(row: ZActionDescriber.Row, actionIndex: Int): View {
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

                // 可编辑字段点一下弹输入框。
                //
                // 只读字段不挂点击 —— 挂了会让人以为能改，点了没反应比不能点更糟。
                if (row.kind != ZActionDescriber.Kind.READONLY && row.field.isNotEmpty()) {
                    line.setBackgroundResource(android.R.drawable.list_selector_background)
                    line.setOnClickListener { onEditRequested(row, actionIndex) }
                }

                // 坐标字段多一个取点按钮。
                //
                // 手输坐标是不现实的 —— 用户既不知道游戏里某按钮是第几像素，
                // 也没法从画面上读出来。自动精灵的做法也是让用户在画面上指。
                // 取 X 和 Y 是一次操作同时拿到的，所以 X 行放按钮，
                // Y 行不放 —— 两个按钮会让人以为要分别取。
                if (row.kind == ZActionDescriber.Kind.COORD && row.field.endsWith(".x")) {
                    val base = row.field.removeSuffix(".x")   // "位置" / "起点" / "终点"
                    val pick = android.widget.TextView(host).apply {
                        text = "⊕"
                        textSize = 15f
                        setPadding(dp(10), 0, dp(2), 0)
                        setTextColor(host.getColor(R.color.ink_faint))
                        contentDescription = "在画面上取 $base 坐标"
                    }
                    pick.setOnClickListener { onPickPoint(base, actionIndex) }
                    line.addView(pick)
                }

                return line
            }

            private fun dp(v: Int): Int =
                (v * host.resources.displayMetrics.density).toInt()
        }
    }

    /**
     * 在预览画面上取点，结果写回该位置的 X 和 Y。
     *
     * ## 为什么挂到预览上而不是弹窗里
     *
     * 预览就是虚拟屏的实时画面，本身就是"要点的东西"。让用户在弹窗里
     * 看着缩略图猜坐标是不可行的；在实时画面上拖准星才是自然操作。
     *
     * 这和 自动精灵 一致 —— 它也是让用户在"脚本运行区域"上完成选择。
     */
    private fun onPickPoint(prefix: String, actionIndex: Int) {
        val cur = script ?: return
        val action = cur.actions.getOrNull(actionIndex) ?: return

        // 初始值：当前位置，没有就交给准星走默认（画面正中）
        val pos = when (prefix) {
            "起点" -> action.startPos
            "终点" -> action.endPos
            else -> action.posData
        }
        val initX = pos?.x?.raw?.toIntOrNull()
        val initY = pos?.y?.raw?.toIntOrNull()
        val initial = if (initX != null && initY != null) {
            com.pvz2.auto.core.PreviewPicker.Point(initX, initY)
        } else null

        // 武装取点模式，等预览那边出结果
        host.lifecycleScope.launch {
            val deferred = host.lifecycleScope.async {
                com.pvz2.auto.core.PreviewPicker.result.firstOrNull {
                    it.mode == com.pvz2.auto.core.PreviewPicker.Mode.POINT
                }
            }
            com.pvz2.auto.core.PreviewPicker.arm(
                com.pvz2.auto.core.PreviewPicker.Mode.POINT,
                initial = initial
            )
            android.widget.Toast.makeText(
                host, "拖动准星到目标位置，然后点确定", android.widget.Toast.LENGTH_SHORT
            ).show()

            val r = deferred.await() ?: return@launch

            // 写回：百分比两位小数，与协议一致（FIELDS.md 的推荐单位）
            val px = "%.2f%%".format(100.0 * r.x / r.screenWidth)
            val py = "%.2f%%".format(100.0 * r.y / r.screenHeight)

            var updated = ZFieldEditor.apply(action, "$prefix.x", px) ?: action
            updated = ZFieldEditor.apply(updated, "$prefix.y", py) ?: updated

            val list = cur.actions.toMutableList()
            list[actionIndex] = updated
            script = cur.copy(actions = list)
            adapter.submit(script!!.actions)
            expandedIndex = actionIndex
            adapter.notifyItemChanged(actionIndex)
        }
    }

}
