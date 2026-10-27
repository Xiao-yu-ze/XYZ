package com.pvz2.auto.ui.page

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.pvz2.auto.zjs.PvzCoords

/**
 * 棋盘（5×9）的两个入口：
 *
 * - [show]：单次选格弹窗（编辑「行/列」字段用）；
 * - [buildGrid]：只给一个网格 View，「种植顺序」面板会把棋盘嵌在自己的界面里。
 *
 * 布局照游戏内视角：**行 1-5 从上到下、列 1-9 从左到右**，
 * 表头显示 R1-R5 / C1-C9，和 参考管线 的棋盘一致。
 */
object PvzBoardPicker {

    /** 弹窗结果：[row]/[col] 都是 1-based；[cleared] = 用户点了清空。 */
    class Pick(val row: Int, val col: Int, val cleared: Boolean = false)

    fun show(
        context: Context,
        title: String,
        currentRow: Int?,
        currentCol: Int?,
        onPick: (Pick) -> Unit
    ) {
        var row = currentRow
        var col = currentCol

        val hint = TextView(context).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#B0BEC5"))
        }
        val grid = buildGrid(
            context,
            selection = { row to col },
            onPick = { r, c ->
                row = r
                col = c
                hint.text = hintFor(r, c)
            }
        )
        hint.text = hintFor(row, col)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 4))
            addView(hint)
            addView(grid)
        }

        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(root)
            .setPositiveButton("确定") { _, _ ->
                val r = row
                val c = col
                if (r != null && c != null) onPick(Pick(r, c))
            }
            .setNeutralButton("清空") { _, _ -> onPick(Pick(0, 0, cleared = true)) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 只造网格（不带弹窗）。[selection] 返回当前选中用于高亮。 */
    fun buildGrid(
        context: Context,
        selection: () -> Pair<Int?, Int?>,
        onPick: (Int, Int) -> Unit
    ): View {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(spacer(context, dp(context, 30)))
        for (c in 1..PvzCoords.COLS) {
            header.addView(textLabel(context, "C$c", bold = true, widthDp = 30))
        }
        root.addView(header)

        val cells = ArrayList<TextView>()
        fun repaint() {
            val sel = selection()
            for (v in cells) {
                val tag = v.tag as Pair<*, *>
                val on = (tag.first as Int) == sel.first && (tag.second as Int) == sel.second
                v.setBackgroundColor(
                    if (on) Color.parseColor("#2E7D32") else Color.parseColor("#263238")
                )
            }
        }

        for (r in 1..PvzCoords.ROWS) {
            val line = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(context, 1), 0, dp(context, 1))
            }
            line.addView(textLabel(context, "R$r", bold = true, widthDp = 30))
            for (c in 1..PvzCoords.COLS) {
                val cell = TextView(context).apply {
                    gravity = Gravity.CENTER
                    setTextColor(Color.parseColor("#CFD8DC"))
                    tag = r to c
                    layoutParams = LinearLayout.LayoutParams(dp(context, 30), dp(context, 30)).apply {
                        setMargins(dp(context, 1), 0, dp(context, 1), 0)
                    }
                    setOnClickListener {
                        onPick(r, c)
                        repaint()
                    }
                }
                cells.add(cell)
                line.addView(cell)
            }
            root.addView(line)
        }
        repaint()
        return root
    }

    // ------------------------------------------------------------ 小工具

    @SuppressLint("SetTextI18n")
    private fun hintFor(row: Int?, col: Int?): String {
        val pt = if (row != null && col != null) PvzCoords.cell(row, col) else null
        return when {
            row == null || col == null -> "点一个格子（行 1-5 从上到下，列 1-9 从左到右）"
            pt == null -> "第${row}行第${col}列"
            else -> "第${row}行第${col}列 → 落点 (${pt[0]}, ${pt[1]})"
        }
    }

    private fun dp(context: Context, v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()

    @SuppressLint("SetTextI18n")
    private fun textLabel(
        context: Context,
        text: String,
        bold: Boolean = false,
        widthDp: Int = 36
    ): TextView = TextView(context).apply {
        this.text = text
        textSize = 11f
        gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#90A4AE"))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(
            dp(context, widthDp), ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun spacer(context: Context, px: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(px, 1)
    }
}
