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
 * 「在棋盘上选格子」弹窗。
 *
 * ## 为什么要有它
 *
 * 「第几行第几列」比「x=833,y=260」好懂，但用户还是得自己数格子。
 * 直接给一张 5×9 的棋盘点一下，行列两个值一起填好，顺带把落点像素显示出来
 * 便于核对 —— 这一步能挡掉绝大多数"种到隔壁格子"。
 *
 * 棋盘布局照游戏内视角：**行从上到下 1-5，列从左到右 1-9**。
 * 点已有选中的格子不会取消（保持最少惊讶），要清空用「清空」按钮。
 */
object PvzBoardPicker {

    /** 弹窗结果：[row]/[col] 都是 1-based；[cleared] 为 true 表示用户点了清空。 */
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

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 4))
        }

        val hint = TextView(context).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#B0BEC5"))
        }
        root.addView(hint)

        // ---- 列号表头
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(spacer(context, dp(context, 34)))
        for (c in 1..PvzCoords.COLS) {
            header.addView(label(context, "C$c", bold = true))
        }
        root.addView(header)

        // ---- 5 行 × 9 列
        val cells = ArrayList<TextView>()
        fun refresh() {
            for (v in cells) {
                val r = v.tag as Pair<*, *>
                val sel = (r.first as Int) == row && (r.second as Int) == col
                v.setBackgroundColor(
                    if (sel) Color.parseColor("#2E7D32") else Color.parseColor("#263238")
                )
                v.setTextColor(if (sel) Color.WHITE else Color.parseColor("#CFD8DC"))
            }
            val pt = if (row != null && col != null) PvzCoords.cell(row!!, col!!) else null
            hint.text = when {
                row == null || col == null -> "点一个格子（行 1-5 从上到下，列 1-9 从左到右）"
                pt == null -> "第${row}行第${col}列"
                else -> "第${row}行第${col}列 → 落点 (${pt[0]}, ${pt[1]})"
            }
        }

        for (r in 1..PvzCoords.ROWS) {
            val line = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(context, 2), 0, dp(context, 2))
            }
            line.addView(label(context, "R$r", bold = true, widthDp = 34))
            for (c in 1..PvzCoords.COLS) {
                val cell = TextView(context).apply {
                    text = ""
                    gravity = Gravity.CENTER
                    setTextColor(Color.parseColor("#CFD8DC"))
                    tag = r to c
                    layoutParams = LinearLayout.LayoutParams(dp(context, 32), dp(context, 32)).apply {
                        setMargins(dp(context, 2), 0, dp(context, 2), 0)
                    }
                    setOnClickListener {
                        row = r
                        col = c
                        refresh()
                    }
                }
                cells.add(cell)
                line.addView(cell)
            }
            root.addView(line)
        }
        refresh()

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

    // ------------------------------------------------------------ 小工具

    private fun dp(context: Context, v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()

    @SuppressLint("SetTextI18n")
    private fun label(
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
        layoutParams = LinearLayout.LayoutParams(dp(context, widthDp), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun spacer(context: Context, px: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(px, 1)
    }
}
