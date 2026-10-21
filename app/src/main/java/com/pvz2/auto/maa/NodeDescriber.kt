package com.pvz2.auto.maa

/**
 * 把一个 [NodeDef] 摊成「参数名 → 参数值」的列表，供编辑器展开显示。
 *
 * ## 为什么单独抽出来
 *
 * 这是纯粹的数据整形，没有 Android 依赖 —— 所以可以用单元测试钉死。
 * 编辑器里如果靠肉眼核对参数对不对，一个节点的字段错位根本看不出来；
 * 而且"展开看参数"本身是这个功能的主要用途，显示错了比不显示更糟。
 *
 * ## 顺序为什么重要
 *
 * 按「识别 → 动作 → 流程 → 时序」排，和看脚本时的思维顺序一致：
 * 先看它在等什么画面，再看它做什么，再看往哪走，最后才关心毫秒数。
 * 按字典序排的话，用户每次都要重新找一遍。
 */
object NodeDescriber {

    /** 一行参数。label 用弱色显示，value 用正文色。 */
    data class Row(val label: String, val value: String)

    /** 节点摘要，折叠状态下显示。例如 `TemplateMatch · Click`。 */
    fun summary(node: NodeDef): String =
        "${recognitionName(node.recognition)} · ${actionName(node.action)}"

    fun describe(node: NodeDef): List<Row> {
        val rows = ArrayList<Row>(16)

        // ---- 识别 ----
        rows += Row("识别", recognitionName(node.recognition))
        when (val r = node.recognition) {
            is Recognition.Template -> {
                if (r.templates.isNotEmpty()) {
                    rows += Row("  模板", r.templates.joinToString("\n"))
                }
                r.roi?.let { rows += Row("  ROI", rectText(it)) }
                rows += Row("  阈值", fmt(r.threshold))
                if (r.greenMask) rows += Row("  绿幕掩码", "开")
                if (r.method != 5) rows += Row("  算法", r.method.toString())
            }

            is Recognition.Ocr -> {
                if (r.expected.isNotEmpty()) {
                    rows += Row("  期望文本", r.expected.joinToString("\n"))
                }
                r.roi?.let { rows += Row("  ROI", rectText(it)) }
                rows += Row("  相似度", fmt(r.threshold))
            }

            is Recognition.Color -> {
                r.roi?.let { rows += Row("  ROI", rectText(it)) }
                rows += Row("  下限", r.lower.joinToString(","))
                rows += Row("  上限", r.upper.joinToString(","))
                rows += Row("  最少像素", r.count.toString())
            }

            is Recognition.AnyOf -> {
                rows += Row("  任一项数", r.items.size.toString())
            }

            Recognition.DirectHit -> Unit
        }

        // ---- 动作 ----
        rows += Row("动作", actionName(node.action))
        when (val a = node.action) {
            is Action.Click -> {
                rows += Row("  目标", pointText(a.target) ?: "命中框中心")
                a.targetOffset?.let { rows += Row("  偏移", rectText(it)) }
            }

            is Action.Swipe -> {
                rows += Row("  起点", pointText(a.begin) ?: "-")
                rows += Row("  终点", pointText(a.end) ?: "-")
                rows += Row("  时长", "${a.durationMs} ms")
                a.target?.let { rows += Row("  目标偏移", rectText(it)) }
            }

            is Action.Custom -> {
                rows += Row("  名称", a.name)
                a.param?.let { rows += Row("  参数", it) }
            }

            is Action.StopTask -> rows += Row("  结果", if (a.success) "成功" else "失败")

            Action.DoNothing -> Unit
        }

        // ---- 流程 ----
        if (node.next.isNotEmpty()) rows += Row("next", node.next.joinToString("\n"))
        if (node.onError.isNotEmpty()) rows += Row("on_error", node.onError.joinToString("\n"))

        // ---- 时序 ----
        rows += Row("超时", "${node.timeout} ms")
        rows += Row("识别间隔", "${node.rateLimit} ms")
        if (node.preDelay != 200L) rows += Row("动作前等待", "${node.preDelay} ms")
        if (node.postDelay != 200L) rows += Row("动作后等待", "${node.postDelay} ms")

        // ---- 其他（只在非默认值时列出，避免每张卡片都塞一堆"正常"） ----
        if (!node.enabled) rows += Row("启用", "否")
        if (node.maxHit != 4_294_967_295L) rows += Row("最大命中", node.maxHit.toString())
        node.focus?.let { rows += Row("提示文字", it) }

        return rows
    }

    private fun recognitionName(r: Recognition): String = when (r) {
        is Recognition.Template -> "TemplateMatch"
        is Recognition.Ocr -> "OCR"
        is Recognition.Color -> "ColorMatch"
        is Recognition.AnyOf -> "Or"
        Recognition.DirectHit -> "DirectHit"
    }

    private fun actionName(a: Action): String = when (a) {
        is Action.Click -> "Click"
        is Action.Swipe -> "Swipe"
        is Action.Custom -> "Custom(${a.name})"
        is Action.StopTask -> "StopTask"
        Action.DoNothing -> "DoNothing"
    }

    /** `[x,y]` → `(x, y)`；`[x,y,w,h]` → `(x,y) w×h`；null → null。 */
    private fun pointText(arr: IntArray?): String? {
        if (arr == null) return null
        return when (arr.size) {
            2 -> "(${arr[0]}, ${arr[1]})"
            4 -> "(${arr[0]}, ${arr[1]}) ${arr[2]}×${arr[3]}"
            else -> arr.joinToString(",", "(", ")")
        }
    }

    private fun rectText(arr: IntArray?): String {
        if (arr == null) return "-"
        return if (arr.size >= 4) "(${arr[0]},${arr[1]}) ${arr[2]}×${arr[3]}"
        else arr.joinToString(",")
    }

    private fun fmt(v: Double): String = "%.2f".format(v)
}
