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
        IMAGE,

        /**
         * 点进去是**另一层**东西（条件、监听），不是一个标量字段。
         *
         * UI 见到这个不要弹输入框 —— 弹了也只能改个摘要字符串，
         * 而里面那层有自己的类型和一堆参数。要打开嵌套编辑。
         */
        ENTER
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

    /** 时间单位的显示名，与 [Z.TimeUnit] 一一对应。 */
    val UNITS = listOf("ms", "s", "min", "h")

    private val YES_NO = listOf("否", "是")

    /**
     * 这个字段能不能编辑。
     *
     * 被 `__vars` 覆盖的字段一律只读 —— 改了也会被变量算出的值盖掉，
     * 让用户改等于骗他。
     */
    private fun editKind(field: String, c: Z.ActionCommon): Kind =
        if (c.vars.containsKey(field)) Kind.READONLY else Kind.TEXT

    /** 纯数字字段。UI 要弹数字键盘，所以单独区分。 */
    private fun numberKind(field: String, c: Z.ActionCommon): Kind =
        if (c.vars.containsKey(field)) Kind.READONLY else Kind.NUMBER
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
        val c = action.common

        // ---- 身份
        rows += Row("类型", action.type.wire)
        action.type.controlRunType?.let { rows += Row("控制方式", it) }
        if (!action.type.executable) {
            rows += Row("⚠ 执行", "安卓上暂不支持", Kind.READONLY)
        }

        // ---- 条件
        //
        // 用 ENTER 表示"这一行点进去是另一层东西"，不是标量字段。
        // UI 据此打开嵌套编辑而不是弹输入框 —— 条件本身有 13 种类型、
        // 自己还有一堆参数，用输入框改不了。
        rows += Row(
            "条件",
            c.condition?.let { ZConditionDescriber.summary(it) }
                // 空条件也要能点 —— 用户看到"—"会以为这里没救，
                // 而实际上点一下就能加。写清楚"点了是去加"。
                ?: "（未设置，点击添加）",
            Kind.ENTER,
            field = "condition"
        )

        // ---- 监听（scriptCallbacks）
        //
        // 7 个槽位，每个槽位本身是一段完整动作。
        //
        // ## 为什么必须显示
        //
        // 一个挂了监听的动作用户展开后如果什么都看不到，他会以为没挂 ——
        // 而那些监听在自动精灵里是会真的跑的。这里每个槽位一行，
        // 显示槽位名 + 里面那个动作的标签，至少让用户知道"这里有事"。
        if (action.callbacks.isNotEmpty()) {
            // 按固定顺序列，不按 Map 的遍历顺序 —— 用户要能对照着看
            Z.CallbackSlot.entries.forEach { slot ->
                action.callbacks[slot]?.let { cb ->
                    rows += Row(
                        "监听·${slot.label}",
                        ZScriptIO.actionLabel(cb),
                        // 点进去编辑那段监听动作本身
                        Kind.ENTER,
                        field = "callbacks"
                    )
                }
            }
        } else {
            // 没挂监听时也给一行入口，否则用户根本不知道有这个功能。
            // 文案要说清楚"点了是去加"，不是"这里有东西"。
            rows += Row(
                "监听",
                "（未设置，点击添加）",
                Kind.ENTER,
                field = "callbacks"
            )
        }

        // ---- 位置
        action.posData?.let { rows += posRows("位置", it) }
        action.startPos?.let { rows += posRows("起点", it) }
        action.endPos?.let { rows += posRows("终点", it) }

        // ---- 14 个通用字段，**缺省也显示**
        //
        // ## 顺序照抄自动精灵
        //
        // 取自 SPEC.md 第 181 行（基类 m26622 的属性声明顺序，也就是 UI 绑定的顺序）：
        //
        //   type, desc, jumpId, duration, delay, delayUnit,
        //   repeatCount, repeatDelayValue, repeatDelayUnit,
        //   fastRepeatMode, disabled, condition, scriptCallbacks, __vars
        //
        // 顺序一致，用户在两个程序之间对照着看时不用重新找位置。
        //
        // ## 为什么缺省也显示
        //
        // 以前是"有什么显示什么"，没设过 delay 的动作就没有那一行，
        // 用户分不清"字段不存在"和"它等于默认值"。
        rows += Row("备注", c.desc.orEmpty(), editKind("desc", c), field = "desc")
        rows += Row("跳转标识", c.jumpId.orEmpty(), editKind("jumpId", c), field = "jumpId")
        rows += Row("持续(ms)", c.duration.orEmpty(), numberKind("duration", c), field = "duration")

        // 等待时间：**值和单位放同一行**。
        //
        // 分成两行（"等待" + "等待单位"）会让用户先看数字再看单位，
        // 而这两个必须一起读才有意义 —— "2" 单独看不知道是 2 秒还是 2 毫秒。
        rows += Row(
            "等待",
            "${c.delay.orEmpty()} ${unitLabel(c.delayUnit)}".trim(),
            editKind("delay", c),
            field = "delay"
        )

        rows += Row(
            "重复次数",
            when (val n = c.repeatCount) {
                null -> ""
                0 -> "无限"
                else -> n.toString()
            },
            numberKind("repeatCount", c),
            field = "repeatCount"
        )

        // 重复间隔同理，值和单位同一行。
        rows += Row(
            "重复间隔",
            "${c.repeatDelayValue.orEmpty()} ${unitLabel(c.repeatDelayUnit)}".trim(),
            numberKind("repeatDelayValue", c),
            field = "repeatDelayValue"
        )

        rows += Row(
            "快速重复", if (c.fastRepeatMode) "是" else "否", Kind.ENUM,
            options = YES_NO, field = "fastRepeatMode"
        )
        rows += Row(
            "禁用", if (c.disabled) "是" else "否", Kind.ENUM,
            options = YES_NO, field = "disabled"
        )

        // ---- 变量覆盖
        //
        // 列出来用户才知道哪些字段是运行时算的。这些行只读：
        // 改固定值也会被变量算出来的值盖掉。
        c.vars.forEach { (field, def) ->
            val t = def.optString("varType").ifEmpty { "?" }
            val exp = def.optString("valueExp")
            rows += Row(
                "变量·$field",
                if (exp.isBlank()) t else "$t = $exp",
                Kind.READONLY
            )
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
    private fun posRows(prefix: String, pos: Z.PosData): List<Row> {
        val rows = ArrayList<Row>()

        // ★ 坐标类型不单列"类型"行。
        //
        //   滑动/点击的位置对用户来说就是一对 (x,y)，类型是格式细节。
        //   单列一行"类型 坐标"只占地方、不提供任何操作 —— 用户要改的
        //   永远是那个坐标本身。
        //
        //   其它类型（图片/文字/颜色/节点）才需要提示，因为用户会想知道
        //   "这一条是按什么找的"。
        if (pos.type != Z.PosType.LOCATION) {
            val typeLabel = when (pos.type) {
                Z.PosType.IMAGE -> "图片"
                Z.PosType.TEXT -> "文字"
                Z.PosType.COLOR -> "颜色"
                Z.PosType.NODE -> "节点"
                Z.PosType.AI_LOC -> "AI位置"
                Z.PosType.LOCATION -> "坐标"
            }
            rows += Row("$prefix 类型", typeLabel)
        }

        when (pos.type) {
            Z.PosType.LOCATION -> {
                val x = pos.x?.raw.orEmpty()
                val y = pos.y?.raw.orEmpty()
                rows += Row("$prefix X", x, Kind.COORD, field = "$prefix.x")
                rows += Row("$prefix Y", y, Kind.COORD, field = "$prefix.y")
            }

            Z.PosType.IMAGE -> {
                pos.imageData?.let { img ->
                    val size = if (img.width != null && img.height != null) "${img.width}×${img.height}" else "未知尺寸"
                    rows += Row("$prefix 模板", "$size，${img.data.length} 字符", Kind.IMAGE, field = "$prefix.image")
                }
                pos.minSimilarPercent?.let { rows += Row("$prefix 相似度", "$it%") }
            }

            Z.PosType.TEXT -> {
                rows += Row("$prefix 文字", pos.text.orEmpty(), Kind.TEXT, field = "$prefix.text")
                pos.similarPercent?.let { rows += Row("$prefix 相似度", "$it%") }
            }

            Z.PosType.COLOR -> {
                rows += Row("$prefix 颜色", pos.color.orEmpty(), Kind.TEXT, field = "$prefix.color")
                pos.similarPercent?.let { rows += Row("$prefix 相似度", "$it%") }
            }

            Z.PosType.NODE -> {
                rows += Row("$prefix 节点", pos.text.orEmpty(), Kind.TEXT, field = "$prefix.text")
            }

            Z.PosType.AI_LOC -> {
                rows += Row("$prefix AI", pos.text.orEmpty(), Kind.TEXT, field = "$prefix.text")
            }
        }

        // 通用字段（FIELDS.md:144-147）
        //
        // 每一行都要带 field 和 Kind —— 否则界面上看得见、点不动、
        // 也不说明为什么，用户会当成 bug。
        // 属性名对应 [Z.PosData] 里的字段，写回走 ZFieldEditor。
        pos.limitArea?.let {
            rows += Row("$prefix 范围", it.toString(), Kind.AREA, field = "$prefix.limitArea")
        }
        pos.indexNum?.let {
            // 多命中时选第几个："2" / "-1" / "0"(随机) / "1-3"(区间随机) / "1,3,4"
            rows += Row("$prefix 选第几个", it, Kind.TEXT, field = "$prefix.indexNum")
        }
        pos.xOffset?.let {
            rows += Row("$prefix X偏移", it.raw, Kind.TEXT, field = "$prefix.xOffset")
        }
        pos.yOffset?.let {
            rows += Row("$prefix Y偏移", it.raw, Kind.TEXT, field = "$prefix.yOffset")
        }

        // 「位置周围条件」（FIELDS.md:147）。
        //
        // 只用于 posData，不出现在条件类型下拉里（FIELDS.md:281）。
        // 之前的实现把它当未知字段，摊出一坨原始 JSON ——
        // 结构没丢，但用户看不懂。现在用条件摘要显示。
        pos.extra["filterByAroundConditionSet"]?.let { raw ->
            val summary = runCatching {
                val obj = if (raw is org.json.JSONObject) raw else org.json.JSONObject(raw.toString())
                Z.Condition.parse(obj)?.let { ZConditionDescriber.summary(it) }
            }.getOrNull()
            rows += Row("$prefix 周围条件", summary ?: "（格式无法解析）", Kind.READONLY)
        }

        return rows
    }
}
