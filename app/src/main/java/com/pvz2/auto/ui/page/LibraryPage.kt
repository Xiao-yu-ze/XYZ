package com.pvz2.auto.ui.page

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.pvz2.auto.MainActivity
import com.pvz2.auto.R
import com.pvz2.auto.core.ScriptRunState
import com.pvz2.auto.service.AutomationService
import com.pvz2.auto.zjs.ZjsInputs
import com.pvz2.auto.zjs.ZjsRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 第 2 页：脚本库。
 *
 * ## 职责划分（三页各管一件事）
 *
 * ```
 * 第 1 页  虚拟屏 + 画面      ← 看
 * 第 2 页  脚本库              ← 跑
 * 第 3 页  编辑器              ← 改
 * ```
 *
 * 分开的理由不是好看，是**每件事都需要屏幕空间**：脚本跑起来时画面要尽量大，
 * 编辑时字段列表要尽量长。原来第 3 页同时塞了保存/另存为/运行/停止/添加，
 * 光按钮就占掉半屏，字段全被挤到看不见。
 *
 * ## 为什么点卡片是编辑、按钮才是运行
 *
 * 两个动作后果差很远：点卡片只是打开，运行会真的操作游戏。
 * 把运行做成卡片本身的话，想改一下脚本却误触运行，游戏已经被点了。
 */
class LibraryPage(
    private val host: AppCompatActivity,
    private val root: View
) {

    private val rv: RecyclerView = root.findViewById(R.id.rvLibrary)
    private val hint: TextView = root.findViewById(R.id.tvLibraryHint)
    private val count: TextView = root.findViewById(R.id.tvLibraryCount)
    private val runStatus: TextView = root.findViewById(R.id.tvRunStatus)
    private val runStatusBar: android.view.View = root.findViewById(R.id.llRunStatus)

    private val adapter = LibraryAdapter()

    fun bind() {
        rv.layoutManager = LinearLayoutManager(host)
        rv.adapter = adapter

        // 页面级「停止」。不依赖卡片上的名字匹配 —— 卡片那颗按钮靠
        // `runState.scriptName == item.name` 判断，名字对不上就永远显示「运行」，
        // 用户就停不下来（真机上发生过）。这颗没有前置条件。
        root.findViewById<MaterialButton>(R.id.btnRunStop).setOnClickListener {
            AutomationService.zjsStop(host)
        }
        // 「暂停」动作会停在原地等这一下；只有暂停中才显示，避免平时误点
        val resume = root.findViewById<MaterialButton>(R.id.btnRunResume)
        resume.setOnClickListener { ScriptRunState.requestResume() }

        /*
         * 新建：造一份**空脚本**直接进编辑器。
         *
         * 之前没有入口 —— 想写脚本只能导入一个别人的再改，
         * 与「这是个脚本编辑器」这件事不符。
         *
         * 骨架走 ZjsFileFormat.parse 而不是手搓 Z.Script：
         * baseInfo 的字段默认值、类型、单位只有解析器那一处是权威，
         * 在这里再造一份必然和它漂移（历史上就是这么漂的）。
         */
        root.findViewById<MaterialButton>(R.id.btnLibraryNew).setOnClickListener {
            val skeleton = """
                {"repeatCount":1,"delay":"200","delayUnit":0,"pauseOnFail":true,"screenWidth":1280,"screenHeight":720,"description":"新脚本"}
            """.trimIndent()
            val parsed = com.pvz2.auto.zjs.ZjsFileFormat.parse(skeleton)
            val fresh = parsed.script ?: run {
                android.widget.Toast.makeText(
                    host, "新建失败：${parsed.error ?: "骨架脚本无法解析"}", android.widget.Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            // 文件名先空着：编辑器的「保存」会问名字，用户自己定
            ZjsEditState.open("", fresh)
            (host as? MainActivity)?.gotoPage(2)
            android.widget.Toast.makeText(
                host, "已新建，编辑完点「保存」再起名字", android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        // 导入：交给 Activity 的 SAF 启动器。放这里注册会违反
        // registerForActivityResult 的生命周期约束（可能已经 STARTED）。
        root.findViewById<MaterialButton>(R.id.btnLibraryImport).setOnClickListener {
            (host as? MainActivity)?.importScript()
        }

        // 导出：先选哪一个，再让用户挑保存位置。
        root.findViewById<MaterialButton>(R.id.btnLibraryExport).setOnClickListener {
            if (adapter.items.isEmpty()) {
                hint.visibility = View.VISIBLE
                hint.text = host.getString(R.string.library_export_empty)
                return@setOnClickListener
            }
            val names = adapter.items.map { it.name }.toTypedArray()
            androidx.appcompat.app.AlertDialog.Builder(host)
                .setTitle(R.string.library_export_pick)
                .setItems(names) { dlg, which ->
                    dlg.dismiss()
                    val item = adapter.items[which]
                    // 导出磁盘上的原文，不用内存里的编辑 ——
                    // 用户点的是列表里那一份，"导出"就该是那一份。
                    val text = runCatching { item.file.readText() }.getOrNull()
                    if (text == null) {
                        android.widget.Toast.makeText(
                            host, host.getString(R.string.library_export_failed),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        (host as? MainActivity)?.exportScript(item.name, text)
                    }
                }
                .setNegativeButton(R.string.picker_cancel, null)
                .show()
        }

        host.lifecycleScope.launch {
            host.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 脚本文件变化（保存、删除）后要重列 —— 否则用户刚存完
                // 回到这页看不到自己刚做的东西。
                launch {
                    ZjsEditState.savedRevision.collect { reload() }
                }
                // 运行状态变了要重画按钮（运行中那颗变成「停止」），
                // 同时把"正在等什么"写到状态行 —— 脚本没动静时，
                // 用户要能一眼分清"在等画面"和"卡死了"。
                launch {
                    ScriptRunState.state.collect { state ->
                        adapter.submit(adapter.items, state)
                        runStatus.text = state.message.ifBlank { "运行中" }
            resume.visibility = if (state.running && ScriptRunState.paused) View.VISIBLE else View.GONE
                        // 只要在跑就把整条状态栏（含停止按钮）显示出来 ——
                        // 不依赖状态文案是否为空，免得"在跑但看不到停止"。
                        //
                        // ★ 失败之后也要留着：任务一失败 running 立刻变 false，
                        //   状态条跟着消失，用户只看到脚本停了、不知道停在哪、
                        //   为什么停（实测反馈：任务失败没有提示）。
                        runStatusBar.visibility = when {
                            state.running -> android.view.View.VISIBLE
                            state.message.startsWith("失败") -> android.view.View.VISIBLE
                            else -> android.view.View.GONE
                        }
                    }
                }
            }
        }
        reload()
    }

    /**
     * 从磁盘重读列表。
     *
     * ★ 必须是**公开**的：第 5 页「应用脚本」在运行中会往这个目录写文件，
     *   而这一页只在首次绑定（bind）时读一次目录 —— 所以回到第 2 页看不到新脚本。
     *   现在 MainActivity 在切到本页时调 [reload] 一次。
     */
    fun reload() {
        host.lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { ZjsRunner.listSaved(host) }
            adapter.submit(list, ScriptRunState.state.value)
            count.text = if (list.isEmpty()) "" else "${list.size} 个"
            hint.apply {
                visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                text = host.getString(R.string.library_empty)
            }
        }
    }

    // ------------------------------------------------------------ 适配器

    private inner class LibraryAdapter : RecyclerView.Adapter<LibraryAdapter.VH>() {

        var items: List<ZjsRunner.Saved> = emptyList()
            private set

        private var runState: ScriptRunState.State = ScriptRunState.State()

        fun submit(list: List<ZjsRunner.Saved>, state: ScriptRunState.State) {
            items = list
            runState = state
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_library_script, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.name.text = item.name
            holder.meta.text = buildString {
                append("${item.actionCount} 个动作")
                append(" · ")
                append(SIMPLE_TIME.format(Date(item.modified)))
            }

            // 这个脚本是不是正在跑的那个。按名字比 —— 路径里带内部目录，
            // 用户看不出来，而且换设备就不一样。
            val isRunning = runState.running && runState.scriptName == item.name

            holder.run.text = host.getString(
                if (isRunning) R.string.library_stop else R.string.library_run
            )
            holder.run.setOnClickListener {
                if (isRunning) {
                    AutomationService.zjsStop(host)
                } else {
                    // 虚拟屏没跑就地拦下并说清楚。
                    //
                    // 不拦的话：脚本会"跑完"，但每一下手势都被注入层丢掉，
                    // 界面上什么都不变 —— 表现就是「点运行没反应」。
                    val vd = com.pvz2.auto.service.VirtualDisplaySession.sharedState.value
                    if (!vd.running || vd.displayId <= 0) {
                        /*
                         * 用对话框而不是 toast：toast 一闪就没了，用户只说得出"点运行没反应"。
                         * 这里把原因写清楚，并给一个直接跳过去的按钮。
                         */
                        androidx.appcompat.app.AlertDialog.Builder(host)
                            .setTitle("虚拟屏没在运行")
                            .setMessage(
                                "脚本要靠虚拟屏识别画面、注入点击。\n\n" +
                                    "请在「虚拟屏」页点「在虚拟屏启动游戏」，等状态变成「运行中」再点运行。"
                            )
                            .setPositiveButton("去启动") { _, _ ->
                                (host as? MainActivity)?.gotoPage(0)
                            }
                            .setNegativeButton("知道了", null)
                            .show()
                        return@setOnClickListener
                    }
                    // 运行前先保存内存里的编辑：用户可能刚在第 3 页改过
                    // 但没点保存，直接跑会是旧版本 —— 那种"我明明改了"最难查。
                    flushEditorIfSame(item.name)

                    // 运行后直接翻到第 3 页：那一页会把"跑到哪一条、
                    // 跑了几次"直接画在节点上 —— 用户不用在这一页和
                    // 预览之间来回找。
                    //
                    // 先把这份脚本装进编辑器状态，否则第 3 页显示的是
                    // 上一次打开的那份，进度会画到错的节点上。
                    val runScript = ZjsRunner.loadSaved(item.file)
                    if (runScript != null) ZjsEditState.open(item.file.name, runScript)
                    
                    /*
                     * 「从键盘输入」的变量：点运行先弹窗问值，再真的开跑。
                     *
                     * 脚本格式就是这个流程（`value.showInput` → 运行前输入页，
                     * 预填上次记住的值；留空且非必填 = 删掉这个变量）。
                     * 没有这类变量时直接开跑，不多一次点击。
                     */
                    val inputItems = runScript?.let { ZjsInputs.collect(it) }.orEmpty()
                    if (inputItems.isEmpty()) {
                        AutomationService.zjsRun(host, item.file.absolutePath)
                    } else {
                        promptRunInputs(item.file.absolutePath, inputItems)
                    }
                    (host as? MainActivity)?.gotoPage(2)
                }
                // 立刻按一下，别等下一次状态回调才变字
                host.lifecycleScope.launch {
                    kotlinx.coroutines.delay(150)
                    adapter.submit(items, ScriptRunState.state.value)
                }
            }

            // 点卡片 = 打开编辑
            holder.itemView.setOnClickListener {
                val script = ZjsRunner.loadSaved(item.file) ?: run {
                    android.widget.Toast.makeText(
                        host, host.getString(R.string.library_load_failed),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }
                ZjsEditState.open(item.file.name, script)
                (host as? MainActivity)?.gotoPage(2)
            }

            // 长按删除。要确认 —— 删脚本是不可逆的，而它可能是用户做了半小时的。
            holder.itemView.setOnLongClickListener {
                androidx.appcompat.app.AlertDialog.Builder(host)
                    .setTitle(host.getString(R.string.library_delete_title, item.name))
                    .setMessage(host.getString(R.string.library_delete_message))
                    .setPositiveButton(host.getString(R.string.library_delete_ok)) { _, _ ->
                        val ok = item.file.delete()
                        ZjsEditState.markSaved()   // 触发重列
                        if (!ok) {
                            android.widget.Toast.makeText(
                                host, host.getString(R.string.library_delete_failed),
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    .setNegativeButton(host.getString(R.string.picker_cancel), null)
                    .show()
                true
            }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.tvLibName)
            val meta: TextView = v.findViewById(R.id.tvLibMeta)
            val run: MaterialButton = v.findViewById(R.id.btnLibRun)
        }
    }

    /**
     * 如果正在编辑的就是这个脚本，先把内存里的改动落盘再运行。
     *
     * 不这么做的话，用户在第 3 页改完直接切过来点运行，跑的是磁盘上的旧版本 ——
     * 而他刚才明明看到界面变了。
     */
    /**
     * 运行前问「从键盘输入」的那些变量。
     *
     * 每个变量一个输入框，预填「上次记住的值」或脚本里配的值；
     * 必填留空就不让开跑（mustInput 默认是 true）。
     */
    private fun promptRunInputs(path: String, items: List<ZjsInputs.Item>) {
        val prefs = host.getSharedPreferences("zjs_input_memory", android.content.Context.MODE_PRIVATE)
        val dp = host.resources.displayMetrics.density
        val box = android.widget.LinearLayout(host).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), 0)
        }
        // 每行可能是文本框，也可能是下拉 —— 用"取值函数"统一收集
        val readers = ArrayList<Pair<ZjsInputs.Item, () -> String>>()
        for (item in items) {
            box.addView(
                android.widget.TextView(host).apply {
                    text = item.label + if (item.mustInput) " *" else "（可空）"
                    setPadding(0, (10 * dp).toInt(), 0, (2 * dp).toInt())
                }
            )
            if (item.desc.isNotEmpty()) {
                box.addView(
                    android.widget.TextView(host).apply {
                        text = item.desc
                        textSize = 12f
                        setTextColor(0xFF888888.toInt())
                    }
                )
            }
            if (item.options.isNotEmpty()) {
                /*
                 * 下拉：脚本里给了 inputOptions（心塔的"起始关卡/喂豆间隔"这类）。
                 * 默认选中"记住的值"或 defaultValue，都没有就第一项。
                 */
                val remembered = if (item.remember) prefs.getString(item.name, null) else null
                val sp = android.widget.Spinner(host)
                sp.adapter = android.widget.ArrayAdapter(
                    host, android.R.layout.simple_spinner_item, item.options
                ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                val want = remembered ?: item.defaultValue
                val idx = item.options.indexOfFirst { it == want }.coerceAtLeast(0)
                sp.setSelection(idx, false)
                box.addView(sp)
                readers.add(item to { sp.selectedItem?.toString().orEmpty() })
            } else {
                val et = android.widget.EditText(host).apply {
                    setSingleLine(true)
                    hint = item.defaultValue
                    val remembered = if (item.remember) prefs.getString(item.name, null) else null
                    setText(remembered ?: item.defaultValue)
                }
                box.addView(et)
                readers.add(item to { et.text.toString().trim() })
            }
        }
        val dlg = androidx.appcompat.app.AlertDialog.Builder(host)
            .setTitle("运行前设置变量")
            .setView(android.widget.ScrollView(host).apply { addView(box) })
            .setPositiveButton("开始", null)
            .setNegativeButton("取消", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val values = LinkedHashMap<String, String>()
                for ((item, read) in readers) {
                    val v = read()
                    if (v.isEmpty() && item.mustInput) {
                        android.widget.Toast.makeText(
                            host, "「${item.label}」还没选", android.widget.Toast.LENGTH_SHORT
                        ).show()
                        return@setOnClickListener
                    }
                    values[item.name] = v
                    if (item.remember) {
                        if (v.isEmpty()) prefs.edit().remove(item.name).apply()
                        else prefs.edit().putString(item.name, v).apply()
                    }
                }
                dlg.dismiss()
                AutomationService.zjsRun(host, path, values)
            }
        }
        dlg.show()
    }


    private fun flushEditorIfSame(name: String) {
        val st = ZjsEditState
        if (st.fileName.removeSuffix(".zjs") != name) return
        val script = st.script ?: return
        val target = java.io.File(ZjsRunner.savedDir(host), "$name.zjs")
        ZjsRunner.saveSaved(target, script)
    }

    private companion object {
        val SIMPLE_TIME = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    }
}
