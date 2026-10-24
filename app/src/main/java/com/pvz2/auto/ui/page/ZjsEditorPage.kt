package com.pvz2.auto.ui.page

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.drop
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
import kotlinx.coroutines.Job
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
    /** 当前打开的条件弹窗。改字段时要先关掉它再重开，否则会层层叠加。 */
    private var conditionDialog: androidx.appcompat.app.AlertDialog? = null
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

    /**
     * 栈帧：正在编辑"哪条动作链"上的哪个监听槽位。
     *
     * ## 为什么必须存**全局路径**，不能存"本地下标"（修掉的 P1）
     *
     * 原来是 `Frame(actionIndex, slot)`，而 actionIndex 是**压栈那一刻当前列表**里的下标。
     * 顶层压栈时它恰好等于顶层下标；但第二层是在"槽位列表"（最多 1 条）里压栈的，
     * actionIndex 恒为 0 —— 取 owner 时 `s.actions.getOrNull(0)` 拿到的是**第 1 条顶层动作**。
     *
     * 表现（真机上能复现）：进到"监听的监听"里，列表突然变成**另一条动作**的槽位；
     * 之后每一次改字段、删除都写进了那条错误的动作，而界面上看不出任何异常。
     *
     * [path] 是一串下标，从顶层动作列表开始逐层往下：
     *  - `[3]`    → 第 4 条顶层动作；
     *  - `[3,0]`  → 第 4 条动作的**槽位里**那条动作（槽位列表只有一个元素，所以尾部恒 0）；
     *  - `[3,0,0]`→ 再往里一层。
     *
     * 每一层"往下走"用的是哪个槽位，由 `navStack` 里**上一层那帧的 [slot]** 给出 ——
     * 所以解析必须结合整个栈（[actionAtIndex]），单独一个 path 不够。
     * 这和条件侧的 `CondTarget` 是同一套思路。
     */
    private data class Frame(val path: List<Int>, val slot: Z.CallbackSlot)

    /**
     * 按路径取动作。
     *
     * `path[i]` 是"第 i 层列表"里的下标；第 i 层 → 第 i+1 层的通道是
     * `navStack[i].slot`（第 i 帧正是为了下潜到第 i+1 层而压入的）。
     *
     * 越界 / 路径失效返回 null（调用方据此清栈或放弃这次写回）。
     */
    private fun actionAtIndex(path: List<Int>): Z.Action? {
        if (path.isEmpty()) return null
        var list: List<Z.Action> = script?.actions ?: return null
        // 前 path.lastIndex 段是"逐层下潜"：取下标 → 用该层帧的槽位进下一层
        for (i in 0 until path.lastIndex) {
            val owner = list.getOrNull(path[i]) ?: return null
            val slot = navStack.getOrNull(i)?.slot ?: return null
            list = owner.callbacks[slot]?.let { listOf(it) } ?: return null
        }
        // 最后一段就是目标动作所在的那一层
        return list.getOrNull(path.last())
    }

    /**
     * 把 [path] 指向的那条动作替换成 [next]，再逐层包回顶层。
     *
     * @return 新的顶层列表；路径失效返回 null（调用方不要覆盖 script）。
     *
     * 和 [actionAtIndex] 对称：自底向上，每层用 `navStack[depth].slot` 把内层装回去。
     */
    private fun writeActionAt(
        list: List<Z.Action>,
        path: List<Int>,
        next: Z.Action,
        depth: Int = 0
    ): List<Z.Action>? {
        val idx = path.getOrNull(depth) ?: return null
        val cur = list.getOrNull(idx) ?: return null
        val out = list.toMutableList()
        if (depth == path.lastIndex) {
            out[idx] = next
            return out
        }
        val slot = navStack.getOrNull(depth)?.slot ?: return null
        val inner = cur.callbacks[slot] ?: return null
        val innerNew = writeActionAt(listOf(inner), path, next, depth + 1)?.firstOrNull() ?: return null
        val cb = LinkedHashMap(cur.callbacks)
        cb[slot] = innerNew
        out[idx] = cur.copy(callbacks = cb)
        return out
    }

    /**
     * 路径失效（脚本被换掉、那条动作已经不在）时清栈退回顶层。
     *
     * 兜底用：不清的话界面会停在空列表上，用户会以为脚本被清空了，
     * 而且下一次编辑会因为 owner 解析失败被静默丢弃（改了等于没改）。
     */
    private fun dropStaleNav() {
        if (navStack.isEmpty()) return
        if (actionAtIndex(navStack.last().path) != null) return
        android.util.Log.w("ZjsEditorPage", "导航路径失效，退回顶层动作列表")
        navStack.clear()
        expandedIndex = -1
    }

    /** 当前层要显示的动作列表。 */
    private fun currentActions(): List<Z.Action> {
        val s = script ?: return emptyList()
        dropStaleNav()
        val f = navStack.lastOrNull() ?: return s.actions
        val owner = actionAtIndex(f.path) ?: return emptyList()
        return owner.callbacks[f.slot]?.let { listOf(it) } ?: emptyList()
    }

    /** 把当前层的列表写回脚本。监听层要沿栈塞回去。 */
    private fun commitActions(newList: List<Z.Action>) {
        val s = script ?: return
        // 所有改动都从这里过，所以在这里标脏最省事 ——
        // 后面要提示"有未保存改动"时不至于漏掉某些编辑路径。
        ZjsEditState.dirty = true
        // 路径失效就回顶层 —— 否则下面 owner 解析失败、改动被静默丢掉。
        dropStaleNav()
        val f = navStack.lastOrNull()
        if (f == null) {
            script = s.copy(actions = newList)
            return
        }
        val owner = actionAtIndex(f.path) ?: return
        val newCallbacks = LinkedHashMap(owner.callbacks)
        /*
         * 监听槽位里**只能有一个动作**（协议：每个槽位是一个完整动作 JSON，
         * 不是数组，见 FIELDS.md:302）。
         *
         * ## 原来的两个坑
         *
         * `currentActions()` 在槽位里恒等于 0 或 1 条，于是：
         *
         *  · 在槽位里「在上方插入」→ newList 有 2 条 → 以前用
         *    `newList.firstOrNull()` 取第一条并写回 → 新动作**覆盖**了原来那条，
         *    用户点了"插入"却把原动作弄没了；
         *  · 在槽位里「删除这一条」→ newList 空 → 以前 `?: return` 直接吞掉，
         *    删除永远无效，而且**没有任何提示**。
         *
         * 现在按槽位语义处理：空列表 = 清掉这个槽位；多出来的一律丢弃并记日志
         * （界面上槽位内已经不再提供"插入/复制"，这里只是兜底）。
         */
        if (newList.isEmpty()) {
            newCallbacks.remove(f.slot)
        } else {
            if (newList.size > 1) {
                android.util.Log.w(
                    "ZjsEditorPage",
                    "监听槽位 ${f.slot.wire} 只能放一个动作，丢弃多余 ${newList.size - 1} 条"
                )
            }
            newCallbacks[f.slot] = newList.first()
        }
        // ★ 按路径写回，而不是"s.actions[f.actionIndex]" —— 后者在 depth ≥ 2 时
        //   指向的是**别的动作**（见 Frame 的注释）。
        val newRoot = writeActionAt(s.actions, f.path, owner.copy(callbacks = newCallbacks)) ?: return
        script = s.copy(actions = newRoot)
    }

    /** 进入某个监听槽位。空槽位先放一个占位动作。 */
    private fun enterSlot(actionIndex: Int, slot: Z.CallbackSlot) {
        val s = script ?: return
        val owner = currentActions().getOrNull(actionIndex) ?: return
        // 新帧的路径 = 上一层的路径 + 本条动作在当前列表里的下标
        val path = (navStack.lastOrNull()?.path ?: emptyList()) + actionIndex
        if (!owner.callbacks.containsKey(slot)) {
            val placeholder = Z.Action(type = Z.ActionType.CLICK)
            val newCb = LinkedHashMap(owner.callbacks)
            newCb[slot] = placeholder
            // 用路径写回占位动作：depth ≥ 2 时写 "list[actionIndex]" 会改错动作
            val newRoot = writeActionAt(s.actions, path, owner.copy(callbacks = newCb)) ?: return
            script = s.copy(actions = newRoot)
            ZjsEditState.dirty = true
        }
        navStack.add(Frame(path, slot))
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
    /**
     * 直接把一个字段改成 [value]，不走弹窗。
     *
     * 开关和滑块用这条路径 —— 它们的值已经由控件本身确定了，
     * 再弹一次输入框等于让用户做两遍。
     */
    private fun commitFieldValue(row: ZActionDescriber.Row, actionIndex: Int, value: String) {
        val cur = script ?: return
        val action = currentActions().getOrNull(actionIndex) ?: return
        commitField(cur, action, actionIndex, row, value)
    }

    /** 模板缩略图缓存。base64 解码 + 缩放很贵，而列表每次绑定都要用。 */
    private val thumbCache = object : android.util.LruCache<Int, android.graphics.Bitmap>(24) {}

    /** 把模板 base64 解成小图。失败返回 null（界面上显示"未设置"而不是崩）。 */
    private fun templateThumb(b64: String): android.graphics.Bitmap? {
        val key = b64.hashCode()
        thumbCache.get(key)?.let { return it }
        val bmp = runCatching {
            val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull() ?: return null
        thumbCache.put(key, bmp)
        return bmp
    }

    /**
     * 从字段路径反查模板数据。
     *
     * 路径形如 `位置.image` / `起点.image`，前缀就是 posData 在动作里的位置。
     */
    private fun imageDataFor(field: String, actionIndex: Int): Z.ImageData? {
        val action = currentActions().getOrNull(actionIndex) ?: return null
        val prefix = field.substringBeforeLast('.')
        val pos = when {
            prefix == "位置" -> action.posData
            prefix == "起点" -> action.startPos
            prefix == "终点" -> action.endPos
            // 手指N 起点/终点：手指行目前不显示缩略图，返回 null 即可
            else -> null
        }
        return pos?.imageData
    }
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
                    val chosen = row.options[which]
                    /*
                     * 换动作类型前先问一句「会不会丢模板/范围」。
                     *
                     * 模板是用户框选 + PNG 编码出来的，而换类型**没有撤销**：
                     *  · 「点击图片 → 按键 / 打开链接」这类无位置类型：整个 posData 被清掉；
                     *  · 「点击图片 → 点击文字 / 点击」：重建 PosData 时只保留 limitArea，
                     *    imageData（模板）直接消失。
                     * 一次误点就永久丢掉，所以先确认再改。
                     *
                     * 判据在 ZFieldEditor.changeTypeWouldLoseTemplate（纯函数），
                     * 界面这边只负责问，不重复实现规则 —— 否则两处一定会有分歧。
                     */
                    if (row.field == "type") {
                        val next = Z.ActionType.entries.firstOrNull {
                            ZFieldEditor.actionTypeLabel(it) == chosen
                        }
                        val losesTemplate = next != null &&
                            ZFieldEditor.changeTypeWouldLoseTemplate(action, next)
                        if (losesTemplate) {
                            androidx.appcompat.app.AlertDialog.Builder(host)
                                .setTitle("换类型会丢模板")
                                .setMessage(
                                    "换成「$chosen」会丢掉当前位置的模板或识别范围，无法撤销。继续吗？"
                                )
                                .setPositiveButton("继续") { _, _ ->
                                    commitField(cur, action, actionIndex, row, chosen)
                                }
                                .setNegativeButton("取消", null)
                                .show()
                            return@setSingleChoiceItems
                        }
                    }
                    commitField(cur, action, actionIndex, row, chosen)
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
            //
            // ★ 必须带 TYPE_NUMBER_FLAG_SIGNED：协议里等待/偏移这类字段支持
            //   "1-3" 这种**区间**写法（范围内随机），而纯 TYPE_CLASS_NUMBER
            //   的键盘上根本没有 "-" 键 —— 用户想填区间时打不出减号，
            //   输入框里只剩 "13"，保存后变成一个固定值，随机抖动静默失效。
            inputType = if (row.kind == ZActionDescriber.Kind.NUMBER ||
                row.kind == ZActionDescriber.Kind.PERCENT
            ) {
                android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                    android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
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
    /**
     * 长按某一条：插入 / 复制 / 删除。
     *
     * 原来长按只能删除 —— 用户想在中间插一条就只能从头重做。
     * 自动精灵这类工具的做法是长按出菜单、让你在这条之前/之后插，
     * 这里按同样的思路给四个动作。
     */
    private fun onRowMenu(index: Int) {
        val action = currentActions().getOrNull(index) ?: return
        /*
         * 菜单项跟着"在哪一层"变。
         *
         * 监听槽位里只能有一个动作（协议规定），所以"插入/复制"在里面全是错的：
         *   · 插入 → 槽位变成两条 → 只有第一条生效，用户以为改了其实是覆盖；
         *   · 复制 → 同上，凭空多出一条永远不会被保存的动作。
         * 槽位里只有两种有意义的操作：换成别的动作类型、清空这个监听槽位。
         */
        val inSlot = navStack.isNotEmpty()
        val items = if (inSlot) {
            arrayOf("换成别的动作", "删除这条（清空该监听）")
        } else {
            arrayOf("在它上方插入", "在它下方插入", "复制这一条", "删除这一条")
        }
        androidx.appcompat.app.AlertDialog.Builder(host)
            .setTitle("第 ${index + 1} 条：${ZScriptIO.actionLabel(action)}")
            .setItems(items) { _, which ->
                if (inSlot) {
                    when (which) {
                        0 -> replaceActionAt(index)
                        else -> onDeleteRequested(index)
                    }
                } else {
                    when (which) {
                        0 -> insertActionAt(index)
                        1 -> insertActionAt(index + 1)
                        2 -> duplicateAction(index)
                        else -> onDeleteRequested(index)
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 把第 [index] 条换成另一个类型的动作（监听槽位里"替换"的入口）。
     *
     * 通用字段（等待 / 重复 / 条件 / 备注）跟着走 —— 用户调好的等待时间和条件
     * 不该因为换了个动作类型就没了。
     */
    private fun replaceActionAt(index: Int) {
        if (script == null) return
        val sorted = Z.ActionType.entries.sortedBy { if (it.executable) 0 else 1 }
        val labels = sorted.map { typeLabel(it) }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(host)
            .setTitle("换成什么动作")
            .setItems(labels) { dlg, which ->
                dlg.dismiss()
                val list = currentActions().toMutableList()
                if (index !in list.indices) return@setItems
                val old = list[index]
                /*
                 * ★ 换类型会丢模板/识别范围，必须先问一句。
                 *
                 *   模板是框选 + PNG 编码出来的，没有撤销；
                 *   这条路径以前是静默丢弃，用户在界面上看不出区别，
                 *   直到运行时永远匹配不到才发现。
                 *   （与「类型」下拉那条是同一个判断，复用同一个函数）
                 */
                if (ZFieldEditor.changeTypeWouldLoseTemplate(old, sorted[which])) {
                    androidx.appcompat.app.AlertDialog.Builder(host)
                        .setTitle("换类型会丢模板")
                        .setMessage(
                            "换成「${typeLabel(sorted[which])}」会丢掉当前位置的模板或识别范围，" +
                                "无法撤销。继续吗？"
                        )
                        .setPositiveButton("继续") { _, _ ->
                            val l2 = currentActions().toMutableList()
                            if (index !in l2.indices) return@setPositiveButton
                            l2[index] = newAction(sorted[which]).copy(common = old.common)
                            commitActions(l2)
                            adapter.submit(currentActions())
                            expandedIndex = index
                            adapter.notifyItemChanged(index)
                            toast("已换成「${sorted[which].wire}」")
                        }
                        .setNegativeButton("取消", null)
                        .show()
                    return@setItems
                }
                list[index] = newAction(sorted[which]).copy(common = old.common)
                commitActions(list)
                adapter.submit(currentActions())
                expandedIndex = index
                adapter.notifyItemChanged(index)
                toast("已换成「${sorted[which].wire}」")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 在第 [at] 条之前插入一条新动作（弹出动作类型选择）。 */
    private fun insertActionAt(at: Int) {
        promptAddAction(insertAt = at)
    }

    /** 复制某一条到它下面。 */
    private fun duplicateAction(index: Int) {
        val list = currentActions().toMutableList()
        val src = list.getOrNull(index) ?: return
        list.add(index + 1, src)
        commitActions(list)
        adapter.submit(currentActions())
        expandedIndex = index + 1
        adapter.notifyItemChanged(index + 1)
        android.widget.Toast.makeText(host, "已复制", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun onDeleteRequested(index: Int) {
        val cur = script ?: return
        val action = currentActions().getOrNull(index) ?: return

        androidx.appcompat.app.AlertDialog.Builder(host)
            .setTitle("删除这一条？")
            .setMessage("第 ${index + 1} 个：${ZScriptIO.actionLabel(action)}")
            .setPositiveButton("删除") { _, _ ->
                val list = currentActions().toMutableList()
                // 兜底：弹窗开着的时候脚本可能被换掉（脚本库打开另一份会清栈），
                // 那时 index 已经失效 —— removeAt 会抛 IndexOutOfBounds，
                // 所以先判边界，越界就什么都不做。
                if (index !in list.indices) return@setPositiveButton
                list.removeAt(index)
                commitActions(list)
                adapter.submit(currentActions())
                expandedIndex = -1
                /*
                 * 监听槽位里删掉唯一一条 = 这个槽位被清空了（commitActions 会
                 * 把它从 callbacks 里移除）。此时还停在空列表里没有意义，
                 * 用户会以为界面坏了 —— 直接退回上一层。
                 */
                if (navStack.isNotEmpty() && currentActions().isEmpty()) {
                    popNav()
                }
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
                    // 顶层条件：path 为空的那一个目标
                    showConditionEditor(topCondTarget(actionIndex))
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

            /*
             * ---- 位置周围条件（`posData.filterByAroundConditionSet`）
             *
             * 这一行作用在**位置对象**上，不是动作级条件：它用来
             * "过滤掉周围信息不满足条件的点位"（FIELDS.md:278 的 AroundCondition，
             * 自动精灵的界面里叫「位置周围条件」）。
             *
             * 以前这里没有分支 —— ZActionDescriber 出了行、界面却不认这一行，
             * 点了完全没反应（和「条件类型」是同一类"看得见点不动"）。
             *
             * 挂载点是 `posData.extra["filterByAroundConditionSet"]`，
             * 值是一个 **AroundCondition 对象**（不是数组）。复用条件弹窗编辑，
             * 新增 / 修改 / 清空都走同一个写回目标（见 [writeAroundAt]）。
             */
            row.field.endsWith(".around") -> {
                val prefix = row.field.removeSuffix(".around")
                val pos = posOf(action, prefix)
                if (pos == null) {
                    // 位置对象还不存在（比如滑动还没设起点）—— 说清楚，别静默
                    toast("这个位置还没设置，先设好位置再配周围条件")
                } else {
                    val target = aroundTarget(actionIndex, prefix)
                    if (target.condition == null) {
                        /*
                         * 键存在但解析不出来（格式不认识）→ **不要覆盖**。
                         * 覆盖等于把用户文件里那段原样数据直接删掉，
                         * 而我们还看不懂它 —— 保留原文、明确告诉用户，比"顺手重建"安全。
                         */
                        if (pos.extra.containsKey(AROUND_KEY)) {
                            toast("这条位置周围条件读不出来（格式不认识），已原样保留未改动")
                            return
                        }
                        // 未设置：直接建一个 AroundCondition。
                        // 它的类型只有一种（自动精灵那边 conditionTypesForSwitch
                        // 也只给它这一个），不需要再弹一次类型选择。
                        target.write(ZConditionEditor.create(Z.ConditionType.AROUND))
                    }
                    showConditionEditor(target)
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
     * 一行字段的渲染。动作卡片和条件弹窗**共用这一份**。
     *
     * ## 为什么要共用
     *
     * 条件弹窗原来是把行拼成一个字符串数组丢给 setItems —— 于是同一个
     * 「模板」字段，在动作里是缩略图 + 剪刀，在条件里变成一行"模板：未设置"。
     * 相似度也一样：动作里是滑块，条件里只能弹输入框。
     * 用户会以为这是两个不相干的东西。
     *
     * 所以渲染只写一份，点击/开关/滑块各自传进来。
     */
    private fun buildRowView(
        row: ZActionDescriber.Row,
        imageData: Z.ImageData?,
        onClick: () -> Unit,
        onToggle: (Boolean) -> Unit,
        onPercent: (Int) -> Unit
    ): View {
        val ctx = host

        // ---- 小节标题：不是字段，只画一行淡字
        if (row.kind == ZActionDescriber.Kind.SECTION) {
            return TextView(ctx).apply {
                text = row.label
                textSize = 11f
                setTextColor(ctx.getColor(R.color.ink_faint))
                setPadding(0, dp(10), 0, dp(2))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
        }

        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            minimumHeight = dp(38)
            setPadding(0, dp(2), 0, dp(2))
        }

        // 标签窄一点、淡一点。它是"这行是什么"，不是内容本身。
        val label = TextView(ctx).apply {
            text = row.label
            textSize = 12f
            setTextColor(ctx.getColor(R.color.ink_muted))
            layoutParams = LinearLayout.LayoutParams(dp(84), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        line.addView(label)

        // ---- 开关：布尔值直接拨，不弹窗
        if (row.kind == ZActionDescriber.Kind.SWITCH) {
            val sw = com.google.android.material.switchmaterial.SwitchMaterial(ctx).apply {
                isChecked = row.value == "是"
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                text = ""
            }
            sw.setOnCheckedChangeListener { _, checked ->
                onToggle(checked)
            }
            line.addView(sw)
            return line
        }

        // ---- 百分比：滑块 + 可点的数字
        if (row.kind == ZActionDescriber.Kind.PERCENT) {
            val cur = row.value.removeSuffix("%").toIntOrNull() ?: 90
            val bar = android.widget.SeekBar(ctx).apply {
                max = 100
                progress = cur.coerceIn(0, 100)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            // 拖动中只更新数字，松手才写回 —— 每动一下都写一次会把
            // 整个列表重算几十遍，滑动卡成幻灯片。
            val num = TextView(ctx).apply {
                text = "$cur%"
                textSize = 14f
                setTextColor(ctx.getColor(R.color.ink))
                gravity = android.view.Gravity.END
                minWidth = dp(52)
            }
            bar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    num.text = "$p%"
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                    onPercent(sb?.progress ?: cur)
                }
            })
            // 点数字可以填精确值：滑块拖不准 90 和 92 的差别
            num.setOnClickListener { onClick() }
            line.addView(bar)
            line.addView(num)
            return line
        }

        // ---- 模板：显示缩略图，不是「212×211，125352 字符」
        //
        // 那串字符数对用户没有任何意义 —— 他要知道的是"我截的是哪个图案"，
        // 而这件事只能看图。
        if (row.kind == ZActionDescriber.Kind.IMAGE) {
            val img = imageData
            if (img != null) {
                val thumb = android.widget.ImageView(ctx).apply {
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(0x11000000)
                    layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                }
                templateThumb(img.data)?.let { thumb.setImageBitmap(it) }
                line.addView(thumb)
            }
            line.addView(TextView(ctx).apply {
                text = if (img != null) "${img.width ?: 0}×${img.height ?: 0}" else "未设置"
                textSize = 13f
                setTextColor(ctx.getColor(R.color.ink_muted))
                setPadding(dp(8), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            if (row.field.isNotEmpty()) {
                line.addView(glyphButton("✂", "在画面上截取模板") {
                    onClick()
                })
            }
            return line
        }

        val value = TextView(ctx).apply {
            text = row.value.ifEmpty { "—" }
            textSize = 14f
            // 值比标签重要：标签是"这行是什么"，值才是用户要读的东西
            setTextColor(
                ctx.getColor(
                    if (row.kind == ZActionDescriber.Kind.READONLY) R.color.ink_muted else R.color.ink
                )
            )
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setSingleLine(false)
        }
        line.addView(value)

        // 可编辑字段点一下弹输入框。
        // 只读字段不挂点击 —— 挂了会让人以为能改，点了没反应比不能点更糟。
        // 坐标和模板**不给整行点击**：
        //   坐标弹窗让用户手输像素，而他根本不知道那个按钮在第几像素 ——
        //   必须用准星在画面上取。
        //   模板弹窗只能改摘要字符串，里面的 base64 他改不了，纯耽误事。
        val clickable = row.kind != ZActionDescriber.Kind.READONLY &&
            row.kind != ZActionDescriber.Kind.COORD &&
            row.kind != ZActionDescriber.Kind.IMAGE &&
            row.field.isNotEmpty()
        if (clickable) {
            line.setBackgroundResource(android.R.drawable.list_selector_background)
            line.setOnClickListener {
                // ENTER 是"点进去看另一层"，不是改标量 ——
                // 弹输入框只能改到摘要字符串，里面那层还是原样。
                if (row.kind == ZActionDescriber.Kind.ENTER) {
                    onClick()
                } else {
                    onClick()
                }
            }
        }

        // 坐标字段多一个取点按钮。
        // 取 X 和 Y 是一次操作同时拿到的，所以 X 行放按钮、Y 行不放 ——
        // 两个按钮会让人以为要分别取。
        //
        // ## 判据为什么不能只认 ".x"
        //
        // 「颜色」位置除了 `x`/`y`（取色点）还有一对 `limitPosX`/`limitPosY`
        // （"指定位置"，FIELDS.md:213-214）。只看 `.endsWith(".x")` 的话，
        // 这两行**没有取点入口** —— 用户只能手输 px，而那在游戏画面上根本对不准，
        // 等于这个字段不可用。所以三个后缀都认。
        // （按钮仍只挂在 X 那一半：一次取点同时写 X 和 Y，见 onPickPoint。）
        val coordField = row.field
        val isCoordAnchor = coordField.endsWith(".x") ||
            coordField.endsWith("limitPosX") ||
            coordField.endsWith("limitPosY") ||
            // 取色行：按钮走同一套准星，只是拿到点之后读像素而不是写坐标。
            // 少了这一条，「从屏幕取色」那一行就只有文字、没有 ⊕ ——
            // 用户会以为功能没做。
            coordField.endsWith(".colorPick")
        if (row.kind == ZActionDescriber.Kind.COORD && isCoordAnchor &&
            !coordField.endsWith("limitPosY")
        ) {
            val pickTip = if (coordField.endsWith(".colorPick")) "在画面上取颜色" else "在画面上取这一步的坐标"
        line.addView(glyphButton("⊕", pickTip) {
                onClick()
            })
        }

        // 识别范围（ROI）：在画面上框一块。
        if (row.kind == ZActionDescriber.Kind.AREA && row.field.isNotEmpty()) {
            line.addView(glyphButton("⊕", "在画面上框选识别范围") {
                onClick()
            })
        }

        // 模板图：在画面上框一块并直接裁下来。
        if (row.kind == ZActionDescriber.Kind.IMAGE && row.field.isNotEmpty()) {
            line.addView(glyphButton("✂", "在画面上截取模板") {
                onClick()
            })
        }

        // "进去另一层"的行给个 › ，和弹输入框的行区分开。
        if (row.kind == ZActionDescriber.Kind.ENTER) {
            line.addView(TextView(ctx).apply {
                text = "›"
                textSize = 18f
                setTextColor(ctx.getColor(R.color.ink_faint))
                setPadding(dp(4), 0, dp(2), 0)
            })
        }

        return line
    }

    /** 行尾的小图标按钮。⊕ / ✂ 这类，统一尺寸和颜色。 */
    private fun glyphButton(glyph: String, desc: String, onClick: () -> Unit): View =
        TextView(host).apply {
            text = glyph
            textSize = 15f
            setPadding(dp(10), 0, dp(2), 0)
            setTextColor(host.getColor(R.color.ink_faint))
            contentDescription = desc
            setOnClickListener { onClick() }
        }

    private fun dp(v: Int): Int = (v * host.resources.displayMetrics.density).toInt()

    /**
     * 条件的写回目标。
     *
     * ## 为什么需要这一层
     *
     * 条件编辑界面要服务**三个地方**：
     *  - 顶层动作的条件（`action.common.condition`）；
     *  - 「多个条件」里的子条件（`extra["conditions"]` 数组的一项）；
     *  - 「位置周围条件」（`posData.filterByAroundConditionSet`，只作用于某个位置）。
     *
     * 三者的界面必须完全一样（取点、框选、截模板、预览全都要能用），
     * 否则用户会以为它们是三套东西。原来这些函数把 `(action, actionIndex)`
     * 写死在签名里、只能写回顶层，于是子条件和周围条件**只能看到、改不了**。
     *
     * [actionIndex] + [posPrefix] + [path] 唯一确定一个条件：
     *  - `posPrefix == null` → 根是动作的条件；
     *  - `posPrefix == "起点"/"终点"/"位置"` → 根是该位置对象的 `filterByAroundConditionSet`；
     *  - `path` 是子条件下标链（空 = 根节点本身）。
     *
     * 用"路径"而不是快照闭包，是因为连续改多个字段时快照会过期 ——
     * 从模型现读现写永远不会拿旧数据重建、把上一次的改动吞掉。
     */
    private inner class CondTarget(
        val actionIndex: Int,
        val path: List<Int>,
        /** null = 动作的条件；否则是哪个位置对象的周围条件（"起点"/"终点"/"位置"）。 */
        val posPrefix: String? = null
    ) {
        /** 当前这条条件。写不进去（下标越界/已被删）时返回 null。 */
        val condition: Z.Condition? get() = readConditionAt(actionIndex, posPrefix, path)

        /** 写回；[next] 为 null 表示删除这一层。 */
        fun write(next: Z.Condition?) = writeConditionAt(actionIndex, posPrefix, path, next)

        /** 给子条件造一个目标（周围条件的子条件也跟着父走）。 */
        fun child(index: Int): CondTarget = CondTarget(actionIndex, path + index, posPrefix)

        /**
         * 上一层（父条件）。顶层没有父层，返回 null。
         *
         * 删除子条件之后要回到父层的**子条件列表** —— 否则用户想连删两个，
         * 得从动作卡片重新点「条件」→「子条件」两次。
         */
        fun parent(): CondTarget? =
            if (path.isEmpty()) null else CondTarget(actionIndex, path.dropLast(1), posPrefix)

        /** 是不是子条件（决定"删除"按钮的文案）。 */
        val isChild: Boolean get() = path.isNotEmpty()

        /** 是不是「位置周围条件」（它的 type 只能固定为 AroundCondition）。 */
        val isAround: Boolean get() = posPrefix != null
    }

    /** 顶层动作的条件。 */
    private fun topCondTarget(actionIndex: Int): CondTarget = CondTarget(actionIndex, emptyList())

    /** 某个位置对象的「周围条件」。 */
    private fun aroundTarget(actionIndex: Int, posPrefix: String): CondTarget =
        CondTarget(actionIndex, emptyList(), posPrefix)

    /** 按前缀取动作里的位置对象。 */
    private fun posOf(action: Z.Action, prefix: String?): Z.PosData? = when (prefix) {
        "起点" -> action.startPos
        "终点" -> action.endPos
        else -> action.posData
    }

    /**
     * 读某个位置的「周围条件」。
     *
     * 协议：`posData.filterByAroundConditionSet` 是一个 **AroundCondition** 对象
     * （不是条件数组）；自动精灵读它时会先校验 type：
     *
     * ```js
     * // m14040
     * const e = tr(this.json.filterByAroundConditionSet);
     * return e?.type === C.CP.AroundCondition ? e : null
     * ```
     *
     * 也就是说 type 不对/缺失时它会**当作没设置**（而且 `tr()` 遇到未知 type 直接抛）。
     * 所以这里补一个 type 再解析 —— 顺手把这种坏数据修正掉，而不是原样带回去。
     */
    private fun readAround(pos: Z.PosData): Z.Condition? {
        val obj = pos.extra[AROUND_KEY] as? org.json.JSONObject ?: return null
        val typed = if (obj.optString("type").isNotEmpty()) obj
        else org.json.JSONObject(obj.toString()).put("type", Z.ConditionType.AROUND.wire)
        return Z.Condition.parse(typed)
    }

    /** 按路径取值：根 → 第 path 层。 */
    private fun readConditionAt(actionIndex: Int, posPrefix: String?, path: List<Int>): Z.Condition? {
        val action = currentActions().getOrNull(actionIndex) ?: return null
        var c: Z.Condition? = if (posPrefix == null) {
            action.common.condition
        } else {
            posOf(action, posPrefix)?.let { readAround(it) }
        }
        for (i in path) {
            c = c?.let { ZConditionEditor.children(it).getOrNull(i) } ?: return null
        }
        return c
    }

    /**
     * 按路径写值。`path` 为空 = 改根节点；否则自底向上把每一层包回去。
     *
     * 自底向上是因为子条件是值对象：改第 3 个子条件，得先造出新的子条件，
     * 再造出"装它的那个条件"，一直包回根。
     */
    private fun writeConditionAt(
        actionIndex: Int,
        posPrefix: String?,
        path: List<Int>,
        next: Z.Condition?
    ) {
        if (path.isEmpty()) {
            if (posPrefix == null) {
                val action = currentActions().getOrNull(actionIndex) ?: return
                applyCondition(action, actionIndex, next)
            } else {
                writeAroundAt(actionIndex, posPrefix, next)
            }
            return
        }
        var rebuilt: Z.Condition? = next
        for (depth in path.indices.reversed()) {
            val parent = readConditionAt(actionIndex, posPrefix, path.subList(0, depth)) ?: return
            val kids = ZConditionEditor.children(parent).toMutableList()
            val idx = path[depth]
            if (idx !in kids.indices) return
            if (rebuilt == null) kids.removeAt(idx) else kids[idx] = rebuilt
            rebuilt = ZConditionEditor.withChildren(parent, kids)
        }
        if (rebuilt == null) return
        if (posPrefix == null) {
            currentActions().getOrNull(actionIndex)?.let { applyCondition(it, actionIndex, rebuilt) }
        } else {
            writeAroundAt(actionIndex, posPrefix, rebuilt)
        }
    }

    /**
     * 把「周围条件」写回位置对象。`next == null` = 清空（把键删掉）。
     *
     * 自动精灵的 setter 也是"传 undefined 就删键"
     * （`set filterByAroundConditionSet(e){ this.json.filterByAroundConditionSet = e?.json || void 0 }`），
     * 所以清空时删键才是对的，写一个空对象会让它以为设置了但内容为空。
     */
    private fun writeAroundAt(actionIndex: Int, posPrefix: String, next: Z.Condition?) {
        val action = currentActions().getOrNull(actionIndex) ?: return
        val pos = posOf(action, posPrefix) ?: return
        val extra = LinkedHashMap(pos.extra)
        if (next == null) extra.remove(AROUND_KEY) else extra[AROUND_KEY] = next.toJson()
        val newPos = pos.copy(extra = extra)
        val updated = when (posPrefix) {
            "起点" -> action.copy(startPos = newPos)
            "终点" -> action.copy(endPos = newPos)
            else -> action.copy(posData = newPos)
        }
        val list = currentActions().toMutableList()
        list[actionIndex] = updated
        commitActions(list)
        adapter.submit(currentActions())
        expandedIndex = actionIndex
        adapter.notifyItemChanged(actionIndex)
    }

    /**
     * 编辑一个已存在的条件。
     *
     * 弹一个列表，把条件摊平成行 —— 和动作的字段行同一套结构，
     * 所以下拉、数字键盘这些行为完全一致，不用另写一套。
     */
    private fun showConditionEditor(t: CondTarget) {
        // 先关掉上一个，否则每次改字段都会在旧的上面再叠一个
        conditionDialog?.dismiss()
        // 从模型现读（不用打开弹窗那一刻的快照）：连续改多个字段时，
        // 第二次必须基于第一次的结果改，否则会把第一次的改动覆盖掉。
        val condition = t.condition ?: return

        val rows = ZConditionEditor.describe(condition)
        val box = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(6))
        }

        for (row in rows) {
            box.addView(
                buildRowView(
                    row = row,
                    imageData = conditionImageData(condition, row.field),
                    onClick = { editConditionRow(t, row) },
                    onToggle = { checked ->
                        commitConditionField(t, row, if (checked) "是" else "否")
                    },
                    onPercent = { p -> commitConditionField(t, row, "$p") }
                )
            )
        }

        val scroll = android.widget.ScrollView(host).apply {
            addView(box)
            // 条件字段比屏幕高，必须能滚 —— 用 setItems 时是系统帮忙滚的
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(420)
            )
        }

        conditionDialog = androidx.appcompat.app.AlertDialog.Builder(host)
            .setTitle(
                when {
                    t.isAround -> "位置周围条件"
                    t.isChild -> "子条件"
                    else -> "条件"
                }
            )
            .setView(scroll)
            // 删除也走同一个写回目标：顶层 → condition=null，子条件 → 从数组里移除，
            // 周围条件 → 把 posData 上的 filterByAroundConditionSet 键删掉。
            // 原来这段写死了"把 action.common.condition 置空"，所以后两者删不掉。
            .setNeutralButton(
                when {
                    t.isAround -> "清空周围条件"
                    t.isChild -> "删除这个子条件"
                    else -> "删除条件"
                }
            ) { _, _ ->
                val parent = t.parent()
                t.write(null)
                /*
                 * 删掉的是**子条件** → 回父层的子条件列表继续删/继续改。
                 *
                 * 用 post 让当前弹窗走完自己的销毁流程再开新弹窗：
                 * 直接开的话新窗口会先挂在旧窗口下面，旧窗口一消失布局重排，
                 * 和「预览」那里踩过的坑是同一个（见 editConditionRow 的 cond.preview）。
                 */
                if (parent != null) {
                    host.window.decorView.post { showChildConditions(parent) }
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 条件某个字段的值改掉，然后重开条件弹窗（行内容要跟着变）。 */
    private fun commitConditionField(
        t: CondTarget,
        row: ZActionDescriber.Row,
        value: String
    ) {
        val condition = t.condition ?: return
        val next = ZConditionEditor.apply(condition, row.field, value)
        if (next == null) {
            // 和动作侧（commitField）对齐：值不合法要**说出来**。
            // 以前这里是 `?.let{}`，失败就什么都不发生 —— 用户以为改成功了，
            // 实际文件里一个字都没变，回头运行才发现阈值/文字还是旧的。
            toast("「${row.label}」的值不合法，未保存")
            return
        }
        t.write(next)
        // 改完回到列表：条件字段多，用户通常是连着改好几项
        showConditionEditor(t)
    }

    /** 条件里某个字段对应的模板图。动作的模板在 posData，AI 的三张图在 extra。 */
    private fun conditionImageData(condition: Z.Condition, field: String): Z.ImageData? =
        when (field) {
            "cond.image" -> condition.posData?.imageData
            "aiImage1" -> condition.extra["image1"] as? Z.ImageData
            "aiImage2" -> condition.extra["image2"] as? Z.ImageData
            "aiImage3" -> condition.extra["image3"] as? Z.ImageData
            else -> null
        }

    /** 改条件里的一个字段，然后回到条件编辑列表。 */
    private fun editConditionRow(t: CondTarget, row: ZActionDescriber.Row) {
        // 取色：与动作侧共用同一套准星，读当前帧像素写回 cond.color
        if (row.field == "cond.colorPick") {
            val c = t.condition ?: return
            val cx = c.posData?.extra?.get("limitPosX")?.toString()?.toIntOrNull()
            val cy = c.posData?.extra?.get("limitPosY")?.toString()?.toIntOrNull()
            pickColorThen(cx, cy) { hex, _, _ ->
                commitConditionField(
                    t,
                    ZActionDescriber.Row("颜色", hex, ZActionDescriber.Kind.TEXT, field = "cond.color"),
                    hex
                )
                toast("已取色 #$hex")
            }
            return
        }

        val condition = t.condition ?: return

        /*
         * 换类型。
         *
         * ## 以前这里是死代码
         *
         * 判据是 `row.label == "条件类型"`，但那一行原来是 READONLY + field 为空，
         * 界面上根本不挂点击（见 ZConditionEditor.describe 的注释），所以永远进不来。
         * 现在按 field 认（`cond.type`），并且 ZConditionEditor 把它摊成了 ENUM。
         */
        if (row.field == "cond.type") {
            /*
             * 「位置周围条件」的类型被协议钉死，不给换。
             *
             * 自动精灵读 `filterByAroundConditionSet` 时会先校验
             * `e?.type === "AroundCondition"`，不是就**当作没设置**（m14040）。
             * 让用户在这里改成"出现图片"，文件里会留下一个 type=image 的对象，
             * 自动精灵静默忽略它 —— 用户以为配了过滤，其实什么都没生效。
             */
            if (t.isAround) {
                toast("位置周围条件的类型固定是「位置周围条件」，不能改成别的")
                return
            }
            val labels = ZConditionEditor.TYPE_LABELS.map { it.second }.toTypedArray()
            androidx.appcompat.app.AlertDialog.Builder(host)
                .setTitle("条件类型")
                .setItems(labels) { dlg, which ->
                    dlg.dismiss()
                    val type = ZConditionEditor.TYPE_LABELS[which].first
                    if (type == condition.type) return@setItems
                    /*
                     * 换类型会重建位置对象：模板 / 文字 / 颜色 / 相似度跟类型绑定，
                     * 搬过去只会写到一个新类型取不到的键上（协议里键名是
                     * image / text / colorData / node，见 Condition.toJson），
                     * 条件会静默恒 false。
                     * 范围（limitArea）会保留 —— 见 ZConditionEditor.changeType。
                     * 提示放在改动**之前**，用户能看见自己丢了什么。
                     */
                    toast("换成「${labels[which]}」会丢掉原来的模板/文字/颜色")
                    val next = ZConditionEditor.changeType(condition, type)
                    t.write(next)
                    // 换完立刻重新打开，用户能接着填新类型的字段
                    showConditionEditor(t)
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        // 「多个条件」的子条件列表：增 / 改 / 删
        if (row.field == "cond.children") {
            showChildConditions(t)
            return
        }

        // 预览命中位置。条件和动作共用同一套预览，判定完全一致。
        if (row.field == "cond.preview") {
            /*
             * 条件里的「预览」。
             *
             * 既要关掉弹窗（它盖在预览区上，结果条的取消会被挡住），
             * 又要**等弹窗真的消失之后**再启动预览：
             * dismiss() 只是请求关闭，窗口还没销毁。立刻 start 的话，
             * 预览的结果条先贴在弹窗下面，弹窗一消失布局重排，
             * 用户看到的就是"点了预览什么都没发生"。
             *
             * 用 post 让出这一帧，让弹窗走完自己的销毁流程再开始。
             */
            conditionDialog?.dismiss()
            conditionDialog = null
            val target = condition.posData
            host.window.decorView.post {
                previewFor(target)
                android.widget.Toast.makeText(
                    host,
                    if (target == null) "这个条件还没有可预览的位置"
                    else "预览中：结果在画面上方，取消按钮在画面下方",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            return
        }

        val commit: (String) -> Unit = { value ->
            val next = ZConditionEditor.apply(condition, row.field, value)
            if (next == null) {
                // 静默失败会让用户以为改成功了（原来这里是 `?.let{}` 没有 else）
                toast("「${row.label}」的值不合法，未保存")
            } else {
                t.write(next)
                // 改完回到列表：条件字段多，用户通常是连着改好几项
                showConditionEditor(t)
            }
        }

        // 开关：拨一下就好，不再弹窗问一次
        if (row.kind == ZActionDescriber.Kind.SWITCH) {
            commit(if (row.value == "是") "否" else "是")
            return
        }

        // 位置类：都在冻结画面上取，和动作的取点完全一样
        when (row.kind) {
            ZActionDescriber.Kind.COORD -> { pickConditionPoint(t); return }
            ZActionDescriber.Kind.AREA -> { pickConditionRect(t); return }
            ZActionDescriber.Kind.IMAGE -> { pickConditionTemplate(t, row.field); return }
            ZActionDescriber.Kind.PERCENT -> { conditionPercentDialog(t, row); return }
            else -> Unit
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
            // 同 onEditRequested：数字字段必须带 SIGNED，否则区间写法
            // （"1-3" 这种"范围内随机"）的减号在数字键盘上打不出来。
            inputType = if (row.kind == ZActionDescriber.Kind.NUMBER || row.kind == ZActionDescriber.Kind.PERCENT) {
                android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            } else {
                android.text.InputType.TYPE_CLASS_TEXT
            }
        }
        androidx.appcompat.app.AlertDialog.Builder(host)
            .setTitle(row.label)
            .setView(input)
            .setPositiveButton("确定") { _, _ -> commit(input.text?.toString().orEmpty()) }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 「多个条件」的子条件列表。
     *
     * ## 为什么是一个单独的弹窗，而不是走 navStack
     *
     * [navStack] + RecyclerView 那套只能显示 `Z.Action`（监听槽位里的动作），
     * 而子条件是 `Z.Condition`。硬塞进同一个 RecyclerView 要改适配器的
     * item 类型，牵连太多。这里用**同一套行渲染**（ZConditionEditor.describe +
     * buildRowView）做成一个列表弹窗：能看有几个、能进去改、能删、能加。
     */
    private fun showChildConditions(t: CondTarget) {
        val condition = t.condition ?: return
        val kids = ZConditionEditor.children(condition)
        val items = ArrayList<String>()
        items.add("＋ 添加子条件")
        kids.forEachIndexed { i, k ->
            val label = ZConditionEditor.typeLabel(k.type)
            items.add("第 ${i + 1} 个：$label${if (k.disabled) "（已禁用）" else ""}")
        }
        if (kids.isNotEmpty()) items.add("清空全部子条件")

        androidx.appcompat.app.AlertDialog.Builder(host)
            .setTitle("子条件（${kids.size} 个）")
            .setItems(items.toTypedArray()) { dlg, which ->
                dlg.dismiss()
                when {
                    which == 0 -> addChildCondition(t)
                    which == items.size - 1 && kids.isNotEmpty() ->
                        t.write(ZConditionEditor.withChildren(condition, emptyList()))
                    else -> showConditionEditor(t.child(which - 1))
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 给「多个条件」加一个子条件：先选类型，再进它的编辑器。 */
    private fun addChildCondition(t: CondTarget) {
        val condition = t.condition ?: return
        val labels = ZConditionEditor.TYPE_LABELS.map { it.second }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(host)
            .setTitle("添加什么子条件")
            .setItems(labels) { dlg, which ->
                dlg.dismiss()
                val type = ZConditionEditor.TYPE_LABELS[which].first
                val kids = ZConditionEditor.children(condition).toMutableList()
                kids.add(ZConditionEditor.create(type))
                t.write(ZConditionEditor.withChildren(condition, kids))
                // 直接进新加的那一条 —— 用户加完就是要填它
                showConditionEditor(t.child(kids.size - 1))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------ 条件里的取点 / 框选 / 截模板

    /** 条件里的坐标：准星在冻结画面上取。 */
    private fun pickConditionPoint(t: CondTarget) {
        val condition = t.condition ?: return
        val pos = condition.posData
        val ix = pos?.x?.raw?.toIntOrNull()
        val iy = pos?.y?.raw?.toIntOrNull()
        val initial = if (ix != null && iy != null) com.pvz2.auto.core.PreviewPicker.Point(ix, iy) else null

        host.lifecycleScope.launch {
            val deferred = host.lifecycleScope.async {
                com.pvz2.auto.core.PreviewPicker.result.firstOrNull {
                    it.mode == com.pvz2.auto.core.PreviewPicker.Mode.POINT
                }
            }
            com.pvz2.auto.core.PreviewPicker.arm(
                com.pvz2.auto.core.PreviewPicker.Mode.POINT,
                initial = initial,
                frozenFrame = com.pvz2.auto.service.VirtualDisplaySession.previewFlow.value
            )
            toast("按住画面拖动准星，位置对了点确定")

            val r = deferred.await() ?: return@launch
            var next = condition
            ZConditionEditor.apply(next, "cond.x", "%.2f%%".format(100.0 * r.x / r.screenWidth))?.let { next = it }
            ZConditionEditor.apply(next, "cond.y", "%.2f%%".format(100.0 * r.y / r.screenHeight))?.let { next = it }
            t.write(next)
            showConditionEditor(t)
        }
    }

    /** 条件里的搜索范围：四角可拖的框。 */
    private fun pickConditionRect(t: CondTarget) {
        val condition = t.condition ?: return
        host.lifecycleScope.launch {
            val deferred = host.lifecycleScope.async {
                com.pvz2.auto.core.PreviewPicker.result.firstOrNull {
                    it.mode == com.pvz2.auto.core.PreviewPicker.Mode.RECT
                }
            }
            val st = com.pvz2.auto.service.VirtualDisplaySession.sharedState.value
            val iw = st.frameWidth.takeIf { it > 0 } ?: 1280
            val ih = st.frameHeight.takeIf { it > 0 } ?: 720
            // 条件里已经有范围就带出来 —— 不改也能直接点确定
            val existing = condition.posData?.let { p ->
                com.pvz2.auto.zjs.ZPosResolver.searchRect(
                    p,
                    com.pvz2.auto.zjs.ZPosResolver.Screen(iw, ih, host.resources.displayMetrics.density)
                ).takeIf { p.limitArea != null }
            }
            com.pvz2.auto.core.PreviewPicker.arm(
                com.pvz2.auto.core.PreviewPicker.Mode.RECT,
                screenWidth = iw, screenHeight = ih,
                frozenFrame = com.pvz2.auto.service.VirtualDisplaySession.previewFlow.value,
                initialRect = existing
            )
            toast(
                if (existing != null) "范围已带出，拖四角可改，不改就点确定"
                else "在画面上框出搜索范围，四角可拖"
            )

            val r = deferred.await() ?: return@launch
            if (r.w <= 0 || r.h <= 0) return@launch
            val area = areaText(r)
            ZConditionEditor.apply(condition, "cond.limitArea", area)?.let {
                t.write(it)
                showConditionEditor(t)
            }
        }
    }

    /** 条件里的模板：截下来，并把这块同时设成搜索范围。 */
    private fun pickConditionTemplate(t: CondTarget, field: String) {
        val condition = t.condition ?: return
        host.lifecycleScope.launch {
            val deferred = host.lifecycleScope.async {
                com.pvz2.auto.core.PreviewPicker.result.firstOrNull {
                    it.mode == com.pvz2.auto.core.PreviewPicker.Mode.TEMPLATE
                }
            }
            val st = com.pvz2.auto.service.VirtualDisplaySession.sharedState.value
            val iw = st.frameWidth.takeIf { it > 0 } ?: 1280
            val ih = st.frameHeight.takeIf { it > 0 } ?: 720
            com.pvz2.auto.core.PreviewPicker.arm(
                com.pvz2.auto.core.PreviewPicker.Mode.TEMPLATE,
                screenWidth = iw, screenHeight = ih,
                frozenFrame = com.pvz2.auto.service.VirtualDisplaySession.previewFlow.value
            )
            toast("在画面上框住要识别的图案")

            val r = deferred.await() ?: return@launch
            val crop = r.crop ?: return@launch
            if (crop.width <= 0 || crop.height <= 0) return@launch

            val bytes = java.io.ByteArrayOutputStream()
            crop.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bytes)
            val b64 = android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP)
            val img = Z.ImageData(
                data = b64, width = crop.width, height = crop.height,
                left = r.x, top = r.y,
                screenWidth = r.screenWidth, screenHeight = r.screenHeight
            )

            var next = ZConditionEditor.applyImage(condition, field, img) ?: condition
            // 条件里的模板同样决定"在哪找"，范围一起设
            if (field == "cond.image") {
                ZConditionEditor.apply(next, "cond.limitArea", areaText(r))?.let { next = it }
            }
            t.write(next)
            showConditionEditor(t)
        }
    }

    /** 百分比用滑块，和动作字段一致。 */
    private fun conditionPercentDialog(t: CondTarget, row: ZActionDescriber.Row) {
        val condition = t.condition ?: return
        val cur = row.value.removeSuffix("%").toIntOrNull() ?: 50
        val box = android.widget.LinearLayout(host).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 8)
        }
        val bar = android.widget.SeekBar(host).apply { max = 100; progress = cur.coerceIn(0, 100) }
        val num = android.widget.TextView(host).apply {
            text = "$cur%"; textSize = 18f; gravity = android.view.Gravity.CENTER
        }
        bar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                num.text = "$p%"
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) = Unit
        })
        box.addView(num)
        box.addView(bar)

        androidx.appcompat.app.AlertDialog.Builder(host)
            .setTitle(row.label)
            .setView(box)
            .setPositiveButton("确定") { _, _ ->
                ZConditionEditor.apply(condition, row.field, "${bar.progress}")?.let {
                    t.write(it)
                    showConditionEditor(t)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 虚拟屏坐标矩形 → 协议里的 `左 上 右 下` 百分比。 */
    private fun areaText(r: com.pvz2.auto.core.PreviewPicker.Result): String {
        val pct = { v: Int, total: Int -> "%.2f%%".format(100.0 * v / total) }
        return "${pct(r.x, r.screenWidth)} ${pct(r.y, r.screenHeight)} " +
            "${pct(r.x + r.w, r.screenWidth)} ${pct(r.y + r.h, r.screenHeight)}"
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(host, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * 把条件写回动作。
     *
     * @param condition null = 删掉这个动作的条件（顶层条件可以没有）。
     *   子条件的删除不走这里 —— 那条路径由 [writeConditionAt] 从数组里移除。
     */
    private fun applyCondition(action: Z.Action, actionIndex: Int, condition: Z.Condition?) {
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
        // 保存：弹窗里能看到保存到哪、并自己起名字。
        //
        // 不在这里直接存 —— 用户第一次保存时文件名还是"示例_基础动作"，
        // 直接存等于把他做的东西命名成别人的示例。让他起个名字，
        // 才认得出第 2 页列表里哪个是自己的。
        root.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnZjsSave)
            .setOnClickListener { promptSave() }
        root.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnZjsAddAction)
            .setOnClickListener { promptAddAction() }

        /*
         * 订阅运行状态：翻到这一页就能看到"跑到哪一条、跑了几次"。
         *
         * 进度条要平滑，而状态只在动作边界更新（一次跳一大截），
         * 所以在跑的时候额外起一个 120ms 的定时器重画当前那一条。
         * 只刷一条，不做整表刷新。
         */
        host.lifecycleScope.launch {
            host.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var ticker: Job? = null
                var lastIndex = -2
                com.pvz2.auto.core.ScriptRunState.state.collect { st ->
                    adapter.updateRunState(st)
                    /*
                     * 自动滚到正在跑的那一条。
                     *
                     * 脚本一跑起来，当前动作往往在可见区之外 ——
                     * 用户盯着屏幕却看不到「识别 N 次」在涨，
                     * 就会以为进度显示没生效。
                     */
                    /*
                     * 失败时：顶部横幅说清楚第几条失败，并把视图停在那一行。
                     * 只在 running 时滚动的话，失败瞬间就没人管了，
                     * 用户还是得自己翻。
                     */
                    if (!st.running && st.failedIndex >= 0) {
                        tvSummary.text = "运行失败：第 ${st.failedIndex + 1} 条 —— ${st.message}"
                        if (st.failedIndex != lastIndex) {
                            lastIndex = st.failedIndex
                            runCatching {
                                (rvActions.layoutManager as? LinearLayoutManager)
                                    ?.scrollToPositionWithOffset(st.failedIndex, 0)
                            }
                        }
                    }
                    if (st.running && st.currentIndex >= 0 && st.currentIndex != lastIndex) {
                        lastIndex = st.currentIndex
                        runCatching {
                            (rvActions.layoutManager as? LinearLayoutManager)
                                ?.scrollToPositionWithOffset(
                                    st.currentIndex,
                                    (rvActions.height / 3).coerceAtLeast(0)
                                )
                                ?: rvActions.scrollToPosition(st.currentIndex)
                        }
                    }
                    if (st.running) {
                        if (ticker == null) {
                            ticker = launch {
                                while (true) {
                                    kotlinx.coroutines.delay(120)
                                    adapter.refreshProgress()
                                }
                            }
                        }
                    } else {
                        ticker?.cancel()
                        ticker = null
                    }
                }
            }
        }

        // 从脚本库打开另一份时，这一页可能还活着（翻页不保证销毁）。
        // 只靠 bind() 里那次 load() 不够 —— 它早就跑完了，界面还是旧脚本。
        // 所以订阅代号变化，变了就整体重画。
        host.lifecycleScope.launch {
            host.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // ★ 不用 drop(1)。
                //
                //   drop(1) 是为了"bind 之后不要多画一次"，但它会连
                //   **页面重新订阅时的当前值**一起丢掉：从脚本库点运行、
                //   页面刚好被重建时，那次 open() 就再也收不到 ——
                //   于是第 3 页一直显示上一份脚本，进度画到错的节点上。
                //   改成按文件名比对：内容一样就不重画，不一样就换。
                ZjsEditState.generation.collect {
                    val s = ZjsEditState.script ?: return@collect
                    navStack.clear()
                    expandedIndex = -1
                    showScript(ZjsEditState.fileName, s)
                }
            }
        }
        load()
    }

    /**
     * 添加一个动作。
     *
     * 列表按分组排：先能跑的，再暂时不能跑的。
     * 用户在安卓上看到一堆点了没反应的类型会以为程序坏了，
     * 所以把「安卓上暂不支持」的排在后面并标出来。
     */
    private fun promptAddAction(insertAt: Int = -1) {
        val cur = script ?: return

        // 可执行的排前面，同组内保持协议顺序
        val sorted = Z.ActionType.entries.sortedBy { if (it.executable) 0 else 1 }
        val labels = sorted.map { typeLabel(it) }.toTypedArray()

        androidx.appcompat.app.AlertDialog.Builder(host)
            .setTitle("添加什么动作")
            .setItems(labels) { dlg, which ->
                dlg.dismiss()
                // ★ 必须把插入位置传下去，否则「在上方插入」实际还是追加到末尾
                addAction(sorted[which], insertAt)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 动作类型在列表里的显示名：能跑的直接写，暂不支持的标出来。 */
    private fun typeLabel(type: Z.ActionType): String {
        val base = type.controlRunType?.let { c -> "${type.wire}·$c" } ?: type.wire
        return if (type.executable) base else "$base（暂不支持）"
    }

    /**
     * 按类型造一条新动作（位置类带默认坐标）。
     *
     * 抽出来是因为"添加"和"监听槽位里的替换"要造同一种东西 ——
     * 两处各写一份的话，改了一处忘了另一处，行为就会不一致。
     */
    private fun newAction(type: Z.ActionType): Z.Action {
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
        return Z.Action(
            type = type,
            posData = if (ZFieldEditor.expectedPosType(type) != null) pos else null,
            startPos = if (type == Z.ActionType.SWIPE) pos else null,
            endPos = if (type == Z.ActionType.SWIPE) pos else null
        )
    }

    private fun addAction(type: Z.ActionType, insertAt: Int = -1) {
        val cur = script ?: return

        val fresh = newAction(type)

        // 加在当前展开的那一条后面。全收起时加到末尾 ——
        // 用户刚看完某条就想在它后面加一条，比滚到底部合理。
        // 长按菜单里指定了位置就插在那儿；否则加在展开项之后 / 末尾。
        val at = when {
            insertAt >= 0 -> insertAt.coerceIn(0, currentActions().size)
            expandedIndex >= 0 -> expandedIndex + 1
            else -> currentActions().size
        }
        val list = currentActions().toMutableList()
        list.add(at, fresh)
        commitActions(list)
        adapter.submit(currentActions())
        expandedIndex = at
        adapter.notifyItemChanged(at)
        android.widget.Toast.makeText(
            host, "已添加「${type.wire}」", android.widget.Toast.LENGTH_SHORT
        ).show()
    }


    /**
     * 保存弹窗：显示存到哪、并让用户起名字。
     *
     * ## 为什么必须让用户起名
     *
     * 从示例或别人的脚本改出来的东西，文件名还是原来的。直接存下去，
     * 第 2 页列表里会出现两个看不出区别的条目，用户不知道哪个是自己的。
     *
     * ## 为什么把目录也显示出来
     *
     * 文件在应用私有目录（`Android/data/.../files/zjs`），用户在文件管理器里
     * 找不到。不写出来，他会以为存到了某个能找到的地方，然后找不到就以为没存上。
     * 要拿出去，第 2 页有「导出」。
     */
    private fun promptSave() {
        val dir = com.pvz2.auto.zjs.ZjsRunner.savedDir(host)
        val base = fileName.substringBeforeLast('.').ifBlank { "" }

        val input = android.widget.EditText(host).apply {
            setText(base)
            setSingleLine(true)
            hint = host.getString(R.string.zjs_save_name_hint)
            setPadding(40, 28, 40, 28)
        }

        androidx.appcompat.app.AlertDialog.Builder(host)
            .setTitle(R.string.zjs_save_title)
            .setMessage(host.getString(R.string.zjs_save_dir) + "：" + dir.absolutePath)
            .setView(input)
            .setPositiveButton(R.string.zjs_save) { _, _ ->
                val name = input.text?.toString().orEmpty().trim()
                if (name.isBlank()) {
                    android.widget.Toast.makeText(
                        host, host.getString(R.string.zjs_save_name_empty),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }
                // 名字里不能有路径分隔符 —— 用户可能顺手打个 "/"，
                // 那会写到别的目录去（或者直接失败）。
                val safe = name.replace('/', '_').replace('\\', '_')
                saveTo(java.io.File(dir, "$safe.zjs").absolutePath, displayName = safe)
            }
            .setNegativeButton(R.string.picker_cancel, null)
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
    private fun saveTo(path: String, displayName: String? = null) {
        val script = this.script ?: return
        val ok = ZScriptIO.save(script, path)
        if (ok) {
            // 记住新名字 —— 否则下次保存又回到旧名字，用户改的名白改了。
            displayName?.let {
                fileName = "$it.zjs"
                ZjsEditState.fileName = "$it.zjs"
            }
            // 通知第 2 页重列：刚存的东西要立刻出现在脚本库里，
            // 不然用户会以为保存失败了。
            ZjsEditState.markSaved()
            refreshSummary()
            android.widget.Toast.makeText(
                host, host.getString(R.string.zjs_saved, fileName), android.widget.Toast.LENGTH_SHORT
            ).show()
        } else {
            android.widget.Toast.makeText(
                host, host.getString(R.string.zjs_save_failed, "写入失败，看日志"), android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * 摘要行（名字 + 动作数）重画。
     *
     * 只改这一行文本，不重跑 [showScript] —— 那会把列表重新提交一遍，
     * 用户刚展开的卡片会全部收起，看起来像"保存把我的手劳动没了"。
     */
    private fun refreshSummary() {
        val s = script ?: return
        tvSummary.text = "${fileName} · ${s.actions.size} 个动作"
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
        /*
         * 换脚本这一次才重置视图状态（全收起 + 运行态清空）。
         *
         * 这两件事原来写在 adapter.submit() 里，于是**每次编辑**都会触发，
         * 把正在跑的进度显示清掉、把用户展开的动作合上。现在挪到这里：
         * 只有真的换了一份脚本，才需要"从零开始看"。
         */
        expandedIndex = -1
        adapter.resetRunState()
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

        /** 运行状态：界面靠它决定哪一条亮起来、进度条到哪、跑了几次。 */
        private var runState: com.pvz2.auto.core.ScriptRunState.State =
            com.pvz2.auto.core.ScriptRunState.State()

        /**
         * 只换数据，**不动视图状态**。
         *
         * ## 原来每次编辑都会清空运行进度
         *
         * 以前这里顺手做三件事：收起展开项、把 runState 重置成未运行、整表刷新。
         * 而**所有**编辑路径（改字段、拨开关、换类型、加动作、删动作…）最后都要调
         * submit() —— 于是脚本正在跑的时候，用户只要点一下任意字段：
         * 「跑到第几条 / 已经在等多久 / 进度条」全部回到空白，看起来像脚本停了；
         * 而且 expandedIndex 被清掉，用户正在改的那个动作自己合上了。
         *
         * 现在：submit 只管 items。展开项由各调用点自己维护（它们本来就设了
         * expandedIndex）。运行态只在**真的换了脚本**时重置 —— 见 [resetRunState]，
         * 由 [showScript] 调用。
         */
        fun submit(actions: List<Z.Action>?) {
            items = actions.orEmpty()
            notifyDataSetChanged()
        }

        /**
         * 运行态回到"未运行"。
         *
         * 只在切换脚本时调：不重置的话，打开另一个脚本时第 5 条会莫名亮着，
         * 那是上一个脚本的进度。编辑同一个脚本时**不能**调（见 [submit]）。
         */
        fun resetRunState() {
            runState = com.pvz2.auto.core.ScriptRunState.State()
        }

        /** 收到新状态：只重画"上一条 + 这一条"，别整表刷新。 */
        fun updateRunState(s: com.pvz2.auto.core.ScriptRunState.State) {
            val prev = runState.currentIndex
            runState = s
            if (prev >= 0 && prev < items.size) notifyItemChanged(prev)
            if (s.currentIndex in items.indices) notifyItemChanged(s.currentIndex)
        }

        /** 进度条要平滑走，靠定时重画当前这一条。 */
        /**
         * 每 120ms 刷新"正在跑的那一条"的进度。
         *
         * 跳过用户**正展开着**的那一行：重建视图会打断他正在拖的滑块
         * （展开态以前每次编辑都会被重置，所以这个组合碰不到；
         * 现在编辑会保持展开，就撞上了）。
         */
        fun refreshProgress() {
            val i = runState.currentIndex
            if (i in items.indices && i != expandedIndex) notifyItemChanged(i)
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
            private val llProgress: LinearLayout = v.findViewById(R.id.llActionProgress)
            private val pbAction: android.widget.ProgressBar = v.findViewById(R.id.pbAction)
            private val tvRunCount: TextView = v.findViewById(R.id.tvActionRunCount)

            fun bind(index: Int, action: Z.Action, expanded: Boolean) {
                tvIndex.text = (index + 1).toString()
                tvLabel.text = ZActionDescriber.title(action)

                // 失败标记要在角标之前算出来（角标要用它）
                val isFailed = runState.failedIndex == index

                // 角标用来标出"这条跑不了"，比藏在展开区里更显眼。
                tvBadge.text = when {
                    /*
                     * 失败的那一条必须一眼看见 —— 红字 + 顶部横幅。
                     * 否则用户只知道"停了"，不知道停在哪一条，
                     * 只能自己在几十条里翻。
                     */
                    isFailed -> "运行失败"
                    action.common.disabled -> "已禁用"
                    !action.type.executable -> "不支持"
                    action.common.vars.isNotEmpty() -> "含变量"
                    else -> ""
                }
                tvBadge.setTextColor(
                    if (isFailed) 0xFFC62828.toInt() else tvBadge.currentTextColor
                )

                llFields.removeAllViews()
                /*
                 * 运行进度。
                 *
                 * 正在跑的那一条按估算时长走进度；估不出来（候选等待，时长取决于
                 * 画面什么时候出现）走不确定进度 —— 一条永远停在 0 的进度条
                 * 比没有更糟。跑过的条数留记号，用户能确认"这条真的重复执行了"。
                 */
                val isCurrent = runState.running && runState.currentIndex == index
                val done = runState.counts[index] ?: 0L
                /*
                 * ★ 进度条**只出现在正在运行的那一条**上。
                 *
                 *   原来「跑过的条数」也留着一条满格进度条 + 「已 N 次」，
                 *   几十条动作的脚本一跑起来满屏都是条，反而看不出当前跑到哪 ——
                 *   进度条要回答的是「现在在哪」，不是「哪些跑过」。
                 */
                llProgress.visibility = if (isCurrent) View.VISIBLE else View.GONE
                if (isCurrent) {
                    /*
                     * 显示"这一条已经跑了多久"，不是"第几次"。
                     *
                     * 转换出来的脚本绝大多数是单程的（每个动作只执行一次），
                     * 所以"第 1 次"永远不变 —— 数字是对的，但一点信息都没有。
                     * 真正有用的是"它在这一条上卡了多久"。
                     *
                     * 真重复（跳转回来 / 执行次数 > 1）时才带次数前缀 ×N。
                     */
                    val elapsed = runState.actionStartedAt.let { st ->
                        if (st <= 0L) 0L
                        else (android.os.SystemClock.uptimeMillis() - st) / 1000
                    }
                    val p = runState.actionProgress()
                    if (p < 0f) pbAction.isIndeterminate = true
                    else {
                        pbAction.isIndeterminate = false
                        pbAction.progress = (p * pbAction.max).toInt()
                    }
                    /*
                     * 把"识别了几次"直接摆在节点上。
                     *
                     * 一条识别等上 392 次、27 秒是常态（Maa 的 timeout + rate_limit），
                     * 界面上如果只有"1.4s"在跳，用户分不清它是在拼命识别还是卡死。
                     */
                    tvRunCount.text = when {
                        runState.retryCount > 0 -> "识别 ${runState.retryCount} 次 · ${elapsed}s"
                        done > 0 -> "×${done + 1} · ${elapsed}s"
                        else -> "${elapsed}s"
                    }
                    // 原因太长，塞进这一行会把进度条挤没；它在第 2 页状态行和日志里都有。

                }

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
                    onRowMenu(index)
                    true
                }
            }

            /**
             * 一行字段。
             *
             * 用两个 TextView 而不是 RecyclerView：一个动作最多十几行，
             * 嵌一层 RecyclerView 得不偿失。
             */
            /**
             * 一行字段。
             *
             * ## 控件形状跟着数据形状走
             *
             * 原来十几行长得一模一样：灰标签 + 灰文字 + 弹窗。用户看不出
             * 「相似度」能拖、「禁用」是个开关、「模板」能截 —— 全都得点一下才知道。
             *
             * 现在：布尔给开关、百分比给滑块、枚举点开选、其余弹输入框。
             * 一眼就能看出这行是什么、该怎么动。
             */
            /**
             * 动作卡片里的一行。渲染交给 [buildRowView]，这里只决定"点了做什么"。
             */
            private fun makeRow(row: ZActionDescriber.Row, actionIndex: Int): View =
                buildRowView(
                    row = row,
                    imageData = imageDataFor(row.field, actionIndex),
                    onClick = {
                        when (row.kind) {
                            ZActionDescriber.Kind.ENTER -> onEnter(row, actionIndex)
                            ZActionDescriber.Kind.COORD ->
                                // 传**完整字段**而不是剥掉 ".x" 的前缀：
                                // 坐标字段不止 ".x" 一种（还有 limitPosX/limitPosY），
                                // 由 onPickPoint 自己判该写哪两个键。
                                onPickPoint(row.field, actionIndex)
                            ZActionDescriber.Kind.AREA -> onPickRect(row.field, actionIndex)
                            ZActionDescriber.Kind.IMAGE -> onPickTemplate(row.field, actionIndex)
                            else -> onEditRequested(row, actionIndex)
                        }
                    },
                    onToggle = { checked ->
                        commitFieldValue(row, actionIndex, if (checked) "是" else "否")
                    },
                    onPercent = { p -> commitFieldValue(row, actionIndex, "$p") }
                )

            /** 行尾的小图标按钮。⊕ / ✂ 这类，统一尺寸和颜色。 */
            private fun glyphButton(glyph: String, desc: String, onClick: () -> Unit): View =
                TextView(host).apply {
                    text = glyph
                    textSize = 15f
                    setPadding(dp(10), 0, dp(2), 0)
                    setTextColor(host.getColor(R.color.ink_faint))
                    contentDescription = desc
                    setOnClickListener { onClick() }
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
        previewFor(pos)
    }

    /** 预览命中位置。动作和条件共用 —— 判定必须和解算时完全一致。 */
    private fun previewFor(pos: Z.PosData?) {
        // 入口就记一行：预览"没反应"时，能立刻分清是没走到这里，
        // 还是走到了但匹配/绘制出了问题。
        android.util.Log.i(
            "ZjsPreview",
            "previewFor 进入：pos=${pos?.type} 有模板=${pos?.imageData != null} " +
                "文字=${pos?.text} 颜色=${pos?.color} 范围=${pos?.limitArea}"
        )
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
            frame = { com.pvz2.auto.service.VirtualDisplaySession.snapshotForMatch() },
            // Context 给 OCR 读 assets/ocr 模型用；预览必须和运行时同一套判定
            context = host
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
                initial = initial,
                frozenFrame = com.pvz2.auto.service.VirtualDisplaySession.previewFlow.value
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
            // 已有范围就带出来：用户可能只想看一眼，什么都不改就点确定。
            // 不带的话「确定」会报"先框出一个框"，等于逼他重框一遍。
            val existing = pos?.let { p ->
                com.pvz2.auto.zjs.ZPosResolver.searchRect(
                    p,
                    com.pvz2.auto.zjs.ZPosResolver.Screen(iw, ih, host.resources.displayMetrics.density)
                ).takeIf { p.limitArea != null }
            }
            com.pvz2.auto.core.PreviewPicker.arm(
                com.pvz2.auto.core.PreviewPicker.Mode.RECT,
                screenWidth = iw, screenHeight = ih,
                // 冻结当前帧：游戏在动，不冻结的话框永远对不准
                frozenFrame = com.pvz2.auto.service.VirtualDisplaySession.previewFlow.value,
                initialRect = existing
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
            // 先把这一帧固定下来：裁图、自检都必须用**同一张**，
            // 否则比较的是两个时刻的画面，分数永远到不了 100%。
            val frozen = com.pvz2.auto.service.VirtualDisplaySession.previewFlow.value
            com.pvz2.auto.core.PreviewPicker.arm(
                com.pvz2.auto.core.PreviewPicker.Mode.TEMPLATE,
                screenWidth = iw, screenHeight = ih,
                // 冻结当前帧：游戏在动，不冻结的话框永远对不准
                frozenFrame = frozen
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

            // 模板和范围一起写。
            //
            // 用户框的这一块**就是他要在哪里找** —— 模板和搜索范围本来就是
            // 同一个动作的两面。只写模板不写范围的话，运行时会在整屏上找，
            // 同一个图案在别处也能命中，于是点错地方。
            var updated = com.pvz2.auto.zjs.ZFieldEditor
                .applyImage(action, field, img) ?: action

            val pct = { v: Int, total: Int -> "%.2f%%".format(100.0 * v / total) }
            val area = "${pct(r.x, r.screenWidth)} ${pct(r.y, r.screenHeight)} " +
                "${pct(r.x + r.w, r.screenWidth)} ${pct(r.y + r.h, r.screenHeight)}"
            updated = com.pvz2.auto.zjs.ZFieldEditor
                .apply(updated, field.substringBeforeLast('.') + ".limitArea", area) ?: updated

            val list = currentActions().toMutableList()
            list[actionIndex] = updated
            commitActions(list)
            adapter.submit(currentActions())
            expandedIndex = actionIndex
            adapter.notifyItemChanged(actionIndex)

            /*
             * 自检：把刚做好的模板拿**同一张冻结帧**再匹配一次。
             *
             * 用户最困惑的是"我明明框的就是这个，怎么匹配不上"。原因有两种：
             * 我们的链路有问题（编码/通道/坐标换算），或者画面已经变了 ——
             * 两者在界面上长得一模一样。
             *
             * 同帧自检把两者分开：接近 100% ⇒ 链路没问题，低分只能怪画面变化；
             * 很低 ⇒ 是我们的 bug，当场就能发现。
             */
            /*
             * 双自检：一次说明"我们的链路对不对"，一次说明"画面变了多少"。
             *
             * - sameBmp：模板和帧取自**同一张**冻结帧。链路（PNG 编解码、通道
             *   顺序、坐标换算、ROI、matchTemplate）没问题时必须是 100%。
             *   不到 100% 就是我们的 bug。
             * - ring：模板和**当前帧**比。这才是运行时真正的分数，
             *   低于 sameBmp 的部分全部来自画面变化。
             */
            var sameBmp = -1.0
            var ringScore = -1.0
            runCatching {
                val p = updated.posData ?: return@runCatching
                val sr = com.pvz2.auto.zjs.ZPosResolver.searchRect(
                    p,
                    com.pvz2.auto.zjs.ZPosResolver.Screen(
                        iw, ih, host.resources.displayMetrics.density
                    )
                )

                // ① 同一张图内部对比
                if (frozen != null) {
                    val mat = org.opencv.core.Mat()
                    org.opencv.android.Utils.bitmapToMat(frozen, mat)
                    val probe = com.pvz2.auto.zjs.ZRealMatcher(
                        com.pvz2.auto.core.TemplateMatcher(),
                        frame = { mat }, context = host
                    )
                    probe.findImage(p, sr)
                    sameBmp = probe.lastScore
                }

                // ② 和当前帧比（运行时真正的分数）
                val snap = com.pvz2.auto.service.VirtualDisplaySession.snapshotForMatch()
                if (snap != null) {
                    val probe2 = com.pvz2.auto.zjs.ZRealMatcher(
                        com.pvz2.auto.core.TemplateMatcher(),
                        frame = { snap }, context = host
                    )
                    probe2.findImage(p, sr)
                    ringScore = probe2.lastScore
                }

                android.util.Log.i(
                    "ZjsSelfCheck",
                    "模板 ${crop.width}x${crop.height} 冻结帧 ${frozen?.width}x${frozen?.height} " +
                        "ROI ${sr[0]},${sr[1]},${sr[2]},${sr[3]} " +
                        "同帧=${"%.1f".format(sameBmp * 100)}% 当前帧=${"%.1f".format(ringScore * 100)}%"
                )
            }.onFailure { android.util.Log.e("ZjsSelfCheck", "自检异常", it) }

            val msg = when {
                sameBmp in 99.0..100.1 ->
                    "已截取 ${crop.width}×${crop.height}（链路自检 ${"%.0f".format(sameBmp * 100)}%，" +
                        "当前帧 ${"%.0f".format(ringScore * 100)}%）"
                sameBmp > 0 ->
                    "已截取，但同帧只有 ${"%.0f".format(sameBmp * 100)}% —— 这是 bug，请发日志"
                else -> "已截取 ${crop.width}×${crop.height}"
            }
            android.widget.Toast.makeText(host, msg, android.widget.Toast.LENGTH_LONG).show()

        }
    }

    /**
     * 在预览画面上取点，一次同时写回 X 和 Y。
     *
     * @param field 被点的那一行字段，形如 `位置.x` / `起点.x` / `位置.limitPosX`
     */
    /**
     * 从画面上取一个颜色。
     *
     * 复用现有的准星取点器（和位置取点同一套交互），拿到坐标后**直接读当前帧那一像素**，
     * 而不是让用户手输 `#RRGGBB`——手输要么靠截图去别处取（压缩后色值已经偏了），
     * 要么填错一个字符就永远匹配不到，而失败信息里只有"没找到这个颜色"。
     */
    private fun pickColorThen(
        initialX: Int?,
        initialY: Int?,
        onPicked: (hex: String, xPct: String, yPct: String) -> Unit
    ) {
        host.lifecycleScope.launch {
            val bitmap = com.pvz2.auto.service.VirtualDisplaySession.previewFlow.value
            if (bitmap == null || bitmap.isRecycled) {
                toast("没有画面可取样：先在首页把游戏启动到虚拟屏")
                return@launch
            }
            val deferred = host.lifecycleScope.async {
                com.pvz2.auto.core.PreviewPicker.result.firstOrNull {
                    it.mode == com.pvz2.auto.core.PreviewPicker.Mode.POINT
                }
            }
            val initial = if (initialX != null && initialY != null) {
                com.pvz2.auto.core.PreviewPicker.Point(initialX, initialY)
            } else null
            com.pvz2.auto.core.PreviewPicker.arm(
                com.pvz2.auto.core.PreviewPicker.Mode.POINT,
                initial = initial,
                frozenFrame = bitmap
            )
            toast("把准星拖到要取色的位置，点确定")
            val r = deferred.await() ?: return@launch
            val bmp = com.pvz2.auto.service.VirtualDisplaySession.previewFlow.value ?: bitmap
            val px = r.x.coerceIn(0, bmp.width - 1)
            val py = r.y.coerceIn(0, bmp.height - 1)
            val c = bmp.getPixel(px, py)
            val hex = "%02X%02X%02X".format(
                android.graphics.Color.red(c),
                android.graphics.Color.green(c),
                android.graphics.Color.blue(c)
            )
            onPicked(
                hex,
                "%.2f%%".format(100.0 * r.x / r.screenWidth),
                "%.2f%%".format(100.0 * r.y / r.screenHeight)
            )
        }
    }

    private fun onPickPoint(field: String, actionIndex: Int) {
        val cur = script ?: return
        val action = currentActions().getOrNull(actionIndex) ?: return

        /*
         * 「指定位置」是另一对键：`limitPosX` / `limitPosY`
         * （颜色位置里的"指定位置"，设置后会忽略 limitArea，FIELDS.md:213-214）。
         *
         * 它和 `x`/`y`（取色点）是**两套独立坐标**，不能混写。原来这里只认
         * `.x` 后缀、写回时又硬拼 `"$prefix.x"`，于是 limitPos 那两行
         * 就算挂了取点按钮，取到的值也只会写到 x/y 上 —— 用户看到的是
         * "点了取点没反应"（其实改的是另一对字段）。
         */
        /*
         * 取色：同一个准星，但拿到的点用来读像素而不是写坐标。
         * 顺便把「指定位置」写成这个点 —— 协议里 limitPos 设置后会忽略 limitArea，
         * 语义正是"只看这一个点是不是这个颜色"。
         */
        if (field.endsWith(".colorPick")) {
            val cPrefix = field.removeSuffix(".colorPick")
            val cPos = when (cPrefix) {
                "起点" -> action.startPos
                "终点" -> action.endPos
                else -> action.posData
            }
            val cx = cPos?.extra?.get("limitPosX")?.toString()?.toIntOrNull()
            val cy = cPos?.extra?.get("limitPosY")?.toString()?.toIntOrNull()
            pickColorThen(cx, cy) { hex, xp, yp ->
                var updated = ZFieldEditor.apply(action, "$cPrefix.color", hex) ?: action
                updated = ZFieldEditor.apply(updated, "${cPrefix}limitPosX", xp) ?: updated
                updated = ZFieldEditor.apply(updated, "${cPrefix}limitPosY", yp) ?: updated
                val l = currentActions().toMutableList()
                if (actionIndex !in l.indices) return@pickColorThen
                l[actionIndex] = updated
                commitActions(l)
                adapter.submit(currentActions())
                expandedIndex = actionIndex
                adapter.notifyItemChanged(actionIndex)
                toast("已取色 #$hex")
            }
            return
        }

        val limitPos = field.endsWith("limitPosX") || field.endsWith("limitPosY")
        // 带点的前缀：`位置.` / `起点.` / `终点.`（limitPos 的键名挂在它后面）
        val prefix = if (limitPos) field.substringBeforeLast("limitPos") else field.removeSuffix(".x")
        val xField = if (limitPos) "${prefix}limitPosX" else "$prefix.x"
        val yField = if (limitPos) "${prefix}limitPosY" else "$prefix.y"

        // 初始值：当前位置，没有就交给准星走默认（画面正中）
        val pos = when (prefix) {
            "起点" -> action.startPos
            "终点" -> action.endPos
            else -> action.posData
        }
        val rawX = if (limitPos) pos?.extra?.get("limitPosX")?.toString() else pos?.x?.raw
        val rawY = if (limitPos) pos?.extra?.get("limitPosY")?.toString() else pos?.y?.raw
        val initX = rawX?.toIntOrNull()
        val initY = rawY?.toIntOrNull()
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
                initial = initial,
                frozenFrame = com.pvz2.auto.service.VirtualDisplaySession.previewFlow.value
            )
            android.widget.Toast.makeText(
                host, "按住画面拖动准星，位置对了点确定", android.widget.Toast.LENGTH_SHORT
            ).show()

            val r = deferred.await() ?: return@launch

            // 写回：百分比两位小数，与协议一致（FIELDS.md 的推荐单位）
            val px = "%.2f%%".format(100.0 * r.x / r.screenWidth)
            val py = "%.2f%%".format(100.0 * r.y / r.screenHeight)

            var updated = ZFieldEditor.apply(action, xField, px) ?: action
            updated = ZFieldEditor.apply(updated, yField, py) ?: updated

            val list = currentActions().toMutableList()
            list[actionIndex] = updated
            commitActions(list)
            adapter.submit(currentActions())
            expandedIndex = actionIndex
            adapter.notifyItemChanged(actionIndex)
        }
    }

    private companion object {
        /**
         * 「位置周围条件」挂在位置对象上的键名。
         *
         * FIELDS.md:278 / m14040：`posData.filterByAroundConditionSet`，
         * 值是 AroundCondition 对象（不是数组），作用于该位置而不是整个动作。
         */
        const val AROUND_KEY = "filterByAroundConditionSet"
    }

}
