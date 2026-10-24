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

        /**
         * 小标题，不是字段。
         *
         * 用来把一长串字段切成「识别 / 触发 / 时机」几段 —— 十几个行
         * 平铺在一起时，用户找不到「相似度」在哪，因为没有任何结构。
         */
        SECTION,

        /** 自由文本（备注、表达式）。 */
        TEXT,

        /** 数字或数值区间，如 `100`、`1-3`。 */
        NUMBER,

        /**
         * 0~100 的百分比。用滑块。
         *
         * 相似度这类值用户的思考方式是"大概八成"，而不是"87"。
         * 滑块能把整个区间摆在眼前，还能量出"我现在的模板实际能到多少"。
         */
        PERCENT,

        /**
         * 布尔值。用开关。
         *
         * 「禁用：否」这种用弹窗填的布尔值是纯浪费 —— 一次点击变成三次。
         */
        SWITCH,

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
    /** 小节标题。不是字段 —— UI 只画一行淡字，不挂点击、不显示值。 */
    private fun section(label: String) = Row(label, "", Kind.SECTION)
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

        rows += section("触发")

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
        rows += section("识别")
        // 主位置不带「位置」前缀 —— 外面已经有「识别」小节，
        // 每行再来一个「位置」只是重复两个字，还占掉标签宽度。
        action.posData?.let { rows += posRows("位置", it, showPrefix = false) }
        action.startPos?.let { rows += posRows("起点", it) }
        action.endPos?.let { rows += posRows("终点", it) }

        // ---- 多指滑动
        //
        // 管理入口放在**每根手指的详情之前** —— 用户展开这个动作，
        // 第一眼该看到"有几根手指、怎么加"，而不是先滚过一堆坐标。
        //
        // ★ 必须给 field，否则 UI 不会挂点击。只给 Kind.ENTER 是不够的：
        //   makeRow 的判断是 `kind != READONLY && field.isNotEmpty()`，
        //   field 空着就永远点不动。
        if (action.type == Z.ActionType.MULTI_SWIPE) {
            rows += Row(
                "手指",
                if (action.fingers.isEmpty()) "（还没有手指，点击添加）"
                else "共 ${action.fingers.size} 根，点击管理",
                Kind.ENTER,
                field = "fingers"
            )
        }

        // 每根手指一组起终点。按 1、2、3 编号而不用"第几个" ——
        // 多指滑动时用户想的是"食指在哪、中指在哪"，
        // 编号比序号更接近那个心智模型。
        action.fingers.forEachIndexed { i, finger ->
            val n = i + 1
            finger.startPos?.let { rows += posRows("手指$n 起点", it) }
            finger.endPos?.let { rows += posRows("手指$n 终点", it) }
        }

        // ---- 等待候选（我们自己的节点，等价于 Maa 的 next）
        //
        // 候选本身不在这里编辑 —— 它们的条件就是各自的位置对象，
        // 在动作列表里逐条配更顺手。这里只把「等谁、等多久」摊开，
        // 让用户一眼看到这条到底在等什么，而不是一个空壳。
        if (action.type == Z.ActionType.WAIT_CANDIDATES) {
            rows += Row("候选", "共 ${action.candidates.size} 个，按顺序试", Kind.READONLY)
            action.candidates.forEachIndexed { i, c ->
                val what = when (c.condition?.type) {
                    null -> "无条件（一定命中）"
                    Z.ConditionType.IMAGE -> "出现图片"
                    Z.ConditionType.TEXT -> "出现文字"
                    Z.ConditionType.COLOR -> "出现颜色"
                    Z.ConditionType.NODE -> "出现节点"
                    else -> c.condition?.type?.wire ?: "?"
                }
                val target = c.jumpTo ?: "（往下走）"
                rows += Row("候选 ${i + 1}", "$what → $target", Kind.READONLY)
            }
            rows += Row(
                "总超时(ms)",
                (action.waitTimeoutMs ?: 0).let { if (it <= 0) "一直等" else it.toString() },
                Kind.NUMBER, field = "waitTimeoutMs"
            )
            rows += Row(
                "轮询间隔(ms)",
                (action.waitIntervalMs ?: 200).toString(),
                Kind.NUMBER, field = "waitIntervalMs"
            )
        }

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
        rows += section("其他")
        rows += Row("备注", c.desc.orEmpty(), editKind("desc", c), field = "desc")
        rows += Row("跳转标识", c.jumpId.orEmpty(), editKind("jumpId", c), field = "jumpId")
        rows += section("时机")
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

        // 布尔值用开关，不用弹窗：一次点击 vs 三次点击，而且状态一直可见。
        rows += Row(
            "快速重复", if (c.fastRepeatMode) "是" else "否", Kind.SWITCH,
            field = "fastRepeatMode"
        )
        rows += Row(
            "禁用", if (c.disabled) "是" else "否", Kind.SWITCH,
            field = "disabled"
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
    /** 从 posData.extra 里读一个布尔（协议里是可选字段，缺省按 false）。 */
    private fun extraBool(pos: Z.PosData, key: String): Boolean =
        when (val v = pos.extra[key]) {
            is Boolean -> v
            is String -> v == "true" || v == "是"
            null -> false
            else -> false
        }

    /** 从 posData.extra 里读一个字符串。 */
    private fun extraStr(pos: Z.PosData, key: String): String? =
        pos.extra[key]?.toString()?.takeIf { it.isNotBlank() }

    private fun posRows(prefix: String, pos: Z.PosData, showPrefix: Boolean = true): List<Row> {
        // 小节已经说明了这是"位置"，每行再带「位置」两个字既重复又占宽度
        val p = if (showPrefix) "$prefix " else ""
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
            rows += Row("${p}类型", typeLabel)
        }

        when (pos.type) {
            Z.PosType.LOCATION -> {
                val x = pos.x?.raw.orEmpty()
                val y = pos.y?.raw.orEmpty()
                rows += Row("${p}X", x, Kind.COORD, field = "$prefix.x")
                rows += Row("${p}Y", y, Kind.COORD, field = "$prefix.y")
            }

            Z.PosType.IMAGE -> {
                pos.imageData?.let { img ->
                    val size = if (img.width != null && img.height != null) "${img.width}×${img.height}" else "未知尺寸"
                    rows += Row("${p}模板", "$size，${img.data.length} 字符", Kind.IMAGE, field = "$prefix.image")
                }
                // 阈值是模板匹配唯一的调节旋钮，必须能改。
                rows += Row(
                    "${p}相似度",
                    pos.minSimilarPercent?.let { "$it%" } ?: "未设置（默认 90%）",
                    Kind.PERCENT, field = "$prefix.minSimilarPercent"
                )
                // FIELDS.md 3.2 里这三个字段以前既没显示也没解释。
                // 快速搜图是「降精度换速度」，搜索模式决定用哪种匹配算法 ——
                // 调不动它们，用户就只能靠改相似度盲试。
                rows += Row(
                    "${p}快速搜图",
                    if (extraBool(pos, "quickSearch")) "是" else "否",
                    Kind.SWITCH, field = "$prefix.quickSearch"
                )
                rows += Row(
                    "${p}搜索模式",
                    extraStr(pos, "searchMode") ?: "color_2.21",
                    Kind.ENUM,
                    listOf("color_2.21", "outline_2.21", "COLOR", "HOG"),
                    field = "$prefix.searchMode"
                )
                rows += Row(
                    "${p}缩放方式",
                    extraStr(pos, "imageScaleType") ?: "dpi",
                    Kind.ENUM,
                    listOf(
                        "dpi", "baseScreenWidth", "baseScreenHeight",
                        "baseScreenWidthAndHeight", "tryAll", "none"
                    ),
                    field = "$prefix.imageScaleType"
                )
            }

            Z.PosType.TEXT -> {
                rows += Row("${p}文字", pos.text.orEmpty(), Kind.TEXT, field = "$prefix.text")
                rows += Row(
                    "${p}相似度",
                    pos.similarPercent?.let { "$it%" } ?: "未设置（默认 90%）",
                    Kind.PERCENT, field = "$prefix.similarPercent"
                )
                // FIELDS.md 3.3：本地识别 / 云识别
                rows += Row(
                    "${p}OCR 模式",
                    extraStr(pos, "ocrMode") ?: "local_v2",
                    Kind.ENUM, listOf("local_v2", "local", "online"),
                    field = "$prefix.ocrMode"
                )
            }

            Z.PosType.COLOR -> {
                rows += Row("${p}颜色", pos.color.orEmpty(), Kind.TEXT, field = "$prefix.color")
                rows += Row(
                    "${p}相似度",
                    pos.similarPercent?.let { "$it%" } ?: "未设置（默认 90%）",
                    Kind.PERCENT, field = "$prefix.similarPercent"
                )
                // FIELDS.md 3.4：填了「指定位置」就只看那一个点，忽略范围
                rows += Row(
                    "${p}指定位置 X", extraStr(pos, "limitPosX").orEmpty(),
                    Kind.COORD, field = "$prefix.limitPosX"
                )
                rows += Row(
                    "${p}指定位置 Y", extraStr(pos, "limitPosY").orEmpty(),
                    Kind.COORD, field = "$prefix.limitPosY"
                )
            }

            Z.PosType.NODE -> {
                rows += Row("${p}节点", pos.text.orEmpty(), Kind.TEXT, field = "$prefix.text")
            }

            Z.PosType.AI_LOC -> {
                rows += Row("${p}AI", pos.text.orEmpty(), Kind.TEXT, field = "$prefix.text")
            }
        }

        // 预览查找结果：在实时画面上把命中位置框出来。
        //
        // 只对"需要查找"的类型出现 —— 固定坐标没有"查找"这回事，
        // 给它一个点了没反应的按钮只会让人困惑。
        //
        // 单独一行而不是塞进菜单：调阈值是这个动作最费时间的部分，
        // 藏进二级菜单等于让它更难找。
        if (pos.type == Z.PosType.IMAGE || pos.type == Z.PosType.TEXT ||
            pos.type == Z.PosType.COLOR || pos.type == Z.PosType.NODE
        ) {
            rows += Row(
                "${p}预览",
                "点这里在画面上看命中位置",
                Kind.ENTER,
                field = "$prefix.preview"
            )
        }

        // 通用字段（FIELDS.md:144-147）
        //
        // 每一行都要带 field 和 Kind —— 否则界面上看得见、点不动、
        // 也不说明为什么，用户会当成 bug。
        // 属性名对应 [Z.PosData] 里的字段，写回走 ZFieldEditor。
        /*
         * ★ 这四行必须**无条件出现**，不能"设过了才显示"。
         *
         *   新建一条「点击图片」时 posData 只有 type，limitArea/indexNum/
         *   xOffset/yOffset 全是 null —— 按原来的写法界面上根本没有"范围"
         *   这一行，用户永远没法给它设 ROI，于是永远全屏搜索：
         *   慢、误匹配多，而且要选"第几个"也无处可填。
         *
         *   没设过就显示空值，点进去填/框选；清空即删除。
         */
        rows += Row("${p}范围", pos.limitArea?.toString() ?: "", Kind.AREA, field = "$prefix.limitArea")
        // 多命中时选第几个："2" / "-1" / "0"(随机) / "1-3"(区间随机) / "1,3,4"
        rows += Row("${p}选第几个", pos.indexNum ?: "", Kind.TEXT, field = "$prefix.indexNum")
        rows += Row("${p}X偏移", pos.xOffset?.raw ?: "", Kind.TEXT, field = "$prefix.xOffset")
        rows += Row("${p}Y偏移", pos.yOffset?.raw ?: "", Kind.TEXT, field = "$prefix.yOffset")

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
            rows += Row("${p}周围条件", summary ?: "（格式无法解析）", Kind.READONLY)
        }

        return rows
    }
}
