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
                        // 只要在跑就把整条状态栏（含停止按钮）显示出来 ——
                        // 不依赖状态文案是否为空，免得"在跑但看不到停止"。
                        runStatusBar.visibility =
                            if (state.running) android.view.View.VISIBLE
                            else android.view.View.GONE
                    }
                }
            }
        }
        reload()
    }

    /** 从磁盘重读列表。 */
    private fun reload() {
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
                        android.widget.Toast.makeText(
                            host, "虚拟屏没在运行：先回第 1 页点「在虚拟屏启动游戏」",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        return@setOnClickListener
                    }
                    // 运行前先保存内存里的编辑：用户可能刚在第 3 页改过
                    // 但没点保存，直接跑会是旧版本 —— 那种"我明明改了"最难查。
                    flushEditorIfSame(item.name)
                    AutomationService.zjsRun(host, item.file.absolutePath)
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
