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
import com.pvz2.auto.zjs.ZConditionEditor
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
    private var script: Z.Script?
        get() = ZjsEditState.script
        set(v) { ZjsEditState.script = v }

    /** 当前脚本文件名，保存时用。 */
    private var fileName: String
        get() = ZjsEditState.fileName
        set(v) { ZjsEditState.fileName = v }

    /**
     * 嵌套编辑的路径栈。
     *
     * ## 为什么用栈而不是树
     *
     * 条件和监听里装着完整的动作。一次只显示一层、退回去看上一层，
     * 比把所有层同时在屏幕上摊开容易读 —— 平板屏幕也不大，
     * 三层缩进后每行只剩几个字。
     *
     * 空栈 = 在编辑脚本的顶层动作列表。
     */
    private val navStack = ArrayList<Frame>()

    /** 栈帧：正在编辑哪个动作的哪个监听槽位。 */
    private data class Frame(val actionIndex: Int, val slot: Z.CallbackSlot)

    /** 当前层要显示的动作列表。 */
    private fun currentActions(): List<Z.Action> {
        val s = script ?: return emptyList()
        val f = navStack.lastOrNull() ?: return s.actions
        return s.actions.getOrNull(f.actionIndex)?.callbacks?.get(f.slot)
            ?.let { listOf(it) } ?: emptyList()
    }

    /** 把当前层的列表写回脚本。监听层要沿栈塞回去。 */
    private fun commitActions(newList: List<Z.Action>) {
        val s = script ?: return
        // 所有改动都从这里过，所以在这里标脏最省事 ——
        // 后面要提示"有未保存改动"时不至于漏掉某些编辑路径。
        ZjsEditState.dirty = true
        val f = navStack.lastOrNull()
        if (f == null) {
            script = s.copy(actions = newList)
            return
        }
        val owner = s.actions.getOrNull(f.actionIndex) ?: return
        val inner = newList.firstOrNull() ?: return
        val newCallbacks = LinkedHashMap(owner.callbacks)
        newCallbacks[f.slot] = inner
        val newActions = s.actions.toMutableList()
        newActions[f.actionIndex] = owner.copy(callbacks = newCallbacks)
        script = s.copy(actions = newActions)
    }

    /** 进入某个监听槽位。空槽位先放一个占位动作。 */
    private fun enterSlot(actionIndex: Int, slot: Z.CallbackSlot) {
        val cur = script ?: return
        val owner = currentActions().getOrNull(actionIndex) ?: return
        if (!owner.callbacks.containsKey(slot)) {
            val placeholder = Z.Action(type = Z.ActionType.CLICK)
            val newCb = LinkedHashMap(owner.callbacks)
            newCb[slot] = placeholder
            val list = currentActions().toMutableList()
            list[actionIndex] = owner.copy(callbacks = newCb)
            commitActions(list)
        }
        navStack.add(Frame(actionIndex, slot))
        expandedIndex = -1
        adapter.submit(currentActions())
    }

    /** 返回上一层。 */
    private fun popNav(): Boolean {
        if (navStack.isEmpty()) return false
        navStack.removeAt(navStack.size - 1)
        expandedIndex = -1
        adapter.submit(currentActions())
        return true
    }

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
        val action = currentActions().getOrNull(actionIndex) ?: return

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
        val list = currentActions().toMutableList()
        list[actionIndex] = updated
        commitActions(list)
        adapter.submit(currentActions())
        // 保持展开，用户接着改别的字段
        expandedIndex = actionIndex
        adapter.notifyItemChanged(actionIndex)
    }

    /**
     * 删除一个动作。
     *
     * 先确认再删 —— 删错了没有撤销，而脚本可能有上百条，
     * 用户很难记住自己刚才删的是哪一条。
     */
    private fun onDeleteRequested(index: Int) {
        val cur = script ?: return
        val action = currentActions().getOrNull(index) ?: return

        androidx.appcompat.app.AlertDialog.Builder(host)
            .setTitle("删除这一条？")
            .setMessage("第 ${index + 1} 个：${ZScriptIO.actionLabel(action)}")
            .setPositiveButton("删除") { _, _ ->
                val list = currentActions().toMutableList()
                list.removeAt(index)
                commitActions(list)
                adapter.submit(currentActions())
                expandedIndex = -1
                android.widget.Toast.makeText(
                    host, "已删除", android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    /**
     * 点了「条件」或「监听」那种可以进去的行。
     *
     * ## 条件只有一个，监听有七个
     *
     * 动作的 `condition` 是单个对象（FIELDS.md:280），所以点条件就直接
     * 编辑它；监听槽位有 7 个，得先让用户选进哪一个。
     */
    private fun onEnter(row: ZActionDescriber.Row, actionIndex: Int) {
        val cur = script ?: return
        val action = currentActions().getOrNull(actionIndex) ?: return

        when {
            // ---- 条件
            row.field == "condition" -> {
                val existing = action.common.condition
                if (existing != null) {
                    showConditionEditor(action, actionIndex, existing)
                } else {
                    // 还没有条件：先选类型
                    val labels = ZConditionEditor.TYPE_LABELS.map { it.second }.toTypedArray()
                    androidx.appcompat.app.AlertDialog.Builder(host)
                        .setTitle("添加什么条件")
                        .setItems(labels) { dlg, which ->
                            dlg.dismiss()
                            val type = ZConditionEditor.TYPE_LABELS[which].first
                            val fresh = ZConditionEditor.create(type)
                            val updated = action.copy(
                                common = action.common.copy(condition = fresh)
                            )
                            val list = currentActions().toMutableList()
                            list[actionIndex] = updated
                            commitActions(list)
                            adapter.submit(currentActions())
                            expandedIndex = actionIndex
                            adapter.notifyItemChanged(actionIndex)
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }

            // ---- 预览查找结果
            //
            // 把识别结果画到主界面的实时预览上，用户调阈值时能立刻看到变化。
            // 这里只负责"武装 + 切到预览页"，绘制在主界面那边。
            row.field.endsWith(".preview") -> {
                val prefix = row.field.removeSuffix(".preview")
                onPreviewResults(action, prefix)
            }

            // ---- 多指滑动的手指
            //
            // 手指多了以后要能逐根改起终点，所以列一个表：
            // 第一项是添加，中间每根手指一项，最后是删除。
            row.field == "fingers" -> {
                val n = action.fingers.size
                val items = ArrayList<String>()
                items.add("＋ 添加一根手指")
                for (i in 0 until n) items.add("手指${i + 1}  改起终点")
                if (n > 0) items.add("删除最后一根")

                androidx.appcompat.app.AlertDialog.Builder(host)
                    .setTitle("手指（$n 根）")
                    .setItems(items.toTypedArray()) { dlg, which ->
                        dlg.dismiss()
                        when {
                            which == 0 -> addFinger(action, actionIndex)
                            which == items.size - 1 && n > 0 ->
                                removeFinger(action, actionIndex)
                            else -> showFingerEditor(action, actionIndex, which - 1)
                        }
                    }
                    .setNegativeButton("关闭", null)
                    .show()
            }

            // ---- 监听
            row.field == "callbacks" -> {
                val slots = Z.CallbackSlot.entries
                val labels = slots.map {
                    val has = action.callbacks.containsKey(it)
                    "${it.label}${if (has) "（已设置）" else ""}"
                }.toTypedArray()
                androidx.appcompat.app.AlertDialog.Builder(host)
                    .setTitle("编辑哪个监听")
                    .setItems(labels) { dlg, which ->
                        dlg.dismiss()
                        enterSlot(actionIndex, slots[which])
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    /**
     * 编辑一个已存在的条件。
     *
     * 弹一个列表，把条件摊平成行 —— 和动作的字段行同一套结构，
     * 所以下拉、数字键盘这些行为完全一致，不用另写一套。
     */
    private fun showConditionEditor(
        action: Z.Action,
        actionIndex: Int,
        condition: Z.Condition
    ) {
        val rows = ZConditionEditor.describe(condition)
        val labels = rows.map { "${it.label}：${it.value.ifEmpty { "—" }}" }.toTypedArray()

        androidx.appcompat.app.AlertDialog.Builder(host)
            .setTitle("条件")
            .setItems(labels) { dlg, which ->
                dlg.dismiss()
                editConditionRow(action, actionIndex, condition, rows[which])
            }
            .setNeutralButton("删除条件") { _, _ ->
                val updated = action.copy(common = action.common.copy(condition = null))
                val list = currentActions().toMutableList()
                list[actionIndex] = updated
                commitActions(list)
                adapter.submit(currentActions())
                expandedIndex = actionIndex
                adapter.notifyItemChanged(actionIndex)
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 改条件里的一个字段，然后回到条件编辑列表。 */
    private fun editConditionRow(
        action: Z.Action,
        actionIndex: Int,
        condition: Z.Condition,
        row: ZActionDescriber.Row
    ) {
        // 换类型要单独处理 —— 位置对象和专属参数都得跟着换
        if (row.label == "条件类型") {
            val labels = ZConditionEditor.TYPE_LABELS.map { it.second }.toTypedArray()
            androidx.appcompat.app.AlertDialog.Builder(host)
                .setTitle("条件类型")
                .setItems(labels) { dlg, which ->
                    dlg.dismiss()
                    val type = ZConditionEditor.TYPE_LABELS[which].first
                    applyCondition(action, actionIndex, ZConditionEditor.changeType(condition, type))
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        val commit: (String) -> Unit = { value ->
            ZConditionEditor.apply(condition, row.field, value)?.let {
                applyCondition(action, actionIndex, it)
            }
        }

        if (row.kind == ZActionDescriber.Kind.ENUM && row.options.isNotEmpty()) {
            val cur = row.options.indexOfFirst { it == row.value }.coerceAtLeast(0)
            androidx.appcompat.app.AlertDialog.Builder(host)
                .setTitle(row.label)
                .setSingleChoiceItems(row.options.toTypedArray(), cur) { dlg, which ->
                    dlg.dismiss()
                    commit(row.options[which])
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        val input = android.widget.EditText(host).apply {
            setText(row.value)
            setSingleLine(false)
            setPadding(32, 24, 32, 24)
            inputType = if (row.kind == ZActionDescriber.Kind.NUMBER) {
                android.text.InputType.TYPE_CLASS_NUMBER
            } else {
                android.text.InputType.TYPE_CLASS_TEXT
            }
        }
        androidx.appcompat.app.AlertDialog.Builder(host)
            .setTitle(row.label)
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                commit(input.text?.toString().orEmpty())
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun applyCondition(action: Z.Action, actionIndex: Int, condition: Z.Condition) {
        val updated = action.copy(common = action.common.copy(condition = condition))
        val list = currentActions().toMutableList()
        list[actionIndex] = updated
        commitActions(list)
        adapter.submit(currentActions())
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
        root.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnZjsAddAction)
            .setOnClickListener { promptAddAction() }
        root.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnZjsRun)
            .setOnClickListener { runCurrent() }
        root.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnZjsStop)
            .setOnClickListener {
                com.pvz2.auto.service.AutomationService.zjsStop(host)
            }
        load()
    }

    /**
     * 运行当前脚本。
     *
     * ## 先检查再跑
     *
     * [ZScriptIO.validate] 报的问题不阻止运行 —— 有些是"暂不支持"这类
     * 提示，用户可能就是只跑其中几条。但**必须让他看见**再决定。
     *
     * ## 保存到文件再跑
     *
     * 执行器读的是文件，不是内存里的对象。内存里改了不保存的话，
     * 跑的还是改之前的版本 —— 这种"我明明改了"的困惑最难查。
     * 所以先写盘再通知服务运行。
     */
    private fun runCurrent() {
        val script = this.script ?: return
        if (script.actions.isEmpty()) {
            android.widget.Toast.makeText(host, "脚本里没有动作", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val path = defaultSavePath()
        android.util.Log.i("ZjsEditor", "即将保存到 $path")
        if (!ZScriptIO.save(script, path)) {
            android.widget.Toast.makeText(
                host, "保存失败，无法运行", android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }

        val issues = ZScriptIO.validate(script)
        android.util.Log.i("ZjsEditor", "validate -> ${issues.size}")
        if (issues.isNotEmpty()) android.util.Log.i("ZjsEditor", "问题：${issues}")
        if (issues.isEmpty()) {
            startRun(path)
            return
        }

        androidx.appcompat.app.AlertDialog.Builder(host)
            .setTitle("有 ${issues.size} 个问题")
            .setMessage(issues.take(8).joinToString("\n") +
                if (issues.size > 8) "\n…" else "")
            .setPositiveButton("仍然运行") { _, _ -> startRun(path) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startRun(path: String) {
        android.util.Log.i("ZjsEditor", "startRun path=$path")
        try {
            com.pvz2.auto.service.AutomationService.zjsRun(host, path)
        } catch (t: Throwable) {
            // 前台服务启动失败会抛 SecurityException / ForegroundServiceStartNotAllowed
            // 之类。静默吞掉的话用户点了没反应、日志里也没有 —— 最难查的一类。
            android.util.Log.e("ZjsEditor", "zjsRun 失败", t)
            android.widget.Toast.makeText(
                host, "启动失败：${t.message}", android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }
        android.widget.Toast.makeText(
            host, "已开始运行", android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * 添加一个动作。
     *
     * 列表按分组排：先能跑的，再暂时不能跑的。
     * 用户在安卓上看到一堆点了没反应的类型会以为程序坏了，
     * 所以把「安卓上暂不支持」的排在后面并标出来。
     */
    private fun promptAddAction() {
        val cur = script ?: return

        // 可执行的排前面，同组内保持协议顺序
        val sorted = Z.ActionType.entries.sortedBy { if (it.executable) 0 else 1 }
        val labels = sorted.map {
            val base = it.controlRunType?.let { c -> "${it.wire}·$c" } ?: it.wire
            if (it.executable) base else "$base（暂不支持）"
        }.toTypedArray()

        androidx.appcompat.app.AlertDialog.Builder(host)
            .setTitle("添加什么动作")
            .setItems(labels) { dlg, which ->
                dlg.dismiss()
                addAction(sorted[which])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun addAction(type: Z.ActionType) {
        val cur = script ?: return

        // 位置类动作要带一个默认位置，否则展开后什么都没有、校验也报错。
        // 坐标给画面正中 —— 用户接下来会去取点，起点在哪里不重要，
        // 重要的是别是空值。
        val st = com.pvz2.auto.core.PreviewPicker.crosshair.value
        val cx = st?.x ?: 640
        val cy = st?.y ?: 360
        val pos = Z.PosData(
            type = ZFieldEditor.expectedPosType(type) ?: Z.PosType.LOCATION,
            x = com.pvz2.auto.zjs.ZValue.parse("$cx"),
            y = com.pvz2.auto.zjs.ZValue.parse("$cy")
        )

        val newAction = Z.Action(
            type = type,
            posData = if (ZFieldEditor.expectedPosType(type) != null) pos else null,
            startPos = if (type == Z.ActionType.SWIPE) pos else null,
            endPos = if (type == Z.ActionType.SWIPE) pos else null
        )

        // 加在当前展开的那一条后面。全收起时加到末尾 ——
        // 用户刚看完某条就想在它后面加一条，比滚到底部合理。
        val at = if (expandedIndex >= 0) expandedIndex + 1 else currentActions().size
        val list = currentActions().toMutableList()
        list.add(at, newAction)
        commitActions(list)
        adapter.submit(currentActions())
        expandedIndex = at
        adapter.notifyItemChanged(at)
        android.widget.Toast.makeText(
            host, "已添加「${type.wire}」", android.widget.Toast.LENGTH_SHORT
        ).show()
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
        // 内存里已有脚本就直接渲染。
        //
        // 翻页（ViewPager2）会销毁并重建这一页，而下面那条路径是从
        // assets 重读文件 —— 那会把用户没保存的改动（刚截的模板、
        // 刚调的阈值）全部冲掉，界面上却看不出任何异常。
        // 实测就是"点预览看画面再翻回来，改动没了"。
        ZjsEditState.script?.let { existing ->
            showScript(ZjsEditState.fileName, existing)
            return
        }
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

                // 长按删除。不用短按 —— 短按是展开/收起，很容易误触，
                // 而删除是不可撤销的。
                itemView.setOnLongClickListener {
                    onDeleteRequested(index)
                    true
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
                    line.setOnClickListener {
                        // ENTER 是"点进去看另一层"，不是改标量 ——
                        // 弹输入框只能改到摘要字符串，里面那层还是原样。
                        if (row.kind == ZActionDescriber.Kind.ENTER) {
                            onEnter(row, actionIndex)
                        } else {
                            onEditRequested(row, actionIndex)
                        }
                    }
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

                // 识别范围（ROI）：在画面上框一块。
                //
                // 和取点同一套交互 —— 用户已经知道"点 ⊕ 然后在画面上操作"，
                // 范围只是把"点一下"换成"拖一个框"。另起一套手势只会增加学习成本。
                if (row.kind == ZActionDescriber.Kind.AREA && row.field.isNotEmpty()) {
                    val pick = android.widget.TextView(host).apply {
                        text = "⊕"
                        textSize = 15f
                        setPadding(dp(10), 0, dp(2), 0)
                        setTextColor(host.getColor(R.color.ink_faint))
                        contentDescription = "在画面上框选识别范围"
                    }
                    pick.setOnClickListener { onPickRect(row.field, actionIndex) }
                    line.addView(pick)
                }

                // 模板图：在画面上框一块并直接裁下来。
                //
                // 这是模板匹配唯一的正经来源 —— 让用户从相册选一张图，
                // 尺寸和缩放都对不上，匹配阈值再调也没用。
                if (row.kind == ZActionDescriber.Kind.IMAGE && row.field.isNotEmpty()) {
                    val pick = android.widget.TextView(host).apply {
                        text = "✂"
                        textSize = 15f
                        setPadding(dp(10), 0, dp(2), 0)
                        setTextColor(host.getColor(R.color.ink_faint))
                        contentDescription = "在画面上截取模板"
                    }
                    pick.setOnClickListener { onPickTemplate(row.field, actionIndex) }
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
    // ------------------------------------------------------------ 多指滑动的手指

    /**
     * 给多指滑动加一根手指。
     *
     * 新手指横向错开（640、760、880…）—— 两根手指叠在同一个点上等于单指，
     * 用户还得自己拖开。上下留出距离，加进来就是一条像样的"几指下滑"。
     */
    /**
     * 在实时画面上预览识别结果。
     *
     * ## 为什么要切回第 1 页
     *
     * 预览画面在第 1 页。用户点完这个按钮还停在第 3 页，就永远看不到
     * 结果 —— 那比不给这个按钮更糟：他以为坏了。
     *
     * 这和 自动精灵 一致：它也是让你"在运行区域上"选择/预览，
     * 用的是一个专门的实时画面，而不是脚本编辑列表。
     */
    private fun onPreviewResults(action: Z.Action, prefix: String) {
        val pos = when (prefix) {
            "起点" -> action.startPos
            "终点" -> action.endPos
            else -> action.posData
        }
        if (pos == null) {
            android.widget.Toast.makeText(host, "这个位置还没设置", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val st = com.pvz2.auto.service.VirtualDisplaySession.sharedState.value
        if (st.frameWidth <= 0) {
            android.widget.Toast.makeText(
                host, "虚拟屏没在跑，先启动虚拟屏", android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }

        val screen = com.pvz2.auto.zjs.ZPosResolver.Screen(
            st.frameWidth, st.frameHeight, host.resources.displayMetrics.density
        )

        // 匹配器用真实的：预览必须和运行时的判定一致，否则调好的阈值跑起来又是另一个结果
        val matcher = com.pvz2.auto.zjs.ZRealMatcher(
            com.pvz2.auto.core.TemplateMatcher().also { it.load(host) },
            frame = { com.pvz2.auto.service.VirtualDisplaySession.snapshotForMatch() }
        )
        matcher.cacheDirProvider = { java.io.File(host.cacheDir, "zjs_templates").absolutePath }

        com.pvz2.auto.core.PreviewResults.start(
            scope = host.lifecycleScope,
            pos = pos,
            screen = screen,
            matcher = matcher,
            frame = { com.pvz2.auto.service.VirtualDisplaySession.snapshotForMatch() }
        )

        // 切回第 1 页才看得见画面
        (host as? com.pvz2.auto.MainActivity)?.gotoPage(0)
        android.widget.Toast.makeText(
            host, "已开始预览，改完记得回来", android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private fun addFinger(action: Z.Action, actionIndex: Int) {
        val x = 640 + action.fingers.size * 120
        val start = Z.PosData(
            type = Z.PosType.LOCATION,
            x = com.pvz2.auto.zjs.ZValue.parse("$x"),
            y = com.pvz2.auto.zjs.ZValue.parse("460")
        )
        val end = Z.PosData(
            type = Z.PosType.LOCATION,
            x = com.pvz2.auto.zjs.ZValue.parse("$x"),
            y = com.pvz2.auto.zjs.ZValue.parse("260")
        )
        applyFingers(action, actionIndex, action.fingers + Z.Action.Finger(start, end))
    }

    private fun removeFinger(action: Z.Action, actionIndex: Int) {
        if (action.fingers.isEmpty()) return
        applyFingers(action, actionIndex, action.fingers.dropLast(1))
    }

    private fun applyFingers(
        action: Z.Action,
        actionIndex: Int,
        fingers: List<Z.Action.Finger>
    ) {
        val list = currentActions().toMutableList()
        list[actionIndex] = action.copy(fingers = fingers)
        commitActions(list)
        adapter.submit(currentActions())
        expandedIndex = actionIndex
        adapter.notifyItemChanged(actionIndex)
    }

    /**
     * 改某一根手指的起点和终点。
     *
     * 列一行一项，点哪项改哪项 —— 和动作字段同一套交互。
     * 坐标既能手输（`50%` / `200dp` / `640`），也能走取点 ——
     * 手输纯像素在游戏画面上几乎不可能对准。
     */
    private fun showFingerEditor(action: Z.Action, actionIndex: Int, fingerIndex: Int) {
        val finger = action.fingers.getOrNull(fingerIndex) ?: return
        val s = finger.startPos
        val e = finger.endPos

        val items = arrayOf(
            "起点 X：${s?.x?.raw ?: "未设置"}",
            "起点 Y：${s?.y?.raw ?: "未设置"}",
            "终点 X：${e?.x?.raw ?: "未设置"}",
            "终点 Y：${e?.y?.raw ?: "未设置"}",
            "在画面上取起点",
            "在画面上取终点"
        )

        androidx.appcompat.app.AlertDialog.Builder(host)
            .setTitle("手指${fingerIndex + 1}")
            .setItems(items) { dlg, which ->
                dlg.dismiss()
                when (which) {
                    4 -> onPickFingerPoint(action, actionIndex, fingerIndex, isStart = true)
                    5 -> onPickFingerPoint(action, actionIndex, fingerIndex, isStart = false)
                    else -> {
                        val cur = when (which) {
                            0 -> s?.x?.raw.orEmpty()
                            1 -> s?.y?.raw.orEmpty()
                            2 -> e?.x?.raw.orEmpty()
                            else -> e?.y?.raw.orEmpty()
                        }
                        promptFingerValue(action, actionIndex, fingerIndex, which, cur)
                    }
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 手输一个坐标分量。 */
    private fun promptFingerValue(
        action: Z.Action,
        actionIndex: Int,
        fingerIndex: Int,
        which: Int,
        current: String
    ) {
        val input = android.widget.EditText(host).apply {
            setText(current)
            setSingleLine(true)
            setPadding(32, 24, 32, 24)
        }
        val label = arrayOf("起点 X", "起点 Y", "终点 X", "终点 Y")[which]

        androidx.appcompat.app.AlertDialog.Builder(host)
            .setTitle(label)
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                setFingerComponent(
                    action, actionIndex, fingerIndex, which,
                    com.pvz2.auto.zjs.ZValue.parse(input.text?.toString())
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 把某个分量写进第 [fingerIndex] 根手指。 */
    private fun setFingerComponent(
        action: Z.Action,
        actionIndex: Int,
        fingerIndex: Int,
        which: Int,
        value: com.pvz2.auto.zjs.ZValue
    ) {
        val fingers = action.fingers.toMutableList()
        val f = fingers.getOrNull(fingerIndex) ?: return
        val s = f.startPos ?: Z.PosData(type = Z.PosType.LOCATION)
        val e = f.endPos ?: Z.PosData(type = Z.PosType.LOCATION)

        fingers[fingerIndex] = when (which) {
            0 -> f.copy(startPos = s.copy(x = value))
            1 -> f.copy(startPos = s.copy(y = value))
            2 -> f.copy(endPos = e.copy(x = value))
            else -> f.copy(endPos = e.copy(y = value))
        }
        applyFingers(action, actionIndex, fingers)
    }

    /**
     * 在预览上给某根手指取点。
     *
     * 复用同一套准星，只是写回目标换成 fingers —— 用户不该因为改的是
     * "第二根手指"就换一种操作方式。一次取点同时写 X 和 Y。
     */
    private fun onPickFingerPoint(
        action: Z.Action,
        actionIndex: Int,
        fingerIndex: Int,
        isStart: Boolean
    ) {
        val f = action.fingers.getOrNull(fingerIndex) ?: return
        val pos = if (isStart) f.startPos else f.endPos
        val initX = pos?.x?.raw?.toIntOrNull()
        val initY = pos?.y?.raw?.toIntOrNull()
        val initial = if (initX != null && initY != null) {
            com.pvz2.auto.core.PreviewPicker.Point(initX, initY)
        } else null

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
                host, "按住画面拖动准星，位置对了点确定", android.widget.Toast.LENGTH_SHORT
            ).show()

            val r = deferred.await() ?: return@launch
            val px = "%.2f%%".format(100.0 * r.x / r.screenWidth)
            val py = "%.2f%%".format(100.0 * r.y / r.screenHeight)

            val fingers = action.fingers.toMutableList()
            val cur = fingers.getOrNull(fingerIndex) ?: return@launch
            val target = if (isStart) cur.startPos else cur.endPos
            val newPos = (target ?: Z.PosData(type = Z.PosType.LOCATION)).copy(
                x = com.pvz2.auto.zjs.ZValue.parse(px),
                y = com.pvz2.auto.zjs.ZValue.parse(py)
            )
            fingers[fingerIndex] = if (isStart) cur.copy(startPos = newPos)
            else cur.copy(endPos = newPos)
            applyFingers(action, actionIndex, fingers)
        }
    }

    /**
     * 在预览上框选识别范围（ROI），写回 `limitArea`。
     *
     * ## 为什么框选而不用四个数字框
     *
     * 自动精灵 两种都有：四个数字输入框（左/右/上/下）+ 画面上拖框
     * （见反编译的 `renderer-xm` 里 `defaultArea` 那个组件）。
     *
     * 但用户在实时画面上根本判断不出"左边该填 320 还是 340"。
     * 拖一个框是零成本的，数字框留着手调（界面上那行本身可点编辑）。
     *
     * ## 存百分比
     *
     * `"25.00% 25.00% 75.00% 75.00%"`，换分辨率不会失效。
     * 存像素的话在别的设备上整片范围就偏了。
     */
    private fun onPickRect(field: String, actionIndex: Int) {
        val action = currentActions().getOrNull(actionIndex) ?: return
        val pos = when {
            field.startsWith("起点") -> action.startPos
            field.startsWith("终点") -> action.endPos
            else -> action.posData
        }

        host.lifecycleScope.launch {
            val st = com.pvz2.auto.service.VirtualDisplaySession.sharedState.value
            val iw = st.frameWidth.takeIf { it > 0 } ?: 1280
            val ih = st.frameHeight.takeIf { it > 0 } ?: 720

            val deferred = host.lifecycleScope.async {
                com.pvz2.auto.core.PreviewPicker.result.firstOrNull {
                    it.mode == com.pvz2.auto.core.PreviewPicker.Mode.RECT
                }
            }
            com.pvz2.auto.core.PreviewPicker.arm(
                com.pvz2.auto.core.PreviewPicker.Mode.RECT,
                screenWidth = iw, screenHeight = ih
            )
            android.widget.Toast.makeText(
                host, "请在画面上拖出识别范围", android.widget.Toast.LENGTH_SHORT
            ).show()

            val r = deferred.await() ?: return@launch
            if (r.w <= 0 || r.h <= 0) {
                android.widget.Toast.makeText(
                    host, "框选范围太小，没有写回", android.widget.Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            val l = "%.2f%%".format(100.0 * r.x / r.screenWidth)
            val t = "%.2f%%".format(100.0 * r.y / r.screenHeight)
            val rr = "%.2f%%".format(100.0 * (r.x + r.w) / r.screenWidth)
            val bb = "%.2f%%".format(100.0 * (r.y + r.h) / r.screenHeight)

            val updated = com.pvz2.auto.zjs.ZFieldEditor
                .apply(action, field, "$l $t $rr $bb") ?: action
            val list = currentActions().toMutableList()
            list[actionIndex] = updated
            commitActions(list)
            adapter.submit(currentActions())
            expandedIndex = actionIndex
            adapter.notifyItemChanged(actionIndex)
        }
    }

    /**
     * 在预览上截取模板图，写回 `imageData`。
     *
     * ## 必须从实时画面裁
     *
     * 模板要和运行时看到的东西**逐像素一致**才有意义。让用户从相册
     * 选一张图，尺寸、缩放、抗锯齿全都对不上，阈值调到 50% 都没用。
     *
     * ## 六个字段一起写
     *
     * 协议里 `imageData` 除了 base64 还有 imageWidth/Height/Left/Top 和
     * screenWidth/Height —— 它们用来在别的分辨率下按比例还原位置。
     * 只写 base64 的话，自动精灵打开这张图位置是错的。
     */
    private fun onPickTemplate(field: String, actionIndex: Int) {
        val action = currentActions().getOrNull(actionIndex) ?: return

        host.lifecycleScope.launch {
            val st = com.pvz2.auto.service.VirtualDisplaySession.sharedState.value
            val iw = st.frameWidth.takeIf { it > 0 } ?: 1280
            val ih = st.frameHeight.takeIf { it > 0 } ?: 720

            val deferred = host.lifecycleScope.async {
                com.pvz2.auto.core.PreviewPicker.result.firstOrNull {
                    it.mode == com.pvz2.auto.core.PreviewPicker.Mode.TEMPLATE
                }
            }
            com.pvz2.auto.core.PreviewPicker.arm(
                com.pvz2.auto.core.PreviewPicker.Mode.TEMPLATE,
                screenWidth = iw, screenHeight = ih
            )
            android.widget.Toast.makeText(
                host, "请在画面上框住要识别的图案", android.widget.Toast.LENGTH_SHORT
            ).show()

            val r = deferred.await() ?: return@launch
            val crop = r.crop
            if (crop == null || crop.width <= 0 || crop.height <= 0) {
                android.widget.Toast.makeText(
                    host, "没截到图（虚拟屏还没画面？）", android.widget.Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            // 压成 PNG 存 base64。协议里 data 就是去掉前缀的 base64。
            val bytes = java.io.ByteArrayOutputStream()
            crop.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bytes)
            val b64 = android.util.Base64.encodeToString(
                bytes.toByteArray(), android.util.Base64.NO_WRAP
            )

            val img = com.pvz2.auto.zjs.Z.ImageData(
                data = b64,
                width = crop.width,
                height = crop.height,
                left = r.x,
                top = r.y,
                screenWidth = r.screenWidth,
                screenHeight = r.screenHeight
            )

            val updated = com.pvz2.auto.zjs.ZFieldEditor
                .applyImage(action, field, img) ?: action
            val list = currentActions().toMutableList()
            list[actionIndex] = updated
            commitActions(list)
            adapter.submit(currentActions())
            expandedIndex = actionIndex
            adapter.notifyItemChanged(actionIndex)

            android.widget.Toast.makeText(
                host, "已截取 ${crop.width}×${crop.height} 的模板",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun onPickPoint(prefix: String, actionIndex: Int) {
        val cur = script ?: return
        val action = currentActions().getOrNull(actionIndex) ?: return

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
                host, "按住画面拖动准星，位置对了点确定", android.widget.Toast.LENGTH_SHORT
            ).show()

            val r = deferred.await() ?: return@launch

            // 写回：百分比两位小数，与协议一致（FIELDS.md 的推荐单位）
            val px = "%.2f%%".format(100.0 * r.x / r.screenWidth)
            val py = "%.2f%%".format(100.0 * r.y / r.screenHeight)

            var updated = ZFieldEditor.apply(action, "$prefix.x", px) ?: action
            updated = ZFieldEditor.apply(updated, "$prefix.y", py) ?: updated

            val list = currentActions().toMutableList()
            list[actionIndex] = updated
            commitActions(list)
            adapter.submit(currentActions())
            expandedIndex = actionIndex
            adapter.notifyItemChanged(actionIndex)
        }
    }

}
