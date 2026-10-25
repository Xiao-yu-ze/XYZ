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
        val field: String = "",
        /**
         * 这一行字段的说明，界面上「?」点开显示的就是它。
         *
         * 默认取 [ZFieldHelp]（按 [field] 查，与前缀无关）；
         * 需要覆盖时才在这里显式给。
         */
        val help: String? = null
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
            field = "type",
            /*
             * 「类型」这行的问号要解释**这个动作是干嘛的**（"点击节点"和"点击图片"
             * 差在哪、本机哪部分做不到），而不是通用说明"改了类型会重建位置对象" ——
             * 用户的原话是"不知道每个节点是干嘛的"。通用说明仍在 ZFieldHelp 的
             * "type" 里，靠 field 兜底。
             */
            help = ZFieldHelp.ofAction(ZFieldEditor.actionTypeHelpKey(action.type))
        )
        action.type.controlRunType?.let { rows += Row("控制方式", Z.controlRunTypeLabel(it) ?: it) }
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
        /*
         * ★ 「位置」只给**真的有位置字段**的动作显示。
         *
         *   滑动/多指手势用的是 startPos / endPos（FIELDS.md 10.7），
         *   执行器也只读这两个（ZExecutor 的 SWIPE 分支）。
         *   原来这里无条件把 posData 也摊出来，于是一条滑动会同时出现
         *   「位置 x,y」和「起点/终点」——两套坐标并排，用户分不清哪个生效，
         *   而「位置」在滑动里其实被完全忽略（是转换/换类型时留下的残留）。
         *   藏掉它，界面就不再自相矛盾。
         */
        val usesStartEnd = action.type == Z.ActionType.SWIPE ||
            action.type == Z.ActionType.MULTI_SWIPE
        val posParams = ArrayList<Row>()
        val posOthers = ArrayList<Row>()
        fun addPos(prefix: String, pos: Z.PosData, showPrefix: Boolean) {
            val g = posRows(prefix, pos, showPrefix)
            posLines.addAll(g.recognize)
            posParams.addAll(g.params)
            posOthers.addAll(g.others)
        }
        // 滑动/多指手势用的是 startPos/endPos，执行器只读这两个，
        // 所以「位置」块对它们不显示（否则两套坐标并排，分不清哪个生效）
        if (!usesStartEnd) {
            action.posData?.let { addPos("位置", it, showPrefix = false) }
        }
        action.startPos?.let { addPos("起点", it, showPrefix = true) }
        action.endPos?.let { addPos("终点", it, showPrefix = true) }
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
        val typeSpecific = typeRows(action) + posParams
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
        /*
         * 「运行多个动作」的子动作列表：必须能点进去增删。
         * 之前只显示一行「子动作 共 N 个」的只读文字 —— 于是这个动作
         * 在界面上等于不可用（用户根本没法往里加东西）。
         */
        if (action.type == Z.ActionType.RUN_MULTI) {
            val n = action.scriptSet.size + action.scriptSetRaw.size
            rows += Row(
                "子动作",
                if (n == 0) "（还没有子动作，点击添加）" else "共 $n 个，点击管理",
                Kind.ENTER,
                field = "scriptSet"
            )
            /*
             * runMode：协议有 normal / random / multiThread 三种，
             * 本机只有顺序执行 —— random 不随机、multiThread 不同时。
             * 只在设了非 normal 时提示，免得平时多一行噪音。
             */
            val runMode = action.extra["runMode"]?.toString().orEmpty().trim()
            if (runMode.isNotEmpty() && runMode != "normal") {
                rows += Row(
                    "说明",
                    "runMode=$runMode 本机按 normal（顺序）执行：不会乱序、也不会真并发",
                    Kind.READONLY
                )
            }
        }

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
            /*
             * ★ 这里必须直接铺进 rows，不能复用上面的 addPos()。
             *
             *   addPos 往 posLines/posParams/posOthers 里攒，而那三个列表
             *   在手指循环**之前**就已经铺进 rows 了 —— 再往里加不会有人再取，
             *   于是每根手指只剩「周围条件」一行（它挂在 posOthers 上，
             *   而 posOthers 是后面才铺的），X/Y/起终点全消失（测试当场抓到）。
             */
            fun emit(prefix: String, pos: Z.PosData) {
                val g = posRows(prefix, pos, showPrefix = true)
                rows.addAll(g.recognize)
                rows.addAll(g.params)
                rows.addAll(g.others)
            }
            finger.startPos?.let { emit("手指$n 起点", it) }
            finger.endPos?.let { emit("手指$n 终点", it) }
        }

        // ---- 等待候选（我们自己的节点，等价于 Maa 的 next）
        //
        // 候选本身不在这里编辑 —— 它们的条件就是各自的位置对象，
        // 在动作列表里逐条配更顺手。这里只把「等谁、等多久」摊开，
        // 让用户一眼看到这条到底在等什么，而不是一个空壳。
        if (action.type == Z.ActionType.WAIT_CANDIDATES) {
            /*
             * 候选原来全是 READONLY：看得见、改不了，也没有「?」。
             * 现在每个候选的条件类型/颜色/文字/跳转目标都能改，
             * 还能加/删；超时去哪也能改。
             */
            rows += Row("候选数", "${action.candidates.size} 个，按顺序试", Kind.READONLY)
            action.candidates.forEachIndexed { i, c ->
                val n = i + 1
                val ct = c.condition?.type
                val condLabel = when (ct) {
                    null -> "（无条件，一定命中）"
                    Z.ConditionType.IMAGE -> "出现图片"
                    Z.ConditionType.TEXT -> "出现文字"
                    Z.ConditionType.COLOR -> "出现颜色"
                    else -> ct.wire
                }
                rows += Row(
                    "候选 $n", "$condLabel → ${c.jumpTo ?: "（往下走）"}", Kind.READONLY
                )
                val condOpts = listOf("（无条件）", "出现颜色", "出现文字", "出现图片", "出现节点")
                rows += Row(
                    "候选 $n 条件", condLabel.removePrefix("（").removeSuffix("）"),
                    Kind.ENUM, condOpts, field = "cand.$i.cond"
                )
                if (ct == Z.ConditionType.COLOR) {
                    rows += Row(
                        "候选 $n 颜色",
                        c.condition?.posData?.color.orEmpty(),
                        Kind.TEXT, field = "cand.$i.color"
                    )
                }
                if (ct == Z.ConditionType.TEXT) {
                    rows += Row(
                        "候选 $n 文字",
                        c.condition?.posData?.text.orEmpty(),
                        Kind.TEXT, field = "cand.$i.text"
                    )
                }
                rows += Row(
                    "候选 $n 跳转", c.jumpTo.orEmpty(),
                    Kind.TEXT, field = "cand.$i.jumpTo"
                )
                rows += Row(
                    "候选 $n 删除", "保留",
                    Kind.ENUM, listOf("保留", "删除"), field = "cand.$i.del"
                )
            }
            rows += Row(
                "添加候选", "点这里加一个（无条件、跳出本节点）",
                Kind.ENTER, field = "cand.add"
            )
            // 单位是 ms，值就必须是数字；"0 = 一直等"写进说明，而不是顶掉数值
            rows += Row(
                "总超时(ms)",
                (action.waitTimeoutMs ?: 0).toString(),
                Kind.NUMBER, field = "waitTimeoutMs"
            )
            rows += Row(
                "轮询间隔(ms)",
                (action.waitIntervalMs ?: 200).toString(),
                Kind.NUMBER, field = "waitIntervalMs"
            )
            // 超时去哪 —— 对应 Maa 的 on_error
            /*
             * ★ 判"跳到标识"要看**键在不在**，不能看值非空。
             *
             *   刚把「超时后」切成「跳到标识」时，值还是空的 ——
             *   按"值非空"判断会立刻回落成「往下走」，那一行压根不出现，
             *   用户说"ontimeoutjump 点不了、也没有问号"就是这么来的。
             */
            val mode = when {
                action.extra.containsKey("onTimeoutJump") -> "跳到标识"
                anyBool(action.extra, "onTimeoutStop") -> "停止任务"
                else -> "往下走"
            }
            rows += Row(
                "超时后", mode, Kind.ENUM,
                listOf("往下走", "停止任务", "跳到标识"), field = "onTimeoutMode"
            )
            if (mode == "跳到标识") {
                rows += Row(
                    "超时跳转",
                    (action.extra["onTimeoutJump"] as? String).orEmpty(),
                    Kind.TEXT, field = "onTimeoutJump"
                )
            }
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
        rows += typeOtherRows(action)
        rows += posOthers
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

    /**
     * 位置字段分成三组返回。
     *
     * ★ 为什么要分组
     *
     *   原来所有位置字段挤在一个「识别」小节里：模板、相似度、X/Y、
     *   范围、选第几个、偏移、周围条件、快速搜图、搜索模式、缩放方式
     *   十几行平铺。用户看不出哪些是"怎么找"、哪些是"找到之后怎么做"、
     *   哪些是几乎不用动的开关。
     *
     *   - recognize：怎么找（模板/文字/颜色/相似度/预览）
     *   - params   ：找到之后干什么（坐标、范围、选第几个、偏移、周围条件）
     *   - others   ：调优开关（快速搜图/搜索模式/缩放方式/OCR 模式）
     */
    private data class PosGroup(
        val recognize: List<Row> = emptyList(),
        val params: List<Row> = emptyList(),
        val others: List<Row> = emptyList()
    )

    private fun posRows(prefix: String, pos: Z.PosData, showPrefix: Boolean = true): PosGroup {
        // 小节已经说明了这是"位置"，每行再带「位置」两个字既重复又占宽度
        val p = if (showPrefix) "$prefix " else ""
        val rec = ArrayList<Row>()
        val par = ArrayList<Row>()
        val oth = ArrayList<Row>()

        // 坐标类型不单列"查找方式"：滑动/点击的位置对用户就是一对 (x,y)。
        if (pos.type != Z.PosType.LOCATION) {
            val typeLabel = when (pos.type) {
                Z.PosType.IMAGE -> "图片"
                Z.PosType.TEXT -> "文字"
                Z.PosType.COLOR -> "颜色"
                Z.PosType.NODE -> "节点"
                Z.PosType.AI_LOC -> "AI位置"
                Z.PosType.LOCATION -> "坐标"
            }
            rec += Row("${p}查找方式", typeLabel)
        }

        when (pos.type) {
            // 固定坐标：X/Y 属于"找到之后去哪儿"，所以放参数
            Z.PosType.LOCATION -> {
                par += Row("${p}X", pos.x?.raw.orEmpty(), Kind.COORD, field = "$prefix.x")
                par += Row("${p}Y", pos.y?.raw.orEmpty(), Kind.COORD, field = "$prefix.y")
            }

            Z.PosType.IMAGE -> {
                // 模板行无条件输出：新建的「点击图片」imageData 是 null，
                // 包在 let 里会导致这行和 ✂ 按钮都不出现，动作永远配不了模板。
                val img = pos.imageData
                val size = when {
                    img == null -> "未设置"
                    img.width != null && img.height != null ->
                        "${img.width}×${img.height}，${img.data.length} 字符"
                    else -> "未知尺寸"
                }
                rec += Row("${p}模板", size, Kind.IMAGE, field = "$prefix.image")
                rec += Row(
                    "${p}相似度",
                    pos.minSimilarPercent?.let { "$it%" } ?: "未设置（默认 90%）",
                    Kind.PERCENT, field = "$prefix.minSimilarPercent"
                )
                // 调优开关归到「其他」
                // 本机刻意不实现（只影响速度、不影响判定），所以不给开关：
                // 拨了不生效的开关比没有这个字段更糟。
                oth += Row(
                    "${p}快速搜图（本机不生效）",
                    if (extraBool(pos, "quickSearch")) "是" else "否",
                    Kind.SWITCH, field = "$prefix.quickSearch"
                )
                val sm = extraStr(pos, "searchMode") ?: "color_2.21"
                // 文件里可能是 HOG 之类我们没实现的模式：保留在选项里（并标注），
                // 否则弹窗预选会落到第一项，用户点一下就把原值改掉了。
                val smOpts = buildList {
                    add("color_2.21"); add("outline_2.21"); add("COLOR")
                    if (sm !in this) add(sm)
                }
                                oth += Row(
                    "${p}搜索模式",
                    if (sm in listOf("color_2.21", "COLOR", "outline_2.21")) sm else "$sm（本机按默认模式跑）",
                    Kind.ENUM, smOpts, field = "$prefix.searchMode"
                )
                oth += Row(
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
                rec += Row("${p}文字", pos.text.orEmpty(), Kind.TEXT, field = "$prefix.text")
                rec += Row(
                    "${p}相似度",
                    pos.similarPercent?.let { "$it%" } ?: "未设置（默认 90%）",
                    Kind.PERCENT, field = "$prefix.similarPercent"
                )
                val om = extraStr(pos, "ocrMode") ?: "local_v2"
                val omOpts = buildList {
                    add("local_v2"); add("local")
                    if (om !in this) add(om)   // 文件里是 online 时也要能预选回去
                }
                /*
                 * ★ 这个字段运行时不读（ZExecutor/ZRealMatcher 零引用）：
                 *   本机只有一套本地 OCR，local_v2 / local / online 三个值
                 *   跑起来完全一样。值照样存取（导给自动精灵还要用），
                 *   但名字里标出来，别让人以为换了引擎。
                 */
                oth += Row(
                    "${p}OCR 模式（本机不生效）",
                    if (om == "online") "online（本机按本地跑）" else om,
                    Kind.ENUM, omOpts, field = "$prefix.ocrMode"
                )
            }

            Z.PosType.TEXT -> {
            /*
             * 找字滤镜（协议 `text.filter`，FIELDS:202）：OCR 之前对画面做预处理。
             * 三种实现（grey/bw/reverse）在匹配器里早就有，只是之前只给图片用。
             */
            val tf = extraStr(pos, "filter") ?: extraStr(pos, "imageFilter")
            val tfOpts = buildList {
                add("（无）"); add("grey"); add("bw"); add("reverse")
                if (!tf.isNullOrBlank() && tf !in this) add(tf)
            }
            oth += Row(
                "${p}找字滤镜",
                tf ?: "（无）",
                Kind.ENUM, tfOpts, field = "$prefix.filter"
            )
        }

        Z.PosType.COLOR -> {
            /*
             * 颜色范围（从 Maa 的 lower/upper 来的）。
             *
             * Maa 的颜色判定是"每通道落在 [lower,upper]"——范围，不是某个精确色。
             * 编辑器原来只显示 color 一个值（范围中点），用户会以为判定就靠它，
             * 于是把相似度往 100 调，反而认不出来。把真实依据摊出来。
             */
            run {
                val lo = pos.colorLower
                val hi = pos.colorUpper
                if (lo != null && hi != null && lo.size >= 3 && hi.size >= 3) {
                    fun hex(c: List<Int>) = String.format("#%02x%02x%02x", c[0], c[1], c[2])
                    rec += Row(
                        "${p}颜色范围",
                        "${hex(lo)} ~ ${hex(hi)}（范围判定，不是精确色）",
                        Kind.READONLY
                    )
                }
            }
                rec += Row("${p}颜色", pos.color.orEmpty(), Kind.TEXT, field = "$prefix.color")
                // 颜色必须能从画面上取，不靠手输 #RRGGBB（手输要么靠截图取色已经偏了，
                // 要么填错一个字符就永远匹配不到，而失败信息里只有"没找到这个颜色"）
                rec += Row(
                    "${p}从屏幕取色",
                    if (pos.color.isNullOrBlank()) "点右边按钮在画面上取一个颜色"
                    else "重新取色（当前 #${pos.color}）",
                    Kind.COORD, field = "$prefix.colorPick"
                )
                rec += Row(
                    "${p}相似度",
                    pos.similarPercent?.let { "$it%" } ?: "未设置（默认 90%）",
                    Kind.PERCENT, field = "$prefix.similarPercent"
                )
                // 指定位置 = 只看一个点，属于"找到之后怎么判"，放参数
                par += Row(
                    "${p}指定位置 X", extraStr(pos, "limitPosX").orEmpty(),
                    Kind.COORD, field = "$prefix.limitPosX"
                )
                par += Row(
                    "${p}指定位置 Y", extraStr(pos, "limitPosY").orEmpty(),
                    Kind.COORD, field = "$prefix.limitPosY"
                )
            }

            Z.PosType.NODE -> {
                rec += Row("${p}节点", pos.text.orEmpty(), Kind.TEXT, field = "$prefix.text")
            }

            Z.PosType.AI_LOC -> {
                rec += Row("${p}AI", pos.text.orEmpty(), Kind.TEXT, field = "$prefix.text")
            }
        }

        // 预览：只对"需要查找"的类型出现（固定坐标没有"查找"这回事）
        if (pos.type == Z.PosType.IMAGE || pos.type == Z.PosType.TEXT ||
            pos.type == Z.PosType.COLOR || pos.type == Z.PosType.NODE
        ) {
            rec += Row(
                "${p}预览", "点这里在画面上看命中位置",
                Kind.ENTER, field = "$prefix.preview"
            )
        }

        // ---- 参数：搜索范围与命中之后怎么处理
        // 这四行必须无条件出现：新建的位置只有 type，全都 null，
        // 按"设过了才显示"写会导致用户永远没法给它设范围/选第几个。
        par += Row("${p}范围", pos.limitArea?.toString() ?: "", Kind.AREA, field = "$prefix.limitArea")
        par += Row("${p}选第几个", pos.indexNum ?: "", Kind.TEXT, field = "$prefix.indexNum")
        par += Row("${p}X偏移", pos.xOffset?.raw ?: "", Kind.TEXT, field = "$prefix.xOffset")
        par += Row("${p}Y偏移", pos.yOffset?.raw ?: "", Kind.TEXT, field = "$prefix.yOffset")

        // 周围条件（FIELDS.md:147）：只作用于这个位置的候选点过滤
        val aroundRaw = pos.extra["filterByAroundConditionSet"]
        val aroundSummary = aroundRaw?.let { raw ->
            runCatching {
                val obj = if (raw is org.json.JSONObject) raw else org.json.JSONObject(raw.toString())
                Z.Condition.parse(obj)?.let { ZConditionDescriber.summary(it) }
            }.getOrNull()
        }
        oth += Row(
            "${p}周围条件",
            aroundSummary ?: if (aroundRaw == null) "（未设置）" else "（格式无法解析）",
            Kind.ENTER, field = "$prefix.around"
        )

                /*
         * 节点位置：本机用不了（游戏在虚拟屏，无障碍拿不到那层窗口）。
         * 直接在这里说清楚 —— 否则用户会把节点字段配满、跑起来恒不成立。
         */
        if (pos.type == Z.PosType.NODE) {
            rec.add(
                Row(
                    "说明",
                    "游戏在虚拟屏上，无障碍拿不到那层窗口 → 节点查找不会命中；改用文字/颜色/图片",
                    Kind.READONLY
                )
            )
        }

return PosGroup(rec, par, oth)
    }

    // ------------------------------------------------------------ 类型专属字段

    /**
     * 「点击」族：FIELDS.md 10.1-10.6 是同一个实现类（m25296），字段完全一致，
     * 只有 type / posData.type 的绑定不同。所以极速点击等开关对这一族统一处理。
     */
    /** 我们自己的五种「游戏动作」（种/喂/铲/点格子/神器）。 */
    private val PVZ_TYPES = setOf(
        Z.ActionType.PVZ_PLANT, Z.ActionType.PVZ_FEED, Z.ActionType.PVZ_SHOVEL,
        Z.ActionType.PVZ_TAP_CELL, Z.ActionType.PVZ_ARTIFACT
    )

    private val CLICK_FAMILY = setOf(
        Z.ActionType.CLICK, Z.ActionType.CLICK_IMAGE, Z.ActionType.CLICK_TEXT,
        Z.ActionType.CLICK_COLOR
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
    /**
     * 类型专属的「调优开关」，归到「其他」小节。
     *
     * ★ 这里全部是**只读**：它们在协议里存在、我们也能原样存取，
     *   但本机的执行路径根本不读它们。做成可点的开关等于骗人 ——
     *   用户拨成"是"、以为更快了，实际什么都没变，而且更容易把
     *   "脚本没变快"归因到别处。
     */
    private fun typeOtherRows(action: Z.Action): List<Row> {
        val e = action.extra
        val rows = ArrayList<Row>()
        if (action.type in CLICK_FAMILY) {
            // 极速点击（10.1）：按下立即抬起、不等回执。本机注入本来就异步，不需要它。
            /*
             * 极速点击（协议 10.1）。现在**真的生效**了：
             * 注入层不再等 `MODE_WAIT_FOR_FINISH`，按下/抬起间隔压到 1ms。
             * 代价照自动精灵的原文写出来 —— 连点会少量丢点，用户得知道。
             */
            rows += Row(
                "极速点击模式",
                if (anyBool(e, "highSpeedTap")) "是" else "否",
                Kind.SWITCH, field = "highSpeedTap"
            )
            if (anyBool(e, "highSpeedTap")) {
                rows += Row(
                    "说明",
                    "极速模式不等点击结束（省 5~30ms）；连续快速点击可能少部分点击失效",
                    Kind.READONLY
                )
            }
        }
        if (action.type == Z.ActionType.INPUT_TEXT) {
            // clearBeforeInput（10.11）：先清空再输入。本机是"直接输入"，
            // 不清空 —— 是否清空取决于目标输入框本身。
            rows += Row(
                "先清空（本机不生效）",
                if (anyBool(e, "clearBeforeInput")) "是" else "否",
                Kind.SWITCH, field = "clearBeforeInput"
            )
        }
        return rows
    }

    /** 取 `value` 里某个布尔标记为 true（或 wantFalse 时为 false）的变量名。 */
    private fun varsFlagNames(
        e: Map<String, Any?>,
        key: String,
        wantFalse: Boolean = false
    ): List<String> {
        val raw = e["vars"] ?: return emptyList()
        val arr = runCatching {
            when (raw) {
                is org.json.JSONArray -> raw
                is String -> org.json.JSONArray(raw)
                else -> org.json.JSONArray(raw.toString())
            }
        }.getOrNull() ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            if (name.isEmpty()) continue
            val v = o.optJSONObject("value") ?: continue
            val flag = when {
                !v.has(key) -> false
                else -> v.optBoolean(key, false)
            }
            if ((!wantFalse && flag) || (wantFalse && v.has(key) && !flag)) out.add(name)
        }
        return out
    }

    /** 有没有任何一个变量勾了"记住输入值"。 */
    private fun varsRemember(e: Map<String, Any?>): Boolean {
        val raw = e["vars"] ?: return false
        val arr = runCatching {
            when (raw) {
                is org.json.JSONArray -> raw
                is String -> org.json.JSONArray(raw)
                else -> org.json.JSONArray(raw.toString())
            }
        }.getOrNull() ?: return false
        for (i in 0 until arr.length()) {
            val v = arr.optJSONObject(i)?.optJSONObject("value") ?: continue
            if (v.optBoolean("rememberInputValue", false)) return true
        }
        return false
    }

    /** 把 `extra["vars"]`（[{name, value:{varType,valueExp}}]）摊成 name=value。 */
    private fun varsPairsOf(e: Map<String, Any?>): List<Pair<String, String>> {
        val raw = e["vars"] ?: return emptyList()
        val arr = runCatching {
            when (raw) {
                is org.json.JSONArray -> raw
                is String -> org.json.JSONArray(raw)
                else -> org.json.JSONArray(raw.toString())
            }
        }.getOrNull() ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            if (name.isEmpty()) continue
            val v = o.optJSONObject("value")
            val exp = v?.optString("valueExp").orEmpty()
            out.add(name to exp)
        }
        return out
    }

    private fun typeRows(action: Z.Action): List<Row> {
        val e = action.extra
        val rows = ArrayList<Row>()

        /*
         * 游戏动作（我们自己的五种）。
         *
         * 参数刻意只有「卡槽 / 行 / 列」三项 —— 这正是玩家脑子里的说法，
         * 像素点位由 [PvzCoords] 查表，改表不用改脚本。
         */
        if (action.type in PVZ_TYPES) {
            if (action.type == Z.ActionType.PVZ_PLANT) {
                val slot = e["pvzSlot"]?.toString().orEmpty()
                rows += Row(
                    "卡槽",
                    if (slot.isBlank()) "（未选）" else "$slot 号",
                    Kind.ENUM,
                    (1..8).map { it.toString() },
                    field = "pvzSlot",
                    help = ZFieldHelp.ofAction("pvzSlot")
                )
            }
            val rowNo = e["pvzRow"]?.toString().orEmpty()
            val colNo = e["pvzCol"]?.toString().orEmpty()
            rows += Row(
                "行",
                if (rowNo.isBlank()) "（未选）" else rowNo,
                Kind.ENUM,
                (1..PvzCoords.ROWS).map { it.toString() },
                field = "pvzRow",
                help = ZFieldHelp.ofAction("pvzRow")
            )
            rows += Row(
                "列",
                if (colNo.isBlank()) "（未选）" else colNo,
                Kind.ENUM,
                (1..PvzCoords.COLS).map { it.toString() },
                field = "pvzCol",
                help = ZFieldHelp.ofAction("pvzCol")
            )
            // 落点回显：选了行列就把实际会点到的像素也显示出来，便于核对
            val r = rowNo.toIntOrNull()
            val c = colNo.toIntOrNull()
            val pt = if (r != null && c != null) PvzCoords.cell(r, c) else null
            rows += Row(
                "落点",
                if (pt == null) "（选好行列后显示）" else "(${pt[0]}, ${pt[1]})"
            )
            rows += Row(
                "属于哪条链",
                PvzChainModel.label(PvzChainModel.chainOf(action)),
                Kind.ENUM,
                PvzChainModel.LABELS.map { it.second },
                field = "pvzChain",
                help = ZFieldHelp.ofAction("pvzChain")
            )
            rows += Row(
                "在棋盘上选格子",
                if (r != null && c != null) "第${r}行第${c}列 ›" else "点开选 ›",
                Kind.ENTER,
                field = "pvz.board",
                help = ZFieldHelp.ofAction("pvz.board")
            )
        }


        /*
         * 「运行多个动作」的子动作。
         *
         * 真实脚本的主体往往就是这种容器 —— 界面上只看得到"运行多个动作"
         * 一行，用户完全不知道里面装了什么，等价于黑盒。
         * 先给只读摘要（能看见、有多少个），编辑留到后面。
         */
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

            /*
         * 运行JS代码（10.25）：jsCode 原样跑在 Rhino 沙箱里。
         *
         * ★ 变量和执行器的运行时变量是**同一份**，所以「设置变量」建的
         *   变量在这里直接当全局变量用；改动会写回去。
         *   标签里把可用的东西写清楚，用户不用去翻文档。
         */
        Z.ActionType.RUN_JS -> {
            rows += Row(
                "JS 代码",
                anyStr(e, "jsCode").orEmpty(),
                Kind.TEXT,
                field = "jsCode"
            )
            rows += Row(
                "可用变量/函数",
                "zdjl.getVar/setVar（同自动精灵）；console.log 写脚本日志",
                Kind.READONLY
            )
        }

        /*
         * 设置变量（10.24）：`vars` 是 [{name, value}] 数组。
         *
         * ★ 以前这里**一行都没有** —— 用户能在脚本里"设置变量"，
         *   却在界面上设不了初值，只能去改 JSON。而"给初始关卡设初值"
         *   正是这个动作最常见的用法。
         *   用一行 `名字=值;名字2=值2` 表达，读写都是它。
         */
        Z.ActionType.SET_VARS -> {
            val pairs = varsPairsOf(e)
            rows += Row(
                "变量",
                if (pairs.isEmpty()) "（空）" else pairs.joinToString("; ") { "${it.first}=${it.second}" },
                Kind.TEXT,
                field = "vars"
            )
            /*
             * 「从键盘输入」= 协议里的 `value.showInput`（FIELDS §8.2）。
             * 勾上的变量在**点运行时**会弹窗问值，弹窗预填下面这行的配置值。
             */
            val askNames = varsFlagNames(e, "showInput")
            rows += Row(
                "运行时输入",
                if (askNames.isEmpty()) "（无，全部用配置值）" else askNames.joinToString("; "),
                Kind.TEXT,
                field = "vars.input"
            )
            val optional = varsFlagNames(e, "mustInput", wantFalse = true)
            rows += Row(
                "不必填",
                if (optional.isEmpty()) "（都必填）" else optional.joinToString("; "),
                Kind.TEXT,
                field = "vars.mustInput"
            )
            rows += Row(
                "记住输入值",
                if (varsRemember(e)) "是" else "否",
                Kind.SWITCH,
                field = "vars.remember"
            )
            rows += Row(
                "说明",
                "运行JS代码 里用 zdjl.getVar/setVar 读写；也能用 \${名字} 代换进查找文字",
                Kind.READONLY
            )
        }

        /*
         * 执行脚本（10.15）：filePath 指向另一个脚本文件。
         *
         * ★ 以前这里**一行都没有**，而 ZFieldEditor 也没有 filePath 的写回通道 ——
         *   用户能从「添加动作」里加一条「执行脚本」，然后永远跑不起来
         *   （skip「缺 filePath」），也没法在界面上填。整条链路是死的。
         */
        Z.ActionType.RUN_SCRIPT -> {
            rows += Row(
                "脚本文件名",
                anyStr(e, "filePath").orEmpty(),
                Kind.TEXT,
                field = "filePath"
            )
            rows += Row(
                "跑完回到本脚本",
                if (anyBool(e, "continueCurrentAfterFinish")) "是" else "否",
                Kind.SWITCH,
                field = "continueCurrentAfterFinish"
            )
            rows += Row(
                "说明",
                "留空则跳过；「跑完回到本脚本」为否时，子脚本结束就等于整条任务结束",
                Kind.READONLY
            )
        }

        // 输入内容（10.11）
            Z.ActionType.INPUT_TEXT -> {
                rows += Row(
                    "输入内容",
                    anyStr(e, "inputText").orEmpty(),
                    Kind.TEXT,
                    field = "inputText"
                )
            }

            /*
         * 设置变量的交互弹窗（协议 10.24 的 dialog*）。
         *
         * 这六个字段执行器早就支持了（点确定赋值、点取消跑取消动作、
         * 只问一次、到点自动点），但界面一直没有行 —— 等于配不了。
         */
        Z.ActionType.SET_VARS -> {
            rows += Row("弹窗标题", anyStr(e, "dialogTitle").orEmpty(), Kind.TEXT, field = "dialogTitle")
            rows += Row("确定按钮", anyStr(e, "dialogOKText").orEmpty(), Kind.TEXT, field = "dialogOKText")
            rows += Row("取消按钮", anyStr(e, "dialogCancelText").orEmpty(), Kind.TEXT, field = "dialogCancelText")
            rows += Row(
                "自动点",
                anyStr(e, "dialogAutoClickBtn").orEmpty().ifBlank { "（不自动）" },
                Kind.ENUM, listOf("（不自动）", "ok", "cancel"),
                field = "dialogAutoClickBtn"
            )
            rows += Row(
                "只问一次",
                if (anyBool(e, "dialogShowOnce")) "是" else "否",
                Kind.SWITCH, field = "dialogShowOnce"
            )
            if (e["dialogCancelAction"] != null) {
                rows += Row(
                    "取消动作",
                    "已配置（点取消时执行）",
                    Kind.READONLY
                )
            }
            rows += Row(
                "说明",
                "标题/按钮都留空 = 不弹窗，直接赋值。取消动作目前只能在文件里写。",
                Kind.READONLY
            )
        }

        // 控制执行·跳转到（10.18）：目标位置，语法见 SPEC 6.5
            Z.ActionType.CONTROL_JUMP -> rows += Row(
                "跳转目标",
                anyStr(e, "jumpToPosition").orEmpty(),
                Kind.TEXT,
                field = "jumpToPosition"
            )

            // 系统提示（10.23）：三种 promptType 本机都只弹 toast
        Z.ActionType.PROMPT -> {
            rows += Row("提示内容", anyStr(e, "promptText").orEmpty(), Kind.TEXT, field = "promptText")
            rows += Row("标题", anyStr(e, "promptTitle").orEmpty(), Kind.TEXT, field = "promptTitle")
            val pt = anyStr(e, "promptType").orEmpty().trim().ifBlank { "toast" }
            rows += Row(
                "提示方式",
                pt,
                Kind.ENUM, listOf("toast", "alert", "notification"),
                field = "promptType"
            )
            if (pt != "toast") {
                rows += Row(
                    "说明",
                    "本机只实现 toast：选 $pt 也会按 toast 弹，且不会阻塞等按钮",
                    Kind.READONLY
                )
            }
        }

        // 控制执行·停止（10.21）：字段名首字母大写，别改
            Z.ActionType.CONTROL_STOP -> rows += Row(
                "只结束当前子脚本",
                if (anyBool(e, "ContinueParentExecute")) "是" else "否",
                Kind.SWITCH,
                field = "ContinueParentExecute"
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
        /*
         * 识别后等待：Maa 的 pre_delay 真正的位置（识别 → 等待 → 动作）。
         * 我们的 delay 在识别之前，补上这一段才能和 Maa 对齐。
         */
        if (action.type in CLICK_FAMILY) {
            rows += Row(
                "识别后等待",
                anyStr(e, "delayAfterLocate").orEmpty(),
                Kind.NUMBER, field = "delayAfterLocate"
            )
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
