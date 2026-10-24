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
        //
        // ★ 「类型」原来是只读、没有 field —— 点不动，于是 ZFieldEditor.changeType()
        //   成了死代码，用户想换类型只能删了重建（模板/阈值/范围全丢）。
        //   现在出成 ENUM：选项是全部动作类型，6 个「控制执行」带 controlRunType
        //   后缀区分（否则下拉里会出现六个一模一样的名字）。
        //   写回走 ZFieldEditor.apply("type") → changeType()。
        rows += Row(
            "类型",
            ZFieldEditor.actionTypeLabel(action.type),
            Kind.ENUM,
            ZFieldEditor.actionTypeLabels(),
            field = "type"
        )
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
        //
        // ★ 小节标题「识别」改为**有行才加**。
        //
        //   原来无条件 `section("识别")`，于是「按键」「打开链接」「控制执行」
        //   这类没有位置的动作会先出现一个空小节标题、下一行直接是「参数」，
        //   视觉上像少渲染了一块（用户会怀疑界面没加载完）。
        //   现在先把位置行攒出来，非空才下标题。
        val posLines = ArrayList<Row>()
        // 主位置不带「位置」前缀 —— 外面已经有「识别」小节，
        // 每行再来一个「位置」只是重复两个字，还占掉标签宽度。
        action.posData?.let { posLines += posRows("位置", it, showPrefix = false) }
        action.startPos?.let { posLines += posRows("起点", it) }
        action.endPos?.let { posLines += posRows("终点", it) }
        if (posLines.isNotEmpty()) {
            rows += section("识别")
            rows += posLines
        }

        // ---- 动作类型专属字段（FIELDS.md §10）
        //
        // ★ 原坑：这里一行都没有 —— describe() 只对 MULTI_SWIPE / WAIT_CANDIDATES
        //   出了专属行，其余类型的参数（keyCode、inputText、jumpToPosition…）
        //   既没有界面行、写回通道也不存在。于是「按键」建出来没有 keyCode，
        //   运行时被 ZExecutor 以"缺 keyCode"直接跳过；「跳转到」没有目标位置。
        //   动作能建、能存、配不了，看上去却一切正常。
        val typeSpecific = typeRows(action)
        if (typeSpecific.isNotEmpty()) {
            rows += section("参数")
            rows += typeSpecific
        }

        // ---- 多指滑动
        //
        // 管理入口放在**每根手指的详情之前** —— 用户展开这个动作，
        // 第一眼该看到"有几根手指、怎么加"，而不是先滚过一堆坐标。
        //
        // ★ 必须给 field，否则 UI 不会挂点击。只给 Kind.ENTER 是不够的：
        //   makeRow 的判断是 `kind != READONLY && field.isNotEmpty()`，
        //   field 空着就永远点不动。
        if (action.type == Z.ActionType.MULTI_SWIPE) {
            // ★ 三种情况分开说，不能只认我们自己的 fingers。
            //
            //   自动精灵原生的「多指手势」轨迹在 `gestureActions`（一个完整动作
            //   数组，FIELDS.md:470）里，而它现在是**强类型字段**（ZModel.HANDLED
            //   收着它）—— 所以它既不会出现在 extra 只读兜底里，这里原来也不读它。
            //   导入这种脚本时界面显示"（还没有手指，点击添加）"，用户会以为数据
            //   全丢了（其实好好躺在文件里）；他一按"添加"就会写出我们自己的
            //   multiSwipe，和原生轨迹并存，文件越改越乱。
            //   先只读显示出来：能看见就不会误判成丢失。编辑能力以后再说。
            val nativeCount = action.gestureActions.size + action.gestureRaw.size
            val text = when {
                action.fingers.isNotEmpty() -> "共 ${action.fingers.size} 根，点击管理"
                nativeCount > 0 ->
                    "共 $nativeCount 个子动作（自动精灵原生轨迹，本机按 fingers 执行）" +
                        (if (action.gestureRaw.isEmpty()) ""
                        else "；其中 ${action.gestureRaw.size} 项格式不认识，已原样保留")
                else -> "（还没有手指，点击添加）"
            }
            // 有原生轨迹、又没有我们的 fingers 时**不挂 entry**：
            // 这是一行"告知"，不是入口 —— 挂了点击会让用户以为点进去能改那批子动作，
            // 而实际上页面只认 fingers，点进去只会看到"还没有手指"。
            rows += if (nativeCount > 0 && action.fingers.isEmpty()) {
                Row("手指", text, Kind.READONLY)
            } else {
                Row("手指", text, Kind.ENTER, field = "fingers")
            }
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

        // 等待时间：**值 和 单位 必须分成两行**。
        //
        // ★ 原坑（P0）：这里把值拼成 "3 s" 却把**整串**写回 `delay`
        //   （field="delay"），而协议里 delay 只允许数字或区间（"1-3"）。
        //   执行器 randomIn("3 s") 解析不出数字 → 回落默认 200ms。
        //   用户把等待改成 3 秒，界面上显示 "3 s"，运行时几乎不等，没有任何报错。
        //   delay 为空时更糟：拼出来是 " s"，用户直接点确定就写进一个 "s"。
        //
        //   现在：`delay` 只放纯数字，单位单独一行写 `delayUnit`（FIELDS.md §0.1）。
        rows += Row(
            "等待",
            c.delay.orEmpty(),
            editKind("delay", c),
            field = "delay"
        )
        rows += Row(
            "等待单位",
            unitLabel(c.delayUnit),
            Kind.ENUM,
            UNITS,
            field = "delayUnit"
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

        // 重复间隔同理：值 / 单位拆两行（原坑与「等待」完全一样）。
        rows += Row(
            "重复间隔",
            c.repeatDelayValue.orEmpty(),
            numberKind("repeatDelayValue", c),
            field = "repeatDelayValue"
        )
        rows += Row(
            "重复间隔单位",
            unitLabel(c.repeatDelayUnit),
            Kind.ENUM,
            UNITS,
            field = "repeatDelayUnit"
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
        //
        // ★ 但**本次已经出了可编辑行**的键要跳过，否则同一个 keyCode 会出现两行：
        //   上面一行能改、下面一行只读，用户改完看到下面还停在旧值，以为没保存。
        //   只按本次实际出的行去重，不能用"全局可编辑键集合" —— 那样会把别的
        //   动作类型带来的同名键也一起藏掉（那种键应该继续只读展示）。
        val editableKeys = typeSpecific.map { it.field }.toSet()
        action.extra.forEach { (k, v) ->
            if (k in editableKeys) return@forEach
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
    private fun extraBool(pos: Z.PosData, key: String): Boolean = anyBool(pos.extra, key)

    /** 从 posData.extra 里读一个字符串。 */
    private fun extraStr(pos: Z.PosData, key: String): String? = anyStr(pos.extra, key)

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
            // ★ 标签叫「查找方式」，不叫「类型」。
            //
            //   动作本身也有一行「类型」（上面那行 ENUM，能改动作种类）。
            //   两行都叫「类型」时，用户想换动作种类很容易点到这行只读的，
            //   点了没反应 → 又变成"看得见点不动"。
            //   这一行回答的是"这条按什么找"，叫「查找方式」既准确又不撞名。
            rows += Row("${p}查找方式", typeLabel)
        }

        when (pos.type) {
            Z.PosType.LOCATION -> {
                val x = pos.x?.raw.orEmpty()
                val y = pos.y?.raw.orEmpty()
                rows += Row("${p}X", x, Kind.COORD, field = "$prefix.x")
                rows += Row("${p}Y", y, Kind.COORD, field = "$prefix.y")
            }

            Z.PosType.IMAGE -> {
                // ★ 模板行**无条件**输出。
                //
                //   原坑（P0）：整行包在 `pos.imageData?.let {}` 里 —— 新建的
                //   「点击图片」imageData 是 null，于是这一行根本不生成，界面上
                //   既没有模板行、也没有 ✂ 截取按钮（按钮只贴在 IMAGE 行上，
                //   见 ZjsEditorPage 的 makeRow）。结果动作建得出、存得下，
                //   却永远配不了模板；运行时 ZRealMatcher 只会报
                //   "图片位置没有模板数据"，用户完全看不出该去哪设置。
                //   条件侧（ZConditionEditor）一直是无条件输出的，这里对齐。
                val img = pos.imageData
                val size = when {
                    img == null -> "未设置"
                    img.width != null && img.height != null ->
                        "${img.width}×${img.height}，${img.data.length} 字符"
                    else -> "未知尺寸"
                }
                rows += Row("${p}模板", size, Kind.IMAGE, field = "$prefix.image")
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
        //
        // ★ 原坑（P1）：出的是 Kind.READONLY 且没有 field —— 看得见、改不了，
        //   也加不了（连"没设置"的情况都没有这一行）。现在出成 ENTER：
        //   点进去由页面编辑（条件和动作共用同一套条件弹窗），
        //   没设置时也显示一行"未设置，点击添加"，和「条件」行的写法一致。
        val aroundRaw = pos.extra["filterByAroundConditionSet"]
        val aroundSummary = aroundRaw?.let { raw ->
            runCatching {
                val obj = if (raw is org.json.JSONObject) raw else org.json.JSONObject(raw.toString())
                Z.Condition.parse(obj)?.let { ZConditionDescriber.summary(it) }
            }.getOrNull()
        }
        rows += Row(
            "${p}周围条件",
            aroundSummary ?: if (aroundRaw == null) "（未设置，点击添加）" else "（格式无法解析）",
            Kind.ENTER,
            field = "$prefix.around"
        )

        return rows
    }

    // ------------------------------------------------------------ 类型专属字段

    /**
     * 「点击」族：FIELDS.md 10.1-10.6 是同一个实现类（m25296），字段完全一致，
     * 只有 type / posData.type 的绑定不同。所以极速点击等开关对这一族统一处理。
     */
    private val CLICK_FAMILY = setOf(
        Z.ActionType.CLICK, Z.ActionType.CLICK_IMAGE, Z.ActionType.CLICK_TEXT,
        Z.ActionType.CLICK_COLOR, Z.ActionType.CLICK_NODE, Z.ActionType.AI_CLICK
    )

    /**
     * 动作类型专属字段（FIELDS.md §10）。
     *
     * 全部走 `action.extra`：模型刻意不给这些字段建属性，好让不认识的字段
     * 原样带过去。写回由 [ZFieldEditor] 的 extra 表负责，键名必须与协议一致。
     *
     * ★ 原坑：这里一行都没有 ——「按键」没有 keyCode、「跳转到」没有目标位置，
     *   动作能建、能存，但配不了；运行时 ZExecutor 只会 skip（"缺 keyCode"）
     *   或什么都不做。这类"能建不能配"最难查，因为界面看起来一切正常。
     *
     * 只做最小可用集（够把动作跑起来），其余字段仍以只读行兜底展示。
     */
    private fun typeRows(action: Z.Action): List<Row> {
        val e = action.extra
        val rows = ArrayList<Row>()

        // 极速点击（10.1）：按下立即抬起，不等系统回执。整族共用。
        if (action.type in CLICK_FAMILY) {
            rows += Row(
                "极速点击",
                if (anyBool(e, "highSpeedTap")) "是" else "否",
                Kind.SWITCH,
                field = "highSpeedTap"
            )
        }

        /*
         * 「运行多个动作」的子动作。
         *
         * 真实脚本的主体往往就是这种容器 —— 界面上只看得到"运行多个动作"
         * 一行，用户完全不知道里面装了什么，等价于黑盒。
         * 先给只读摘要（能看见、有多少个），编辑留到后面。
         */
        if (action.type == Z.ActionType.RUN_MULTI) {
            val n = action.scriptSet.size + action.scriptSetRaw.size
            rows += Row(
                "子动作",
                when {
                    action.scriptSet.isEmpty() && action.scriptSetRaw.isEmpty() ->
                        "（空，本机不会执行任何动作）"
                    action.scriptSetRaw.isNotEmpty() ->
                        "共 $n 个（其中 ${action.scriptSetRaw.size} 个格式不认识，已原样保留）"
                    else -> "共 $n 个，按顺序执行"
                },
                Kind.READONLY
            )
        }

        when (action.type) {
            // 按键（10.10）：Android KeyEvent keycode，0~288。
            // 用纯数字而不是下拉 —— 键名表 288 项，塞进下拉反而更难找，
            // 而"要按哪个键"通常是从别人的脚本或 keycode 表里抄来的。
            Z.ActionType.KEY -> rows += Row(
                "键值 keyCode",
                anyStr(e, "keyCode").orEmpty(),
                Kind.NUMBER,
                field = "keyCode"
            )

            // 输入内容（10.11）
            Z.ActionType.INPUT_TEXT -> {
                rows += Row(
                    "输入内容",
                    anyStr(e, "inputText").orEmpty(),
                    Kind.TEXT,
                    field = "inputText"
                )
                rows += Row(
                    "先清空",
                    if (anyBool(e, "clearBeforeInput")) "是" else "否",
                    Kind.SWITCH,
                    field = "clearBeforeInput"
                )
            }

            // 控制执行·跳转到（10.18）：目标位置，语法见 SPEC 6.5
            Z.ActionType.CONTROL_JUMP -> rows += Row(
                "跳转目标",
                anyStr(e, "jumpToPosition").orEmpty(),
                Kind.TEXT,
                field = "jumpToPosition"
            )

            // 控制执行·运行目标动作（10.17）
            Z.ActionType.CONTROL_RUN_AIM -> rows += Row(
                "目标动作",
                anyStr(e, "aimActionPosition").orEmpty(),
                Kind.TEXT,
                field = "aimActionPosition"
            )

            // 控制执行·停止（10.21）：字段名首字母大写，别改
            Z.ActionType.CONTROL_STOP -> rows += Row(
                "只结束当前子脚本",
                if (anyBool(e, "ContinueParentExecute")) "是" else "否",
                Kind.SWITCH,
                field = "ContinueParentExecute"
            )

            // 打开应用（10.13）
            Z.ActionType.OPEN_APP -> {
                rows += Row(
                    "包名/路径",
                    anyStr(e, "appPackageName").orEmpty(),
                    Kind.TEXT,
                    field = "appPackageName"
                )
                rows += Row(
                    "先关再开",
                    if (anyBool(e, "closeBeforeOpen")) "是" else "否",
                    Kind.SWITCH,
                    field = "closeBeforeOpen"
                )
                rows += Row(
                    "仅关闭",
                    if (anyBool(e, "onlyClose")) "是" else "否",
                    Kind.SWITCH,
                    field = "onlyClose"
                )
            }

            // 打开链接（10.14）
            Z.ActionType.OPEN_LINK -> rows += Row(
                "链接",
                anyStr(e, "link").orEmpty(),
                Kind.TEXT,
                field = "link"
            )

            // 系统提示（10.23）
            Z.ActionType.PROMPT -> {
                rows += Row(
                    "提示方式",
                    anyStr(e, "promptType") ?: "toast",
                    Kind.ENUM,
                    listOf("toast", "alert", "notification"),
                    field = "promptType"
                )
                rows += Row(
                    "提示内容",
                    anyStr(e, "promptText").orEmpty(),
                    Kind.TEXT,
                    field = "promptText"
                )
            }

            else -> Unit
        }
        return rows
    }

    /** 从任意 extra map 里读布尔（协议里写成 true/false 或 "是"）。 */
    private fun anyBool(m: Map<String, Any?>, key: String): Boolean =
        when (val v = m[key]) {
            is Boolean -> v
            is String -> v == "true" || v == "是" || v == "1"
            is Number -> v.toInt() != 0
            else -> false
        }

    /** 从任意 extra map 里读字符串。 */
    private fun anyStr(m: Map<String, Any?>, key: String): String? =
        m[key]?.toString()?.takeIf { it.isNotBlank() }
}
