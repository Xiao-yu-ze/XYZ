package com.pvz2.auto.zjs

/**
 * 把一个动作翻译成**可编辑的字段列表**。
 *
 * ## 为什么单独一层
 *
 * 编辑器不该知道 `posData.type == "location"` 还是 `"image"`，也不该知道
 * `delayUnit` 的 1 代表秒。这些是协议细节，散在 UI 代码里改一次要动五处。
 *
 * 这一层只做两件事：
 *  1. 把模型摊平成「标签 → 值」的行，UI 直接渲染；
 *  2. 标出哪些字段**可以改**、改的时候是什么类型（文本/数字/枚举/坐标）。
 *
 * ## 只读字段也要列出来
 *
 * 用户需要看见完整的动作定义（尤其是从别人脚本导入的），
 * 哪怕暂时不能编辑。藏起来会让人以为脚本缺了东西。
 */
object ZActionDescriber {

    /** 一个字段该怎么编辑。 */
    enum class Kind {
        /** 不可编辑，只显示。 */
        READONLY,

        /** 自由文本（备注、表达式）。 */
        TEXT,

        /** 数字或数值区间，如 `100`、`1-3`。 */
        NUMBER,

        /** 从固定选项里选。 */
        ENUM,

        /** 坐标：可以手填，也可以用取点按钮。 */
        COORD,

        /** 区域：可以用框选按钮。 */
        AREA,

        /** 模板图：可以用截取按钮。 */
        IMAGE
    }

    /** 一行。 */
    data class Row(
        val label: String,
        val value: String,
        val kind: Kind = Kind.READONLY,
        /** 枚举选项，仅 [Kind.ENUM] 有。 */
        val options: List<String> = emptyList(),
        /** 这一行对应模型里的哪个字段，写回时用。空 = 只读。 */
        val field: String = ""
    )

    /** 动作标题，如「点击」「控制执行·jumpTo」。 */
    fun title(action: Z.Action): String = ZScriptIO.actionLabel(action)

    /**
     * 摊平成一个动作的所有字段。
     *
     * 顺序是固定的：先身份（类型），再位置，再时序，最后其它 ——
     * 用户扫一眼最先想看的是"这是什么动作、打哪里"。
     */
    fun describe(action: Z.Action): List<Row> {
        val rows = ArrayList<Row>()

        // ---- 身份
        rows += Row("类型", action.type.wire)
        action.type.controlRunType?.let { rows += Row("控制方式", it) }
        if (!action.type.executable) {
            rows += Row("⚠ 执行", "安卓上暂不支持", Kind.READONLY)
        }

        // ---- 位置
        action.posData?.let { rows += posRows("位置", it, editable = true) }
        action.startPos?.let { rows += posRows("起点", it, editable = true) }
        action.endPos?.let { rows += posRows("终点", it, editable = true) }

        // ---- 备注与跳转
        rows += Row(
            "备注",
            action.common.desc.orEmpty(),
            if (action.common.vars.containsKey("desc")) Kind.READONLY else Kind.TEXT,
            field = "desc"
        )
        action.common.jumpId?.let { rows += Row("跳转标识", it, Kind.TEXT, field = "jumpId") }

        // ---- 时序
        action.common.duration?.let {
            rows += Row("持续(ms)", it, editable("duration", action), field = "duration")
        }
        action.common.delay?.let {
            rows += Row("等待(${unitLabel(action.common.delayUnit)})", it, editable("delay", action), field = "delay")
        }
        action.common.repeatCount?.let {
            rows += Row("重复次数", if (it == 0) "无限" else it.toString(), editable("repeatCount", action), field = "repeatCount")
        }
        action.common.repeatDelayValue?.let {
            rows += Row("重复间隔(${unitLabel(action.common.repeatDelayUnit)})", it, editable("repeatDelayValue", action), field = "repeatDelayValue")
        }
        if (action.common.fastRepeatMode) rows += Row("快速重复", "是")

        // ---- 其它
        if (action.common.disabled) rows += Row("状态", "已禁用")
        action.common.condition?.let { c ->
            rows += Row("条件", ZConditionDescriber.summary(c))
        }

        // ---- 未知字段：让用户看得见，避免"导入后凭空少了东西"
        action.extra.forEach { (k, v) ->
            rows += Row(k, v?.toString().orEmpty(), Kind.READONLY)
        }

        return rows
    }

    // ------------------------------------------------------------ 内部

    /** 标出哪些字段被变量覆盖了 —— 那些只能看不能改，改了也会被变量覆盖。 */
    private fun editable(field: String, action: Z.Action): Kind =
        if (action.common.vars.containsKey(field)) Kind.READONLY else Kind.NUMBER

    private fun unitLabel(u: Z.TimeUnit): String = when (u) {
        Z.TimeUnit.MILLISECONDS -> "ms"
        Z.TimeUnit.SECONDS -> "s"
        Z.TimeUnit.MINUTES -> "min"
        Z.TimeUnit.HOURS -> "h"
    }

    /**
     * 位置对象的行。标签带前缀，因为一个滑动手势有两组位置。
     *
     * `x`/`y` 用 [Kind.COORD]，编辑时能挂取点按钮；
     * `limitArea` 用 [Kind.AREA]，能挂框选按钮。
     */
    private fun posRows(prefix: String, pos: Z.PosData, editable: Boolean): List<Row> {
        val rows = ArrayList<Row>()
        val typeLabel = when (pos.type) {
            Z.PosType.LOCATION -> "坐标"
            Z.PosType.IMAGE -> "图片"
            Z.PosType.TEXT -> "文字"
            Z.PosType.COLOR -> "颜色"
            Z.PosType.NODE -> "节点"
            Z.PosType.AI_LOC -> "AI位置"
        }
        rows += Row("$prefix 类型", typeLabel)

        when (pos.type) {
            Z.PosType.LOCATION -> {
                val x = pos.x?.raw.orEmpty()
                val y = pos.y?.raw.orEmpty()
                rows += Row("$prefix X", x, if (editable) Kind.COORD else Kind.READONLY, field = "$prefix.x")
                rows += Row("$prefix Y", y, if (editable) Kind.COORD else Kind.READONLY, field = "$prefix.y")
            }

            Z.PosType.IMAGE -> {
                pos.imageData?.let { img ->
                    val size = if (img.width != null && img.height != null) "${img.width}×${img.height}" else "未知尺寸"
                    rows += Row("$prefix 模板", "$size，${img.data.length} 字符", Kind.IMAGE, field = "$prefix.image")
                }
                pos.minSimilarPercent?.let { rows += Row("$prefix 相似度", "$it%") }
            }

            Z.PosType.TEXT -> {
                rows += Row("$prefix 文字", pos.text.orEmpty(), if (editable) Kind.TEXT else Kind.READONLY, field = "$prefix.text")
                pos.similarPercent?.let { rows += Row("$prefix 相似度", "$it%") }
            }

            Z.PosType.COLOR -> {
                rows += Row("$prefix 颜色", pos.color.orEmpty(), if (editable) Kind.TEXT else Kind.READONLY, field = "$prefix.color")
                pos.similarPercent?.let { rows += Row("$prefix 相似度", "$it%") }
            }

            Z.PosType.NODE -> {
                rows += Row("$prefix 节点", pos.text.orEmpty(), Kind.TEXT, field = "$prefix.text")
            }

            Z.PosType.AI_LOC -> {
                rows += Row("$prefix AI", pos.text.orEmpty(), Kind.TEXT, field = "$prefix.text")
            }
        }

        // 通用字段
        pos.limitArea?.let { rows += Row("$prefix 范围", it.toString(), if (editable) Kind.AREA else Kind.READONLY, field = "$prefix.limitArea") }
        pos.indexNum?.let { rows += Row("$prefix 选第几个", it) }
        pos.xOffset?.let { rows += Row("$prefix X偏移", it.raw) }
        pos.yOffset?.let { rows += Row("$prefix Y偏移", it.raw) }

        return rows
    }
}
