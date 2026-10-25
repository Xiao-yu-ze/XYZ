package com.pvz2.auto.ui.page

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.pvz2.auto.zjs.PvzChainModel
import com.pvz2.auto.zjs.Z

/**
 * 「种植顺序」面板：三条链 + 棋盘 + 条目列表。
 *
 * ## 它改的是什么
 *
 * 只改编辑期的条目集合；点「应用到脚本」时按 [PvzChainModel.rebuild]
 * 把三条链编译成平铺节点写回脚本 —— **运行格式没变**，
 * 执行器完全不认识"链"这个概念。
 *
 * ## 交互
 *
 * 选链 → 选动作（种植物还要选卡槽）→ 点棋盘格子（直接加到当前链）→
 * 列表里可上下移 / 换链 / 删除 → 「应用到脚本」。
 */
object PvzChainPanel {

    fun show(
        context: Context,
        actions: List<Z.Action>,
        onApply: (List<Z.Action>) -> Unit
    ) {
        val chains = HashMap<String, MutableList<Z.Action>>()
        PvzChainModel.group(actions).forEach { (k, v) -> chains[k] = v.toMutableList() }
        PvzChainModel.LABELS.forEach { (k, _) -> chains.getOrPut(k) { ArrayList() } }

        var curChain = PvzChainModel.ONCE
        var curType = Z.ActionType.PVZ_PLANT
        var curSlot = 1
        var loopRepeat = PvzChainModel.loopRepeatOf(actions)

        // 重画钩子：先声明后赋值，避免局部函数声明顺序问题
        var paintChains: () -> Unit = {}
        var paintTypes: () -> Unit = {}
        var paintSlots: () -> Unit = {}
        var paintList: () -> Unit = {}
        var paintHint: () -> Unit = {}

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 6))
        }
        val listBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val hint = TextView(context).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#90A4AE"))
        }

        // ---------------- 链选择
        val chainRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val chainBtns = LinkedHashMap<String, TextView>()
        for ((key, name) in PvzChainModel.LABELS) {
            val b = chip(context, name)
            chainBtns[key] = b
            b.setOnClickListener {
                curChain = key
                paintChains(); paintList(); paintHint()
            }
            chainRow.addView(b)
        }
        root.addView(chainRow)

        // ---------------- 动作类型
        val typeRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val typeBtns = ArrayList<Pair<Z.ActionType, TextView>>()
        val types = listOf(
            Z.ActionType.PVZ_PLANT to "种植物",
            Z.ActionType.PVZ_FEED to "喂豆",
            Z.ActionType.PVZ_SHOVEL to "铲除",
            Z.ActionType.PVZ_TAP_CELL to "点格子",
            Z.ActionType.PVZ_ARTIFACT to "神器"
        )
        for ((t, n) in types) {
            val b = chip(context, n)
            typeBtns.add(t to b)
            b.setOnClickListener {
                curType = t
                paintTypes(); paintHint()
            }
            typeRow.addView(b)
        }
        root.addView(typeRow)

        // ---------------- 卡槽
        val slotRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val slotBtns = ArrayList<Pair<Int, TextView>>()
        for (s in 1..8) {
            val b = chip(context, s.toString())
            slotBtns.add(s to b)
            b.setOnClickListener {
                curSlot = s
                paintSlots(); paintHint()
            }
            slotRow.addView(b)
        }
        root.addView(slotRow)

        // ---------------- 棋盘
        root.addView(
            PvzBoardPicker.buildGrid(
                context,
                selection = { null to null },
                onPick = { r, c ->
                    chains.getOrPut(curChain) { ArrayList() }.add(makeNode(curType, curSlot, r, c, curChain))
                    paintList(); paintHint()
                }
            )
        )

        root.addView(hint)
        root.addView(
            ScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 160)
                )
                addView(listBox)
            }
        )

        // ---------------- 循环次数
        val repeatRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 6), 0, 0)
        }
        repeatRow.addView(TextView(context).apply {
            text = "循环链重复次数（0 = 无限）："
            textSize = 12f
            setTextColor(Color.parseColor("#B0BEC5"))
        })
        val repeatEt = EditText(context).apply {
            setText(loopRepeat.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        repeatRow.addView(repeatEt)
        root.addView(repeatRow)

        // ---------------- 重画实现
        paintChains = {
            for ((k, b) in chainBtns) {
                val on = k == curChain
                b.setBackgroundColor(if (on) Color.parseColor("#2E7D32") else Color.parseColor("#263238"))
                b.setTextColor(if (on) Color.WHITE else Color.parseColor("#B0BEC5"))
            }
        }
        paintTypes = {
            for ((t, b) in typeBtns) {
                val on = t == curType
                b.setBackgroundColor(if (on) Color.parseColor("#1565C0") else Color.parseColor("#263238"))
                b.setTextColor(if (on) Color.WHITE else Color.parseColor("#B0BEC5"))
            }
            slotRow.visibility = if (curType == Z.ActionType.PVZ_PLANT) View.VISIBLE else View.GONE
        }
        paintSlots = {
            for ((s, b) in slotBtns) {
                val on = s == curSlot
                b.setBackgroundColor(if (on) Color.parseColor("#1565C0") else Color.parseColor("#263238"))
                b.setTextColor(if (on) Color.WHITE else Color.parseColor("#B0BEC5"))
            }
        }
        paintHint = {
            val from = when (curType) {
                Z.ActionType.PVZ_PLANT -> "卡槽$curSlot"
                Z.ActionType.PVZ_FEED -> "能量豆"
                Z.ActionType.PVZ_SHOVEL -> "铲子"
                Z.ActionType.PVZ_ARTIFACT -> "神器"
                else -> "直接点"
            }
            hint.text = "点格子 → 把「$from」加到「${PvzChainModel.label(curChain)}」链"
        }
        paintList = {
            listBox.removeAllViews()
            val items = chains.getOrPut(curChain) { ArrayList() }
            if (items.isEmpty()) {
                listBox.addView(TextView(context).apply {
                    text = "（这条链还是空的）"
                    textSize = 12f
                    setTextColor(Color.parseColor("#607D8B"))
                })
            } else {
                items.forEachIndexed { idx, a ->
                    val row = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(context, 3), 0, dp(context, 3))
                    }
                    row.addView(TextView(context).apply {
                        text = "${idx + 1}. ${describe(a)}"
                        textSize = 12f
                        setTextColor(Color.parseColor("#CFD8DC"))
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    row.addView(miniButton(context, "↑") {
                        if (idx > 0) {
                            items.add(idx - 1, items.removeAt(idx))
                            paintList()
                        }
                    })
                    row.addView(miniButton(context, "↓") {
                        if (idx < items.size - 1) {
                            items.add(idx + 1, items.removeAt(idx))
                            paintList()
                        }
                    })
                    row.addView(miniButton(context, "换链") {
                        val next = when (curChain) {
                            PvzChainModel.ONCE -> PvzChainModel.LOOP
                            PvzChainModel.LOOP -> PvzChainModel.END
                            else -> PvzChainModel.ONCE
                        }
                        val moved = items.removeAt(idx)
                        chains.getOrPut(next) { ArrayList() }.add(moved.inChain(next))
                        paintList()
                    })
                    row.addView(miniButton(context, "✕") {
                        items.removeAt(idx)
                        paintList()
                    })
                    listBox.addView(row)
                }
            }
        }

        paintChains(); paintTypes(); paintSlots(); paintHint(); paintList()

        AlertDialog.Builder(context)
            .setTitle("🌱 种植顺序")
            .setView(ScrollView(context).apply { addView(root) })
            .setPositiveButton("应用到脚本") { _, _ ->
                loopRepeat = repeatEt.text.toString().trim().toIntOrNull() ?: 0
                onApply(PvzChainModel.rebuild(actions, chains, loopRepeat))
                Toast.makeText(
                    context,
                    "已写入：单次 ${chains[PvzChainModel.ONCE]?.size ?: 0}，" +
                        "循环 ${chains[PvzChainModel.LOOP]?.size ?: 0}，" +
                        "收尾 ${chains[PvzChainModel.END]?.size ?: 0}",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------ 内部

    private fun makeNode(
        type: Z.ActionType,
        slot: Int,
        row: Int,
        col: Int,
        chain: String
    ): Z.Action = Z.Action(
        type = type,
        common = Z.ActionCommon(delay = "100", delayUnit = Z.TimeUnit.MILLISECONDS),
        extra = HashMap<String, Any?>().apply {
            put("pvzChain", chain)
            put("pvzRow", row.toString())
            put("pvzCol", col.toString())
            if (type == Z.ActionType.PVZ_PLANT) put("pvzSlot", slot.toString())
        }
    )

    private fun Z.Action.inChain(chain: String): Z.Action =
        copy(extra = extra.toMutableMap().apply { put("pvzChain", chain) })

    @SuppressLint("SetTextI18n")
    private fun describe(a: Z.Action): String {
        val row = a.extra["pvzRow"]?.toString().orEmpty()
        val col = a.extra["pvzCol"]?.toString().orEmpty()
        val cell = if (row.isNotEmpty() && col.isNotEmpty()) "${row}行${col}列" else "?行?列"
        return when (a.type) {
            Z.ActionType.PVZ_PLANT -> "种 卡槽${a.extra["pvzSlot"] ?: "?"} → $cell"
            Z.ActionType.PVZ_FEED -> "喂豆 → $cell"
            Z.ActionType.PVZ_SHOVEL -> "铲除 → $cell"
            Z.ActionType.PVZ_ARTIFACT -> "神器 → $cell"
            else -> "点格子 → $cell"
        }
    }

    private fun dp(context: Context, v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()

    private fun chip(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#B0BEC5"))
        setBackgroundColor(Color.parseColor("#263238"))
        setTypeface(typeface, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(0, dp(context, 34), 1f).apply {
            setMargins(dp(context, 2), dp(context, 2), dp(context, 2), dp(context, 2))
        }
    }

    private fun miniButton(context: Context, text: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(context).apply {
            this.text = text
            textSize = 11f
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(context, 6), 0, dp(context, 6), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 30)
            )
            setOnClickListener { onClick() }
        }
}
