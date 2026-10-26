package com.pvz2.auto.ui.page

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.pvz2.auto.zjs.PvzChainModel
import com.pvz2.auto.zjs.PvzCoords
import com.pvz2.auto.zjs.Z

/**
 * 第 5 页：**无尽模式**（布阵台）。
 *
 * ## 和自动精灵那套的区别
 *
 * 这一页不讲"动作节点"，只讲玩家脑子里的东西：把某个卡槽的植物摆到某格、
 * 什么时候喂豆/铲/丢神器、点波点加速，然后分单次 / 循环 / 收尾三段跑。
 * 底层仍是普通 .zjs 脚本（[PvzChainModel] 编译），所以运行/日志/监听全都复用。
 *
 * ## 视觉
 *
 * 主题是**草坪**：墨绿底 + 草皮格线；每个已放置的操作是一枚**贴片**
 * （种3 / 豆 / 铲 / 点 / 器 / 波 / 速），一眼能看出格子上有什么。
 * 阶段（前/中/后期）用一条横条表示，当前阶段点灯。
 *
 * ## 关卡变量
 *
 * `无尽关卡` 作为切换前中后期的依据：页面上可增减，生成脚本时写进运行时变量，
 * 收尾链触发条件可以用 `[关卡] >= N` 来表达。
 */
class PageEndless(
    private val host: android.app.Activity,
    private val root: View
) {

    // ---------------------------------------------------------------- 主题

    private val bg = Color.parseColor("#0E1512")
    private val cellEmpty = Color.parseColor("#16211C")
    private val cellLine = Color.parseColor("#24352C")
    private val grassA = Color.parseColor("#18271F")
    private val grassB = Color.parseColor("#1C2D24")
    private val textMain = Color.parseColor("#E6EFE9")
    private val textMuted = Color.parseColor("#7D9188")

    private val cPlant = Color.parseColor("#7CB342")
    private val cFeed = Color.parseColor("#F2B24C")
    private val cShovel = Color.parseColor("#8D9BA6")
    private val cTap = Color.parseColor("#4FA3D1")
    private val cArtifact = Color.parseColor("#B07CE8")
    private val cWave = Color.parseColor("#E06C5A")
    private val cSpeed = Color.parseColor("#5AC8A6")

    // ---------------------------------------------------------------- 状态

    /** 关卡（前中后期切换依据）。 */
    private var level = 1

    /** 当前要放置的操作：ActionType + 卡槽（种植物才有）。 */
    private var curType: Z.ActionType = Z.ActionType.PVZ_PLANT
    private var curSlot = 1

    /** 当前选中的神器预设键（拖拽类需要再点格子）。 */
    private var curArtifact: String = com.pvz2.auto.zjs.PvzCoords.ARTIFACTS.first().key
    private var curChain = PvzChainModel.ONCE

    /** 三条链的条目（编辑期数据）。 */

    /** 格子视图（refreshBoard 直接按行列取，不去遍历子 view）。 */
    private val cellViews: Array<Array<LinearLayout?>> =
        Array(PvzCoords.ROWS) { arrayOfNulls(PvzCoords.COLS) }

    private val board: Array<Array<MutableList<Z.Action>>> =
        Array(PvzCoords.ROWS) { Array(PvzCoords.COLS) { ArrayList<Z.Action>() } }

    private lateinit var levelText: TextView
    private lateinit var stageRow: LinearLayout
    private lateinit var boardBox: LinearLayout
    private lateinit var chainBox: LinearLayout
    private lateinit var hint: TextView
    private val typeChips = LinkedHashMap<String, TextView>()

    /** 普通关 / Boss 关 两个切换按钮。 */
    private val modeChips = LinkedHashMap<Boolean, TextView>()

    // ---------------------------------------------------------------- 入口

    fun bind() {
        android.util.Log.i("PageEndless", "bind 开始 root=${root.javaClass.simpleName}")
        val col = root as LinearLayout
        col.setBackgroundColor(bg)
        col.removeAllViews()

        col.addView(buildHeader())
        col.addView(buildBoard())
        col.addView(buildToolbar())
        val scroll = ScrollView(host).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            isFillViewport = true
            addView(buildChainArea())
        }
        col.addView(scroll)
        refreshAll()
        android.util.Log.i("PageEndless", "bind 完成，子视图 ${col.childCount} 个")
    }

    // ---------------------------------------------------------------- 顶部：关卡 + 阶段

    private fun buildHeader(): View {
        val box = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(6))
        }

        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(label("关卡", 13f, textMuted))
        row.addView(stepBtn("−") { if (level > 1) { level--; refreshAll() } })
        levelText = TextView(host).apply {
            textSize = 20f
            setTextColor(textMain)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(58), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        row.addView(levelText)
        row.addView(stepBtn("+") { level++; refreshAll() })
        row.addView(View(host).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        row.addView(label("变量名 无尽关卡", 11f, textMuted))
        box.addView(row)

        stageRow = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        box.addView(stageRow)

        // 普通关 / Boss 关：两套独立的链（boss 关 = 关卡 % 5 == 0）
        val modeRow = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        for (boss in listOf(false, true)) {
            val chip = TextView(host).apply {
                text = if (boss) "Boss 关（关卡 % 5 == 0）" else "普通关"
                textSize = 11f
                gravity = Gravity.CENTER
                background = roundRect(cellEmpty, 8f)
                setPadding(dp(8), dp(3), dp(8), dp(3))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                }
                setOnClickListener {
                    curBoss = boss
                    rebuildBoardIndex()
                    refreshAll()
                }
            }
            modeChips[boss] = chip
            modeRow.addView(chip)
        }
        box.addView(modeRow)
        return box
    }

    // ---------------------------------------------------------------- 棋盘

    private fun buildBoard(): View {
        boardBox = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(cellLine, 10f, stroke = true)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }

        // 列号表头
        val head = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(View(host).apply { layoutParams = LinearLayout.LayoutParams(dp(22), 1) })
        for (c in 1..PvzCoords.COLS) {
            head.addView(label("C$c", 10f, textMuted).apply {
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, dp(16), 1f)
            })
        }
        boardBox.addView(head)

        for (r in 1..PvzCoords.ROWS) {
            val line = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(2), 0, dp(2))
            }
            line.addView(label("R$r", 10f, textMuted).apply {
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(22), ViewGroup.LayoutParams.MATCH_PARENT)
            })
            for (c in 1..PvzCoords.COLS) {
                val cell = LinearLayout(host).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    // 草坪感：深浅交替
                    setBackgroundColor(if ((r + c) % 2 == 0) grassA else grassB)
                    layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                        setMargins(dp(1), 0, dp(1), 0)
                    }
                    setOnClickListener { placeAt(r, c) }
                }
                cellViews[r - 1][c - 1] = cell
                line.addView(cell)
            }
            boardBox.addView(line)
        }
        return boardBox
    }

    // ---------------------------------------------------------------- 工具条

    private fun buildToolbar(): View {
        val wrap = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        }

        // 第一行：种 1-8
        val row1 = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        for (s in 1..8) {
            val chip = chip("种$s", cPlant) {
                curType = Z.ActionType.PVZ_PLANT
                curSlot = s
                refreshToolbar()
                refreshHint()
            }
            typeChips["plant$s"] = chip
            row1.addView(chip)
        }
        wrap.addView(row1)

        // 第二行：喂豆 / 铲 / 点格子 / 神器 / 点波 / 点加速
        val row2 = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        val others = listOf(
            Triple("豆", cFeed, Z.ActionType.PVZ_FEED),
            Triple("铲", cShovel, Z.ActionType.PVZ_SHOVEL),
            Triple("点格子", cTap, Z.ActionType.PVZ_TAP_CELL),
            Triple("神器", cArtifact, Z.ActionType.PVZ_ARTIFACT),
            // 点波 / 点加速：坐标固定，不需要选格子
            Triple("点波", cWave, Z.ActionType.PVZ_WAVE),
            Triple("加速", cSpeed, Z.ActionType.PVZ_SPEED)
        )
        for ((name, color, t) in others) {
            val chip = chip(name, color) {
                when (t) {
                    // 点波：先问次数和间隔（连点催波很常见）
                    Z.ActionType.PVZ_WAVE -> promptWave()
                    // 加速：固定点，点一下直接入链
                    Z.ActionType.PVZ_SPEED -> {
                        chains.getOrPut(curChain) { ArrayList() }.add(makeGlobal(t))
                        refreshAll()
                        toast("已把「加速」加到${PvzChainModel.label(curChain)}链")
                    }
                    // 神器：先选是哪一个（不同神器用法不同）
                    Z.ActionType.PVZ_ARTIFACT -> promptArtifact()
                    else -> {
                        curType = t
                        refreshToolbar(); refreshHint()
                    }
                }
            }
            typeChips[t.wire] = chip
            row2.addView(chip)
        }
        wrap.addView(row2)

        hint = TextView(host).apply {
            textSize = 11f
            setTextColor(textMuted)
            setPadding(0, dp(4), 0, dp(2))
        }
        wrap.addView(hint)
        wrap.addView(
            com.google.android.material.button.MaterialButton(host).apply {
                text = "应用到脚本（生成 .zjs）"
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2) }
                setOnClickListener { applyScript() }
            }
        )
        return wrap
    }

    // ---------------------------------------------------------------- 三条链

    private fun buildChainArea(): View {
        chainBox = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(24))
        }
        return chainBox
    }

    // ---------------------------------------------------------------- 放置

    private fun placeAt(row: Int, col: Int) {
        if (curType == Z.ActionType.PVZ_WAVE || curType == Z.ActionType.PVZ_SPEED) {
            toast("点波/加速不用选格子，直接点上面那颗按钮")
            return
        }
        val a = Z.Action(
            type = curType,
            common = Z.ActionCommon(delay = "100", delayUnit = Z.TimeUnit.MILLISECONDS),
            extra = HashMap<String, Any?>().apply {
                put("pvzChain", curChain)
                put("pvzRow", row.toString())
                put("pvzCol", col.toString())
                if (curType == Z.ActionType.PVZ_PLANT) put("pvzSlot", curSlot.toString())
                if (curType == Z.ActionType.PVZ_ARTIFACT) put("pvzArtifact", curArtifact)
            }
        )
        chains.getOrPut(curChain) { ArrayList() }.add(a)
        board[row - 1][col - 1].add(a)
        refreshBoard()
        refreshChains()
    }

    private fun makeGlobal(
        t: Z.ActionType,
        extraSet: Map<String, Any?> = emptyMap()
    ): Z.Action = Z.Action(
        type = t,
        common = Z.ActionCommon(delay = "100", delayUnit = Z.TimeUnit.MILLISECONDS),
        extra = HashMap<String, Any?>().apply {
            put("pvzChain", curChain)
            putAll(extraSet)
        }
    )

    /** 点波参数：次数 + 间隔（毫秒）。 */
    private fun promptWave() {
        val box = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        fun field(hintText: String, def: String): android.widget.EditText {
            val et = android.widget.EditText(host).apply {
                setText(def)
                hint = hintText
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }
            box.addView(TextView(host).apply { text = hintText; textSize = 12f; setTextColor(textMuted) })
            box.addView(et)
            return et
        }
        val times = field("点几次（1-50）", "1")
        val gap = field("每次间隔（毫秒，0 = 连点）", "300")
        android.app.AlertDialog.Builder(host)
            .setTitle("点下一波")
            .setView(box)
            .setPositiveButton("加到${PvzChainModel.label(curChain)}链") { _, _ ->
                val t = (times.text.toString().trim().toIntOrNull() ?: 1).coerceIn(1, 50)
                val g = (gap.text.toString().trim().toIntOrNull() ?: 300).coerceIn(0, 10_000)
                chains.getOrPut(curChain) { ArrayList() }.add(
                    makeGlobal(
                        Z.ActionType.PVZ_WAVE,
                        mapOf("pvzWaveTimes" to t.toString(), "pvzWaveInterval" to g.toString())
                    )
                )
                refreshAll()
                toast("点波 ×$t，间隔 ${g}ms → ${PvzChainModel.label(curChain)}链")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 神器选择：6 个预设，点按类直接入链，拖拽类等用户点格子。 */
    private fun promptArtifact() {
        val list = com.pvz2.auto.zjs.PvzCoords.ARTIFACTS
        android.app.AlertDialog.Builder(host)
            .setTitle("用哪个神器")
            .setItems(list.map { it.label }.toTypedArray()) { _, which ->
                val ps = list[which]
                curArtifact = ps.key
                if (ps.use == com.pvz2.auto.zjs.PvzCoords.Use.TAP) {
                    // 选中即生效：不用选格子
                    chains.getOrPut(curChain) { ArrayList() }.add(
                        makeGlobal(Z.ActionType.PVZ_ARTIFACT, mapOf("pvzArtifact" to ps.key))
                    )
                    refreshAll()
                    toast("已把「${ps.label}」加到${PvzChainModel.label(curChain)}链")
                } else {
                    curType = Z.ActionType.PVZ_ARTIFACT
                    refreshToolbar(); refreshHint()
                    toast("「${ps.label}」需要点一个目标格子")
                }
            }
            .show()
    }

    /** 「应用脚本」：把布阵台编译成 .zjs 落到脚本目录。 */
    private fun applyScript() {
        val acts = buildActions()
        val hasPvz = acts.any { PvzChainModel.isPvz(it) }
        if (!hasPvz) {
            toast("还没有任何操作 —— 先在棋盘上摆几个")
            return
        }
        val name = "无尽模式_" +
            java.text.SimpleDateFormat("MMdd-HHmm", java.util.Locale.US)
                .format(java.util.Date()) + ".zjs"
        val f = java.io.File(com.pvz2.auto.zjs.ZjsRunner.savedDir(host), name)
        val ok = com.pvz2.auto.zjs.ZjsRunner.saveSaved(f, Z.Script(actions = acts))
        toast(if (ok) "已生成 $name（第 2 页可运行）" else "生成失败")
    }

    // ---------------------------------------------------------------- 刷新

    private fun refreshAll() {
        levelText.text = level.toString()
        refreshStages()
        refreshMode()
        refreshBoard()
        refreshToolbar()
        refreshChains()
        refreshHint()
    }

    /** 当前关卡落在哪个阶段（名字）。 */
    private fun stageOf(): String =
        stages.getOrNull(stageIndexOf(level))?.name ?: stages.first().name

    /**
     * 阶段：**用户自己定的**，可增可删，一个也行。
     *
     * 原来写死了前/中/后期三段 —— 等于只允许三套阵容。改成一张表：
     * 每个阶段有自己的名字和「到第几关为止」，最后一个阶段的上界是 ∞。
     *
     * 交互：**点一下 = 切到这一阶段**；改上界 / 改名 / 删除走长按菜单。
     */
    private class Stage(val id: Int, var name: String, var upTo: Int)

    private var stageSeq = 0

    private val stages = mutableListOf(
        Stage(stageSeq++, "前期", 30),
        Stage(stageSeq++, "中期", 80),
        Stage(stageSeq++, "后期", Int.MAX_VALUE)
    )

    /**
     * **每个阶段自己一套链**。
     *
     * 用户原话："前中后期的切换它脚本也要切换，它阶段不同操作也不同" ——
     * 所以三条链按阶段分开存：切到哪个阶段，棋盘和三链就显示哪个阶段的。
     */
    private val stageChains = HashMap<String, HashMap<String, MutableList<Z.Action>>>()

    /**
     * 每个阶段要切到第几编队：0 = 不变（不切换），1..6 = 切到对应编队。
     * 键是 Stage.id。
     */
    private val stageDeck = HashMap<Int, Int>()

    /** 当前在编辑「普通关」还是「Boss 关」的那套链。 */
    private var curBoss = false

    private fun chainKey(stageId: Int, boss: Boolean = curBoss) =
        "$stageId|" + if (boss) "boss" else "normal"

    private fun chainMap(): HashMap<String, MutableList<Z.Action>> {
        val id = stages.getOrNull(curStage)?.id ?: 0
        return stageChains.getOrPut(chainKey(id)) {
            HashMap<String, MutableList<Z.Action>>().apply {
                PvzChainModel.LABELS.forEach { (k, _) -> put(k, ArrayList()) }
            }
        }
    }

    /** 当前阶段的链（所有读写都走这里）。 */
    private val chains: HashMap<String, MutableList<Z.Action>>
        get() = chainMap()

    private var curStage = 0

    /** 关卡落在哪个阶段（返回下标）。 */
    private fun stageIndexOf(level: Int): Int {
        stages.forEachIndexed { i, s -> if (level <= s.upTo) return i }
        return stages.lastIndex
    }

    private fun stageText(s: Stage, index: Int): String =
        if (s.upTo == Int.MAX_VALUE) "${s.name} ${s.upTo.let { "∞" }}"
        else "${s.name} ≤${s.upTo}"

    private fun refreshStages() {
        stageRow.removeAllViews()
        for ((i, s) in stages.withIndex()) {
            val on = i == curStage
            val chip = TextView(host).apply {
                text = buildString {
                    append(if (i == stages.lastIndex) "${s.name} ∞" else "${s.name} ≤${s.upTo}")
                    val dk = stageDeck[s.id] ?: 0
                    if (dk > 0) append(" ·编队$dk")
                }
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(if (on) Color.parseColor("#0E1512") else textMuted)
                background = roundRect(if (on) cPlant else cellEmpty, 8f)
                setPadding(dp(8), dp(3), dp(8), dp(3))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                }
                // 点 = 切换；长按 = 改上界/改名/删除
                setOnClickListener {
                    curStage = i
                    refreshStages(); refreshHint(); refreshChains()
                }
                setOnLongClickListener {
                    stageMenu(i)
                    true
                }
            }
            stageRow.addView(chip)
        }
        // 末尾一颗「＋」：加阶段
        stageRow.addView(TextView(host).apply {
            text = "＋"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(cPlant)
            background = roundRect(cellEmpty, 8f)
            layoutParams = LinearLayout.LayoutParams(dp(38), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(2), 0, dp(2), 0)
            }
            setOnClickListener { addStage() }
        })
    }

    private fun addStage() {
        // 新阶段插在当前最后一段之前，上界 = 上一段上界 + 20（最后一段仍是 ∞）
        val boundary = stages.lastOrNull()?.upTo ?: 0
        val prev = if (boundary == Int.MAX_VALUE) {
            (stages.getOrNull(stages.lastIndex - 1)?.upTo ?: level) + 20
        } else boundary + 20
        stages.add(stages.lastIndex, Stage(stageSeq++, "阶段${stages.size + 1}", prev))
        curStage = stages.lastIndex - 1
        refreshStages(); refreshHint()
    }

    private fun stageMenu(index: Int) {
        val s = stages.getOrNull(index) ?: return
        val lastOne = index == stages.lastIndex
        val items = ArrayList<String>()
        items.add("改上界（现在是 ${if (lastOne) "∞" else "≤${s.upTo} 关"}）")
        items.add("改名（现在叫「${s.name}」）")
        val dk = stageDeck[s.id] ?: 0
        items.add("切换编队（现在：${if (dk == 0) "不变" else "编队 $dk"}）")
        if (stages.size > 1) items.add("删除这个阶段")

        android.app.AlertDialog.Builder(host)
            .setTitle(s.name)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> editStageBound(index)
                    1 -> editStageName(index)
                    2 -> editStageDeck(index)
                    3 -> {
                        stageChains.remove(chainKey(stages[index].id, false))
                        stageChains.remove(chainKey(stages[index].id, true))
                        stages.removeAt(index)
                        if (curStage >= stages.size) curStage = stages.lastIndex
                        refreshStages(); refreshHint()
                    }
                }
            }
            .show()
    }

    private fun editStageBound(index: Int) {
        val s = stages.getOrNull(index) ?: return
        if (index == stages.lastIndex) {
            toast("最后一个阶段没有上界（∞）")
            return
        }
        val et = android.widget.EditText(host).apply {
            setText(if (s.upTo == Int.MAX_VALUE) "" else s.upTo.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "到第几关为止（含）"
        }
        android.app.AlertDialog.Builder(host)
            .setTitle("「${s.name}」的上界")
            .setView(et)
            .setPositiveButton("确定") { _, _ ->
                val v = et.text.toString().trim().toIntOrNull() ?: return@setPositiveButton
                s.upTo = v
                // 保持递增：后面的阶段不能比它小
                for (i in index + 1 until stages.size - 1) {
                    if (stages[i].upTo < v) stages[i].upTo = v + (i - index) * 20
                }
                refreshStages(); refreshHint()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 选「进入这个阶段时切到第几编队」。0 = 不变。 */
    private fun editStageDeck(index: Int) {
        val st = stages.getOrNull(index) ?: return
        val cur = stageDeck[st.id] ?: 0
        val names = (0..com.pvz2.auto.zjs.PvzCoords.DECK_COUNT).map {
            if (it == 0) "不变（不切换编队）" else "编队 $it"
        }
        android.app.AlertDialog.Builder(host)
            .setTitle("「${st.name}」进入时切到哪个编队")
            .setSingleChoiceItems(names.toTypedArray(), cur) { dlg, which ->
                if (which == 0) stageDeck.remove(st.id) else stageDeck[st.id] = which
                dlg.dismiss()
                refreshStages()
                toast(if (which == 0) "「${st.name}」不切换编队" else "「${st.name}」→ 编队 $which")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun editStageName(index: Int) {
        val s = stages.getOrNull(index) ?: return
        val et = android.widget.EditText(host).apply { setText(s.name) }
        android.app.AlertDialog.Builder(host)
            .setTitle("阶段名")
            .setView(et)
            .setPositiveButton("确定") { _, _ ->
                val v = et.text.toString().trim()
                if (v.isNotEmpty()) s.name = v
                refreshStages(); refreshHint()
            }
            .setNegativeButton("取消", null)
            .show()
    }


    /** 重画「普通关 / Boss 关」两个按钮的选中态。 */
    private fun refreshMode() {
        for ((boss, chip) in modeChips) {
            val on = boss == curBoss
            chip.setTextColor(if (on) Color.parseColor("#0E1512") else textMuted)
            chip.background = roundRect(if (on) cWave else cellEmpty, 8f)
        }
    }

    private fun refreshBoard() {
        for (r in 1..PvzCoords.ROWS) {
            for (c in 1..PvzCoords.COLS) {
                val cell = cellViews[r - 1][c - 1] ?: continue
                cell.removeAllViews()
                val items = board[r - 1][c - 1]
                if (items.isEmpty()) continue
                // 一格里可能叠多个操作：竖着排，最多显示 3 个 + "…"
                for (a in items.take(3)) {
                    cell.addView(token(a))
                }
                if (items.size > 3) {
                    cell.addView(label("+${items.size - 3}", 9f, textMuted))
                }
            }
        }
    }

    private fun refreshToolbar() {
        for ((key, chip) in typeChips) {
            val on = when {
                key.startsWith("plant") -> curType == Z.ActionType.PVZ_PLANT && key == "plant$curSlot"
                else -> key == curType.wire
            }
            chip.alpha = if (on) 1f else 0.45f
            chip.setTextColor(if (on) Color.parseColor("#0E1512") else Color.WHITE)
        }
    }

    private fun refreshHint() {
        val what = when (curType) {
            Z.ActionType.PVZ_PLANT -> "种 卡槽$curSlot"
            Z.ActionType.PVZ_FEED -> "喂豆"
            Z.ActionType.PVZ_SHOVEL -> "铲除"
            Z.ActionType.PVZ_ARTIFACT -> "使用神器"
            Z.ActionType.PVZ_WAVE -> "点波"
            Z.ActionType.PVZ_SPEED -> "点加速"
            else -> "点格子"
        }
        hint.text = "点棋盘格子 → 把「$what」加到「${PvzChainModel.label(curChain)}」链"
    }

    private fun refreshChains() {
        chainBox.removeAllViews()
        for ((key, name) in PvzChainModel.LABELS) {
            val items = chains.getOrPut(key) { ArrayList() }
            val on = key == curChain

            val head = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(6), dp(8), dp(6))
                background = roundRect(if (on) Color.parseColor("#1E2E24") else cellEmpty, 8f)
                setOnClickListener {
                    curChain = key
                    refreshChains(); refreshHint()
                }
            }
            head.addView(label("$name（${items.size}）", 13f, if (on) textMain else textMuted).apply {
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            // 收尾链：显示触发条件（循环 → 收尾 的判断依据）
            if (key == PvzChainModel.END) {
                head.addView(label(if (endCondition == null) "触发：未设" else "触发：已设", 11f, cWave))
            }
            chainBox.addView(head)

            if (items.isEmpty()) {
                chainBox.addView(label("  （空）", 11f, textMuted).apply { setPadding(dp(14), dp(2), 0, dp(6)) })
                continue
            }
            items.forEachIndexed { idx, a ->
                chainBox.addView(chainRow(key, idx, a))
            }
        }
        // 收尾触发条件入口
        chainBox.addView(
            TextView(host).apply {
                text = if (endCondition == null) "⚑ 设「循环 → 收尾」的判断依据" else "⚑ 收尾判据：已设置（点改）"
                textSize = 12f
                setTextColor(cWave)
                setPadding(dp(8), dp(10), 0, dp(6))
                setOnClickListener { pickEndCondition() }
            }
        )
    }

    /** 收尾触发条件（循环链什么时候跳出到收尾）。 */
    private var endCondition: Z.Condition? = null

    private fun pickEndCondition() {
        val names = listOf(
            "关卡数达到阈值（用变量 无尽关卡）",
            "出现指定文字（如「挑战成功」）",
            "出现指定图片（自己截图）",
            "不设（循环一直跑）"
        )
        android.app.AlertDialog.Builder(host)
            .setTitle("循环 → 收尾 的判断依据")
            .setItems(names.toTypedArray()) { _, which ->
                endCondition = when (which) {
                    0 -> Z.Condition(
                        type = Z.ConditionType.JS_EXPRESSION,
                        extra = HashMap<String, Any?>().apply {
                            val bound = stages.getOrNull(curStage)?.upTo?.takeIf { it != Int.MAX_VALUE } ?: level
                            put("expression", "Number(zdjl.getVar('无尽关卡')||0) >= $bound")
                        }
                    )
                    1 -> Z.Condition(
                        type = Z.ConditionType.TEXT,
                        posData = Z.PosData(type = Z.PosType.TEXT),
                        extra = HashMap<String, Any?>().apply { put("text", "挑战成功") }
                    )
                    2 -> Z.Condition(
                        type = Z.ConditionType.IMAGE,
                        posData = Z.PosData(type = Z.PosType.IMAGE)
                    )
                    else -> null
                }
                if (which in 1..2) {
                    toast("这个判据要在脚本编辑器里补具体文字/图片")
                }
                refreshChains()
            }
            .show()
    }

    /** 一条链里的一个条目：长按可拖动排序。 */
    @SuppressLint("ClickableViewAccessibility")
    private fun chainRow(chainKey: String, idx: Int, a: Z.Action): View {
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(5), dp(8), dp(5))
        }
        row.addView(token(a))
        row.addView(label(" " + describe(a), 12f, textMain).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(host).apply {
            text = "✕"
            textSize = 13f
            setTextColor(textMuted)
            setPadding(dp(10), dp(2), dp(4), dp(2))
            setOnClickListener {
                chains[chainKey]?.removeAt(idx)
                rebuildBoardIndex()
                refreshAll()
            }
        })
        /*
         * 长按拖动排序（不再用 ↑↓ 按钮）。
         *
         * 用系统 drag-and-drop：长按给个"拿起"的反馈，拖到别的条目上就插到它前面。
         * 不引入 ItemTouchHelper/RecyclerView，因为这一页是手搭的普通 View 树。
         */
        row.setOnLongClickListener { v ->
            dragPayload = chainKey to idx
            v.alpha = 0.4f
            v.startDragAndDrop(null, View.DragShadowBuilder(v), null, 0)
            true
        }
        row.setOnDragListener { v, e ->
            when (e.action) {
                android.view.DragEvent.ACTION_DRAG_ENTERED -> {
                    v.setBackgroundColor(Color.parseColor("#243B2C")); true
                }
                android.view.DragEvent.ACTION_DRAG_EXITED -> {
                    v.setBackgroundColor(Color.TRANSPARENT); true
                }
                android.view.DragEvent.ACTION_DROP -> {
                    v.setBackgroundColor(Color.TRANSPARENT)
                    val payload = dragPayload
                    if (payload != null && payload.first == chainKey && payload.second != idx) {
                        val list = chains[chainKey]
                        if (list != null) {
                            val moved = list.removeAt(payload.second)
                            list.add(idx.coerceIn(0, list.size), moved)
                            rebuildBoardIndex()
                            refreshAll()
                        }
                    }
                    true
                }
                else -> true
            }
        }
        return row
    }

    private var dragPayload: Pair<String, Int>? = null

    /** 改过链的顺序后，棋盘索引按链重建（棋盘只显示，顺序以链为准）。 */
    private fun rebuildBoardIndex() {
        for (r in 0 until PvzCoords.ROWS) for (c in 0 until PvzCoords.COLS) {
            board[r][c].clear()
        }
        for ((_, list) in chains) {
            for (a in list) {
                val r = a.extra["pvzRow"]?.toString()?.toIntOrNull() ?: continue
                val c = a.extra["pvzCol"]?.toString()?.toIntOrNull() ?: continue
                if (r in 1..PvzCoords.ROWS && c in 1..PvzCoords.COLS) board[r - 1][c - 1].add(a)
            }
        }
    }

    // ---------------------------------------------------------------- 贴片

    private fun colorOf(t: Z.ActionType): Int = when (t) {
        Z.ActionType.PVZ_PLANT -> cPlant
        Z.ActionType.PVZ_FEED -> cFeed
        Z.ActionType.PVZ_SHOVEL -> cShovel
        Z.ActionType.PVZ_TAP_CELL -> cTap
        Z.ActionType.PVZ_ARTIFACT -> cArtifact
        Z.ActionType.PVZ_WAVE -> cWave
        else -> cSpeed
    }

    private fun tokenText(a: Z.Action): String = when (a.type) {
        Z.ActionType.PVZ_PLANT -> "种${a.extra["pvzSlot"] ?: "?"}"
        Z.ActionType.PVZ_FEED -> "豆"
        Z.ActionType.PVZ_SHOVEL -> "铲"
        Z.ActionType.PVZ_TAP_CELL -> "点"
        Z.ActionType.PVZ_ARTIFACT -> "器"
        Z.ActionType.PVZ_WAVE -> "波"
        else -> "速"
    }

    private fun token(a: Z.Action): TextView = TextView(host).apply {
        text = tokenText(a)
        textSize = 9f
        gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#0E1512"))
        setTypeface(typeface, Typeface.BOLD)
        background = roundRect(colorOf(a.type), 4f)
        setPadding(dp(3), dp(1), dp(3), dp(1))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(2), 0, 0, 0) }
    }

    private fun describe(a: Z.Action): String {
        val r = a.extra["pvzRow"]?.toString()
        val c = a.extra["pvzCol"]?.toString()
        val cell = if (r != null && c != null) " → ${r}行${c}列" else ""
        return when (a.type) {
            Z.ActionType.PVZ_PLANT -> "种 卡槽${a.extra["pvzSlot"] ?: "?"}$cell"
            Z.ActionType.PVZ_FEED -> "喂豆$cell"
            Z.ActionType.PVZ_SHOVEL -> "铲除$cell"
            Z.ActionType.PVZ_ARTIFACT -> "神器$cell"
            Z.ActionType.PVZ_WAVE -> "点下一波"
            Z.ActionType.PVZ_SPEED -> "点加速"
            else -> "点格子$cell"
        }
    }

    // ---------------------------------------------------------------- 小工具

    private fun dp(v: Int): Int = (v * host.resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float, color: Int): TextView = TextView(host).apply {
        this.text = text
        textSize = size
        setTextColor(color)
    }

    private fun stepBtn(text: String, onClick: () -> Unit): TextView = TextView(host).apply {
        this.text = text
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(textMain)
        background = roundRect(cellEmpty, 8f)
        layoutParams = LinearLayout.LayoutParams(dp(34), dp(30)).apply {
            setMargins(dp(4), 0, dp(4), 0)
        }
        setOnClickListener { onClick() }
    }

    private fun chip(text: String, color: Int, onClick: () -> Unit): TextView = TextView(host).apply {
        this.text = text
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        background = roundRect(color, 8f)
        setPadding(dp(4), dp(6), dp(4), dp(6))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(2), dp(2), dp(2), dp(2))
        }
        setOnClickListener { onClick() }
    }

    private fun roundRect(color: Int, radiusDp: Float, stroke: Boolean = false): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp.toInt()).toFloat()
            if (stroke) {
                setStroke(dp(1), color)
                setColor(Color.TRANSPARENT)
            } else {
                setColor(color)
            }
        }

    private fun toast(msg: String) {
        Toast.makeText(host, msg, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- 对外

    /** 每多少个游戏动作自动插一次「识别当前状态」。 */
    private var checkEvery = 10

    /** 自定义的状态文字候选（预置之外再加的）。 */
    private val stateChecks = mutableListOf<String>()

    /** 启用的预置识别（默认三个全开：结算 / 开始战斗 / 补给入口）。 */
    private val usePresets: MutableSet<String> =
        com.pvz2.auto.zjs.PvzRecognizers.PRESETS.map { it.key }.toMutableSet()
    /**
     * 把布阵台编译成一份能跑的脚本。
     *
     * 结构照 MAA 那套实际跑法（用户确认过）：
     *
     * ```text
     * 设置变量 无尽关卡 = N
     * :轮询
     *   状态轮询（等待候选，三个预置识别）
     *     命中「开始战斗」→ 跳 进关
     *     命中「补给/BOSS入口」→ 跳 补给
     *     命中「结算·继续挑战」→ 跳 结算
     *   :进关
     *     每个阶段一段（条件：关卡落在本阶段）→ 单次链 + 循环链
     *     跳回 轮询
     *   :补给
     *     点「查看」→（分类选择待 UI 补）→ 跳回 轮询
     *   :结算
     *     无尽关卡 +1 → 跳回 轮询
     * ```
     *
     * 用户原话："+1 之后是回到轮询容器重来一遍"；"每 5 的倍数关卡前会遇到补给，
     * 5 的倍数那关是 boss 关，选完补给进 boss 的选卡界面"；"加速要不要内建
     * 由用户决定"。
     */
    fun buildActions(): List<Z.Action> {
        val out = ArrayList<Z.Action>()
        out.add(setVarNode("无尽关卡", level.toString()))
        out.add(jumpLabel("轮询"))
        out.add(statePollNode())
        out.add(jumpLabel("进关"))


        // 每个阶段一段，条件按关卡变量判
        var prevBound = 0
        var pending = 0
        for (st in stages) {
          for (boss in listOf(false, true)) {
            val m = stageChains[chainKey(st.id, boss)]
            val once = m?.get(PvzChainModel.ONCE).orEmpty()
            val loop = m?.get(PvzChainModel.LOOP).orEmpty()
            if (once.isNotEmpty() || loop.isNotEmpty()) {
                val body = ArrayList<Z.Action>()
                /*
                 * 阶段开始的这一关，先切编队（用户："只在阶段切换那一关切一次，
                 * 比如前期 1-51 就在第 1 关切，中期 52-103 就在 52 关切"）。
                 *
                 * 用「关卡 == 本段起始关」当条件，所以整个阶段里只有那一关会执行；
                 * 选「不变」就不生成这个节点。
                 */
                val deckNo = stageDeck[st.id] ?: 0
                if (deckNo > 0) {
                    body.add(
                        Z.Action(
                            type = Z.ActionType.PVZ_SWITCH_DECK,
                            common = Z.ActionCommon(
                                delay = "100",
                                condition = stageStartCondition(prevBound + 1, st.name)
                            ),
                            extra = HashMap<String, Any?>().apply {
                                put("pvzDeck", deckNo.toString())
                            }
                        )
                    )
                }
                for (a in once) {
                    body.add(chained(a, PvzChainModel.ONCE))
                    if (++pending % checkEvery == 0) body.add(stateCheckNode())
                }
                if (loop.isNotEmpty()) {
                    val kids = ArrayList<Z.Action>()
                    for (a in loop) {
                        kids.add(chained(a, PvzChainModel.LOOP))
                        if (++pending % checkEvery == 0) kids.add(stateCheckNode())
                    }
                    /*
                     * 循环 → 收尾 的判定**由程序内建**，不让用户配。
                     *
                     * 用户原话："从循环链到收尾链的条件也是我们自己设置，
                     * 不要用户设置"。逻辑照 MAA：战斗链尾的 next 首项是
                     * 「识别结算」—— 认出"继续挑战"就跳出循环。
                     * 我们的等价写法：一个带条件的「跳转到 :收尾」节点，
                     * 条件不满足时它自己会被跳过，循环继续。
                     */
                    kids.add(settleJumpNode())
                    body.add(
                        Z.Action(
                            type = Z.ActionType.RUN_MULTI,
                            scriptSet = kids,
                            common = Z.ActionCommon(repeatCount = 0)
                        )
                    )
                }
                out.add(
                    Z.Action(
                        type = Z.ActionType.RUN_MULTI,
                        scriptSet = body,
                        common = Z.ActionCommon(
                            delay = "0",
                            condition = rangeCondition(prevBound, st.upTo, st.name, boss)
                        )
                    )
                )
            }
          }
            prevBound = st.upTo
        }
        out.add(jumpToNode("轮询"))

        /*
         * 补给段：整条链 1:1 复刻 MAA Supply.json
         * （查看 → 5 分类候选 → 兜底点第一个卡片 → 确定1 → [提示|确定3] → 确定3 → 确定4）
         */
        out.add(jumpLabel("补给"))
        out.addAll(com.pvz2.auto.zjs.PvzSupplyChain.build())
        out.add(jumpToNode("轮询"))

        // 结算段：关卡 +1 后回轮询重来
        out.add(jumpLabel("结算"))
        out.add(jsNode("zdjl.setVar('无尽关卡', Number(zdjl.getVar('无尽关卡')||0) + 1); log('关卡 +1 → ' + zdjl.getVar('无尽关卡'));"))
        out.add(jumpToNode("轮询"))

        // 收尾段：由循环里的 settleJumpNode() 跳进来
        out.add(jumpLabel("收尾"))
        val lastEnd = stages.lastOrNull()?.let { stageChains[chainKey(it.id)] }
            ?.get(PvzChainModel.END).orEmpty()
        for (a in lastEnd) out.add(chained(a, PvzChainModel.END))
        return out
    }

    /** 循环里的「认到结算就跳收尾」节点（条件不满足会被跳过，循环继续）。 */
    private fun settleJumpNode(): Z.Action {
        val settle = com.pvz2.auto.zjs.PvzRecognizers.PRESETS.first { it.key == "settle" }
        return Z.Action(
            type = Z.ActionType.CONTROL_JUMP,
            common = Z.ActionCommon(
                desc = "结算→收尾",
                delay = "0",
                condition = settle.build()
            ),
            extra = HashMap<String, Any?>().apply { put("jumpToPosition", "收尾") }
        )
    }

    /** 「状态轮询」：三个预置识别按顺序判，命中就跳到对应段。 */
    private fun statePollNode(): Z.Action {
        val cands = org.json.JSONArray()
        fun add(presetKey: String, target: String, cond: Z.Condition? = null) {
            val c = cond
                ?: com.pvz2.auto.zjs.PvzRecognizers.PRESETS.first { it.key == presetKey }.build()
            cands.put(
                org.json.JSONObject().apply {
                    put("condition", c.toJson())
                    put("jumpTo", target)
                }
            )
        }
        // 开始战斗：模板优先，认不出退 OCR（用户要求）
        add("start", "进关", com.pvz2.auto.zjs.PvzRecognizers.startBattleAny(host))
        add("supply", "补给")
        add("settle", "结算")
        val o = org.json.JSONObject().apply {
            put("type", "等待候选")
            put("delay", "0")
            put("desc", "状态轮询")
            // 用户要求：节点间隔与运行时间都 100ms；进关要快，别拖慢喂豆打断
            put("waitIntervalMs", 100)
            put("waitTimeoutMs", 100)
            put("candidates", cands)
        }
        return Z.Action.parse(o)!!
    }

    private fun jumpLabel(id: String): Z.Action = Z.Action(
        type = Z.ActionType.CONTROL_SET_JUMP_ID,
        common = Z.ActionCommon(jumpId = id, delay = "0")
    )

    private fun jumpToNode(target: String): Z.Action = Z.Action(
        type = Z.ActionType.CONTROL_JUMP,
        common = Z.ActionCommon(delay = "0"),
        extra = HashMap<String, Any?>().apply { put("jumpToPosition", target) }
    )

    private fun setVarNode(name: String, value: String): Z.Action = Z.Action(
        type = Z.ActionType.SET_VARS,
        common = Z.ActionCommon(delay = "0"),
        extra = HashMap<String, Any?>().apply { put("vars", org.json.JSONArray().put(varJson(name, value))) }
    )

    private fun jsNode(code: String): Z.Action = Z.Action(
        type = Z.ActionType.RUN_JS,
        common = Z.ActionCommon(delay = "0"),
        extra = HashMap<String, Any?>().apply { put("jsCode", code) }
    )


    private fun varJson(name: String, value: String) = org.json.JSONObject().apply {
        put("name", name)
        put(
            "value",
            org.json.JSONObject().apply {
                put("varType", "number")
                put("valueExp", value)
            }
        )
    }

    /** 「正好是阶段起始的那一关」的条件（用来只切一次编队）。 */
    private fun stageStartCondition(startLevel: Int, name: String): Z.Condition =
        Z.Condition(
            type = Z.ConditionType.JS_EXPRESSION,
            desc = "阶段「$name」起始关 = $startLevel",
            extra = HashMap<String, Any?>().apply {
                put(
                    "expression",
                    "Number(zdjl.getVar('无尽关卡')||0) == $startLevel"
                )
            }
        )

    /** 「关卡落在这个阶段」的条件。 */
    private fun rangeCondition(
        prevBound: Int,
        bound: Int,
        name: String,
        boss: Boolean = false
    ): Z.Condition =
        Z.Condition(
            type = Z.ConditionType.JS_EXPRESSION,
            desc = "阶段：$name",
            extra = HashMap<String, Any?>().apply {
                val hi = if (bound == Int.MAX_VALUE) "Number.MAX_SAFE_INTEGER" else bound.toString()
                // boss 关 = 关卡 % 5 == 0（用户确认：补给之后进的就是 boss 关）
                val bossPart = if (boss) " && L % 5 == 0" else " && L % 5 != 0"
                put(
                    "expression",
                    "var L=Number(zdjl.getVar('无尽关卡')||0); L > $prevBound && L <= $hi$bossPart"
                )
            }
        )

    /**
     * 「识别当前状态」节点：一个等待候选，候选就是 [stateChecks] 里的文字。
     *
     * 用户原话："把所有的识别全部封装到一个节点的候选列表里面" —— 所以
     * 这里生成的是**同一个形状**的节点，改一处（stateChecks）就全变。
     */
    private fun chained(a: Z.Action, chain: String): Z.Action =
        a.copy(extra = a.extra.toMutableMap().apply { put("pvzChain", chain) })

    private fun stateCheckNode(): Z.Action {
        // 用 JSON 构造再交给解析器：候选是模型的内部类，直接 new 容易踩可见性；
        // 走 parse 这条路读文件和落盘用的是同一套逻辑。
        val cands = org.json.JSONArray()
        // 预置识别（结算 / 开始战斗 / 补给入口）
        for (ps in com.pvz2.auto.zjs.PvzRecognizers.PRESETS) {
            if (ps.key !in usePresets) continue
            cands.put(
                org.json.JSONObject().apply {
                    put("condition", ps.build().toJson())
                }
            )
        }
        for (t in stateChecks) {
            cands.put(
                org.json.JSONObject().apply {
                    put(
                        "condition",
                        org.json.JSONObject().apply {
                            put("type", "textFount")
                            put("text", org.json.JSONObject().apply {
                                put("type", "text")
                                put("text", t)
                            })
                        }
                    )
                }
            )
        }
        val o = org.json.JSONObject().apply {
            put("type", "等待候选")
            put("delay", "0")
            put("desc", "状态识别")
            put("candidates", cands)
        }
        return Z.Action.parse(o) ?: Z.Action(
            type = Z.ActionType.WAIT_CANDIDATES,
            common = Z.ActionCommon(delay = "0", desc = "状态识别")
        )
    }
}
