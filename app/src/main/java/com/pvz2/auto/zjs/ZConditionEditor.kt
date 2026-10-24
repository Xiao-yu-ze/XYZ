package com.pvz2.auto.zjs

import com.pvz2.auto.zjs.ZActionDescriber.Kind

/**
 * 条件的增删改与字段摊平。
 *
 * ## 条件字段分三层，三层都要能改
 *
 * ```
 * 通用字段   runWhenFalse / disabled / repeatWhenFalse / checkBeforeDelay / desc
 * 位置对象   image / text / colorData / node（键名随类型变，见 ZModel.Condition.toJson）
 * 类型专属   每类各自不同
 * ```
 *
 * 只做「通用开关 + 几个数字」的话，用户选完条件类型就卡住了：
 * 出现图片没有模板可截、出现颜色没有颜色可填、在时间之后没有时间可输。
 * 条件会一直是默认值，而且**不会报错** —— 动作永远不跑，看起来像脚本坏了。
 */
object ZConditionEditor {

    /**
     * 可在「条件类型」里选的条件类型。顺序与 FIELDS.md:264-278 一致。
     *
     * ## AROUND 不在这里（故意的）
     *
     * `AroundCondition` 是协议里的第 13 种，但自动精灵**自己的类型下拉里也没有它**：
     *
     * ```js
     * // m72957：BF = 下拉用的列表
     * o = Object.keys(a).filter(e => e !== a.AroundCondition)
     * ```
     *
     * 它只被 `posData.filterByAroundConditionSet`（找位置的"周围条件"）使用。
     * 放进下拉的后果：用户能选出一个**自动精灵自己都不会生成**的条件，
     * 导出的脚本在自动精灵里行为不可预期（它按 type 查表，查不到就当条件恒 false）。
     *
     * [create] 仍然保留 AROUND 分支 —— 旧脚本里可能已经有，得能显示、能编辑。
     */
    val TYPE_LABELS: List<Pair<Z.ConditionType, String>> = listOf(
        Z.ConditionType.IMAGE to "出现图片",
        Z.ConditionType.TEXT to "出现文字",
        Z.ConditionType.COLOR to "出现颜色",
        Z.ConditionType.NODE to "出现节点",
        Z.ConditionType.AI_CLOUD to "AI 云识别（本机不支持）",
        Z.ConditionType.LIMIT_RUN_TIMES to "限制运行次数",
        Z.ConditionType.SCRIPT_RUN_STATE to "动作运行状态",
        Z.ConditionType.RANDOM to "随机运行",
        Z.ConditionType.TIME_AFTER to "在时间之后",
        Z.ConditionType.TIME_INTERVAL to "时间间隔",
        Z.ConditionType.JS_EXPRESSION to "JS 表达式",
        Z.ConditionType.CONDITION_SET to "多个条件"
    )

    /**
     * 显示用：比 [TYPE_LABELS] 多一个 AROUND。
     *
     * 分开两份是因为"能选"和"能显示"不是一回事：AROUND 不能让用户新建，
     * 但旧脚本里已有的那条必须显示出中文名，而不是裸的 `AroundCondition`。
     */
    private val DISPLAY_LABELS: List<Pair<Z.ConditionType, String>> =
        TYPE_LABELS + (Z.ConditionType.AROUND to "位置周围条件")

    // ------------------------------------------------------------ 枚举字典
    //
    // 协议里存英文标识，界面上要显示中文。两边都得收：用户看到中文点下去是中文，
    // 导入的脚本里是英文。写回时不还原成英文的话，文件里会出现中文枚举，
    // 自动精灵读不懂，条件静默失效。

    private val RESET_TIME = listOf(
        "afterFinish" to "跑完后", "onLoad" to "载入时", "afterRepeat" to "每次重复后"
    )
    private val AI_SPEED = listOf("fast" to "快", "normal" to "普通")
    private val RUN_STATE = listOf("suc" to "成功", "fail" to "失败", "notRun" to "未运行")
    private val MATCH_MODE = listOf(
        "and" to "全部满足", "or" to "任一满足", "count" to "满足指定个数"
    )
    private val TIME_UNIT = listOf(
        "0" to "毫秒", "1" to "秒", "2" to "分钟", "3" to "小时"
    )

    private fun wireOf(dict: List<Pair<String, String>>, label: String): String =
        dict.firstOrNull { it.second == label || it.first == label }?.first ?: label

    /**
     * 条件类型的中文名（含 AROUND）。
     *
     * 界面显示统一走这里：`TYPE_LABELS` 是"能选哪些"，显示还要能显示
     * 旧脚本里已经存在的 AROUND —— 两件事不能共用一个列表。
     */
    fun typeLabel(type: Z.ConditionType): String =
        DISPLAY_LABELS.firstOrNull { it.first == type }?.second ?: type.wire

    /** 条件类型的中文名（执行器的日志也要用，不能只在界面里）。 */
    fun labelOf(type: Z.ConditionType): String =
        DISPLAY_LABELS.firstOrNull { it.first == type }?.second ?: type.wire

private fun labelOf(dict: List<Pair<String, String>>, wire: String?): String =
        dict.firstOrNull { it.first == (wire ?: "") }?.second ?: (wire ?: "")

    // ------------------------------------------------------------ 新建

    /**
     * 新建一个条件，带**合理的默认值**。
     *
     * 全空的条件执行时必然判 false，用户加完发现动作永远不跑，
     * 会以为是我们的 bug。默认值取自 FIELDS.md 各条目。
     */
    fun create(type: Z.ConditionType): Z.Condition = when (type) {
        Z.ConditionType.IMAGE -> Z.Condition(type = type, posData = Z.PosData(type = Z.PosType.IMAGE))
        Z.ConditionType.TEXT -> Z.Condition(type = type, posData = Z.PosData(type = Z.PosType.TEXT))
        Z.ConditionType.COLOR -> Z.Condition(type = type, posData = Z.PosData(type = Z.PosType.COLOR))
        Z.ConditionType.NODE -> Z.Condition(type = type, posData = Z.PosData(type = Z.PosType.NODE))

        Z.ConditionType.RANDOM -> Z.Condition(
            type = type, extra = mutableMapOf("percent" to 50)
        )

        Z.ConditionType.LIMIT_RUN_TIMES -> Z.Condition(
            type = type,
            extra = mutableMapOf("limitTimes" to 1, "resetTime" to "afterFinish")
        )

        Z.ConditionType.TIME_INTERVAL -> Z.Condition(
            type = type,
            extra = mutableMapOf(
                "timeIntervalValue" to "1",
                "timeIntervalUnit" to 1,
                "ignoreInitPass" to false
            )
        )

        Z.ConditionType.TIME_AFTER -> Z.Condition(
            type = type,
            // 给一个合法格式让用户照着改。空串解析不出时间，条件永远不成立。
            extra = mutableMapOf("timeValue" to "+00:00:05")
        )

        Z.ConditionType.SCRIPT_RUN_STATE -> Z.Condition(
            type = type,
            extra = mutableMapOf("aimPosition" to "", "aimScriptRunState" to "suc")
        )

        Z.ConditionType.AI_CLOUD -> Z.Condition(
            type = type,
            extra = mutableMapOf("text" to "", "aiSpeed" to "normal")
        )

        Z.ConditionType.CONDITION_SET, Z.ConditionType.AROUND -> Z.Condition(
            type = type, extra = mutableMapOf("matchMode" to "and")
        )

        else -> Z.Condition(type = type)
    }

    // ------------------------------------------------------------ 摊平成行

    /** 条件摊平成可编辑的行，UI 直接复用 [ZActionDescriber.Row]。 */
    fun describe(c: Z.Condition): List<ZActionDescriber.Row> {
        val rows = ArrayList<ZActionDescriber.Row>()
        /*
         * 条件类型：**必须是 ENUM 且带 field**。
         *
         * 原来这行是 `Row("条件类型", 中文名)` —— kind 默认 READONLY、field 是空串。
         * 而界面的点击判据是
         * `kind != READONLY && kind != COORD && kind != IMAGE && field.isNotEmpty()`
         * （ZjsEditorPage.buildRowView），三个条件全不满足 → 这一行**根本不挂点击**。
         * 于是「条件类型」点了没反应，用户想换类型只能把条件删了重建，
         * 重填一遍模板/范围/相似度。而 ZjsEditorPage 里那段
         * `if (row.label == "条件类型")` 的换类型分支因此从来没被执行过（死代码）。
         *
         * 现在：Kind.ENUM + options（下拉用）+ field="cond.type"（让点击和
         * editConditionRow 都能认出这一行）。
         */
        rows += Row(
            "条件类型",
            typeLabel(c.type),
            Kind.ENUM, options = TYPE_LABELS.map { it.second }, field = "cond.type"
        )

        // ---- 位置类。两类条件的键名不同（image / text / colorData / node），
        //      但界面上要改的东西是同一套：在哪找、找什么、多像算找到。
        c.posData?.let { pos ->
            when (pos.type) {
                Z.PosType.LOCATION -> {
                    rows += Row("位置 X", pos.x?.raw.orEmpty(), Kind.COORD, field = "cond.x")
                    rows += Row("位置 Y", pos.y?.raw.orEmpty(), Kind.COORD, field = "cond.y")
                }

                Z.PosType.IMAGE -> {
                    val img = pos.imageData
                    rows += Row(
                        "模板",
                        if (img?.width != null && img.height != null) "${img.width}×${img.height}" else "未设置",
                        Kind.IMAGE, field = "cond.image"
                    )
                    rows += Row(
                        "相似度",
                        pos.minSimilarPercent?.let { "$it%" } ?: "未设置（默认 90%）",
                        Kind.PERCENT, field = "cond.minSimilarPercent"
                    )
                }

                Z.PosType.TEXT -> {
                    rows += Row("文字", pos.text.orEmpty(), Kind.TEXT, field = "cond.text")
                    rows += Row(
                        "相似度",
                        pos.similarPercent?.let { "$it%" } ?: "未设置（默认 90%）",
                        Kind.PERCENT, field = "cond.similarPercent"
                    )
                }

                Z.PosType.COLOR -> {
                    rows += Row("颜色", pos.color.orEmpty(), Kind.TEXT, field = "cond.color")
                            // 颜色从屏幕取，不靠手输（理由同动作侧）
            rows += Row(
                "从屏幕取色",
                if (pos.color.isNullOrBlank()) "点右边按钮在画面上取一个颜色"
                else "重新取色（当前 ${if (pos.color!!.startsWith("#")) pos.color else "#" + pos.color}）",
                Kind.COORD,
                field = "cond.colorPick"
            )
                    rows += Row(
                        "相似度",
                        pos.similarPercent?.let { "$it%" } ?: "未设置（默认 90%）",
                        Kind.PERCENT, field = "cond.similarPercent"
                    )
                }

                Z.PosType.NODE -> rows += Row("节点", pos.text.orEmpty(), Kind.TEXT, field = "cond.text")
                else -> Unit
            }
            // 范围对四类都成立：在画面的哪一块里找
            rows += Row("范围", pos.limitArea?.toString() ?: "整屏", Kind.AREA, field = "cond.limitArea")
            rows += Row("预览", "点这里在画面上看命中位置", Kind.ENTER, field = "cond.preview")
        }

        // ---- 类型专属（FIELDS.md:264-278）
        when (c.type) {
            Z.ConditionType.RANDOM -> rows += Row(
                "百分比", c.extra["percent"]?.toString() ?: "50",
                Kind.PERCENT, field = "percent"
            )

            Z.ConditionType.LIMIT_RUN_TIMES -> {
                rows += Row(
                    "限制次数", c.extra["limitTimes"]?.toString().orEmpty(),
                    Kind.NUMBER, field = "limitTimes"
                )
                rows += Row(
                    "清零时机", labelOf(RESET_TIME, c.extra["resetTime"]?.toString()),
                    Kind.ENUM, options = RESET_TIME.map { it.second }, field = "resetTime"
                )
                rows += Row(
                    "同步到变量", c.extra["syncNowRunTimesToVarName"]?.toString().orEmpty(),
                    Kind.TEXT, field = "syncNowRunTimesToVarName"
                )
            }

            Z.ConditionType.SCRIPT_RUN_STATE -> {
                rows += Row(
                    "目标动作", c.extra["aimPosition"]?.toString().orEmpty(),
                    Kind.TEXT, field = "aimPosition"
                )
                rows += Row(
                    "状态", labelOf(RUN_STATE, c.extra["aimScriptRunState"]?.toString()),
                    Kind.ENUM, options = RUN_STATE.map { it.second }, field = "aimScriptRunState"
                )
            }

            Z.ConditionType.TIME_AFTER -> rows += Row(
                "时间", c.extra["timeValue"]?.toString().orEmpty(),
                Kind.TEXT, field = "timeValue"
            )

            Z.ConditionType.TIME_INTERVAL -> {
                rows += Row(
                    "间隔值", c.extra["timeIntervalValue"]?.toString().orEmpty(),
                    Kind.TEXT, field = "timeIntervalValue"
                )
                rows += Row(
                    "间隔单位", labelOf(TIME_UNIT, c.extra["timeIntervalUnit"]?.toString()),
                    Kind.ENUM, options = TIME_UNIT.map { it.second }, field = "timeIntervalUnit"
                )
                rows += Row(
                    "忽略首次", if (c.extra["ignoreInitPass"] == true) "是" else "否",
                    Kind.SWITCH, field = "ignoreInitPass"
                )
            }

            Z.ConditionType.JS_EXPRESSION -> rows += Row(
                "表达式", c.extra["expression"]?.toString().orEmpty(),
                Kind.TEXT, field = "expression"
            )

            Z.ConditionType.AI_CLOUD -> {
                /*
                 * ★ AI 云识别本机跑不了（要云端接口）。下面这几个字段存得进文件，
                 *   但**不会被求值** —— 从自动精灵导入的脚本里可能带着这条条件，
                 *   所以字段保留可编辑，顶上先说清楚。
                 */
                rows += Row(
                    "说明",
                    "本机不支持 AI 云识别（需要云端接口）→ 下面的字段不会生效",
                    Kind.READONLY
                )
                rows += Row("文字", c.extra["text"]?.toString().orEmpty(), Kind.TEXT, field = "aiText")
                rows += Row(
                    "速度", labelOf(AI_SPEED, c.extra["aiSpeed"]?.toString()),
                    Kind.ENUM, options = AI_SPEED.map { it.second }, field = "aiSpeed"
                )
                for (n in 1..3) {
                    rows += Row(
                        "图$n",
                        if (c.extra["image$n"] != null) "已设置" else "未设置",
                        Kind.IMAGE, field = "aiImage$n"
                    )
                }
            }

            Z.ConditionType.CONDITION_SET, Z.ConditionType.AROUND -> {
                /*
                 * 子条件：`conditionSet.conditions` 是一串条件（FIELDS.md:277）。
                 *
                 * 以前这里只出「匹配方式 / 匹配数量 / 多线程检查」三行 ——
                 * 子条件本身**看不见也删不掉**。用户在自动精灵里做的
                 * 「同时满足：A 且 B 且 C」导进来后只剩一行「全部满足」，
                 * 他既不知道里面有几个条件，也没法改，改完保存还可能把它们带坏。
                 *
                 * 现在给一行 ENTER：点进去是子条件列表（增 / 改 / 删）。
                 * 用 ENTER 而不是把子条件平铺在这里 —— 子条件自己又是完整条件，
                 * 平铺会无限嵌套，90 行以内放不下。
                 */
                val kids = children(c)
                rows += Row(
                    "子条件",
                    if (kids.isEmpty()) "未设置（点这里添加）" else "共 ${kids.size} 个",
                    Kind.ENTER, field = "cond.children"
                )
                rows += Row(
                    "匹配方式", labelOf(MATCH_MODE, c.extra["matchMode"]?.toString()),
                    Kind.ENUM, options = MATCH_MODE.map { it.second }, field = "matchMode"
                )
                if (c.extra["matchMode"] == "count") {
                    rows += Row(
                        "匹配数量", c.extra["matchCount"]?.toString().orEmpty(),
                        Kind.NUMBER, field = "matchCount"
                    )
                }
                rows += Row(
                    "多线程检查", if (c.extra["checkInMultiThreads"] == true) "是" else "否",
                    Kind.SWITCH, field = "checkInMultiThreads"
                )
                if (c.type == Z.ConditionType.AROUND) {
                    rows += Row("基准 X", c.extra["basePosX"]?.toString().orEmpty(), Kind.TEXT, field = "basePosX")
                    rows += Row("基准 Y", c.extra["basePosY"]?.toString().orEmpty(), Kind.TEXT, field = "basePosY")
                }
            }

            else -> Unit
        }

        // ---- 通用字段（FIELDS.md:251-260）
        rows += Row("备注", c.desc.orEmpty(), Kind.TEXT, field = "desc")
        rows += Row("取反", if (c.runWhenFalse) "是" else "否", Kind.SWITCH, field = "runWhenFalse")
        rows += Row("禁用", if (c.disabled) "是" else "否", Kind.SWITCH, field = "disabled")
        rows += Row(
            "不成立时反复检查", if (c.repeatWhenFalse) "是" else "否",
            Kind.SWITCH, field = "repeatWhenFalse"
        )
        // 「识别次数」和「识别间隔」始终显示，而且**始终显示真实数字**。
        //
        // 以前只在开启"反复检查"时才出现，用户第一眼看不到，就会以为
        // 自动精灵有的字段我们没做。默认关着但看得见，比藏起来好。
        //
        // ## 显示"—"是错的
        //
        // 原来开关关着时这行的值是 "—"，而 apply() 不管开关状态**总是写入**。
        // 于是：用户看到 "—"（以为没设置）→ 点一下 → 弹窗里的初值是 "—" →
        // 点确定 → "—".toIntOrNull() = null → 字段被清空。
        // 反过来更常见：文件里本来有 50，界面上是 "—"，用户永远看不到这个 50，
        // 打开一次"反复检查"就直接按 `?: 0` 的默认值跑无限重试。
        //
        // 现在：**标签恒定**、值恒为真实数字（默认 0 / 1000，与 FIELDS.md:257-258 一致）。
        // "这两个值当前生不生效"由上面那行「不成立时反复检查」开关表达 ——
        // 不在这两行的标签上做标记（按 label 查行的既有测试和调用点会因此失效）。
        rows += Row(
            "识别次数",
            (c.repeatWhenFalseLimitTimes ?: 0).toString(),
            Kind.NUMBER, field = "repeatWhenFalseLimitTimes"
        )
        rows += Row(
            "识别间隔(ms)",
            (c.repeatWhenFalseRepeatDelay ?: 1000).toString(),
            Kind.NUMBER, field = "repeatWhenFalseRepeatDelay"
        )
        rows += Row(
            "先等待再检查", if (c.checkBeforeDelay) "是" else "否",
            Kind.SWITCH, field = "checkBeforeDelay"
        )

        return rows
    }

    // ------------------------------------------------------------ 改字段

    /**
     * 改条件的一个字段。不认识返回 null。
     *
     * `cond.*` 是位置对象里的字段，其余落在通用字段或 `extra` 里。
     */
    fun apply(condition: Z.Condition, field: String, value: String): Z.Condition? {
        if (field.startsWith("cond.")) return applyPosition(condition, field, value)

        return when (field) {
            "desc" -> condition.copy(desc = value.ifBlank { null })
            "runWhenFalse" -> condition.copy(runWhenFalse = truthy(value))
            "disabled" -> condition.copy(disabled = truthy(value))
            "repeatWhenFalse" -> condition.copy(repeatWhenFalse = truthy(value))
            "repeatWhenFalseLimitTimes" ->
                condition.copy(repeatWhenFalseLimitTimes = value.trim().toIntOrNull())
            "repeatWhenFalseRepeatDelay" ->
                condition.copy(repeatWhenFalseRepeatDelay = value.trim().toIntOrNull())
            "checkBeforeDelay" -> condition.copy(checkBeforeDelay = truthy(value))

            "percent" -> withExtra(condition, "percent", value.removeSuffix("%").trim().toIntOrNull())
            "limitTimes" -> withExtra(condition, "limitTimes", value.trim().toIntOrNull())
            "resetTime" -> withExtra(condition, "resetTime", wireOf(RESET_TIME, value))
            "syncNowRunTimesToVarName" ->
                withExtra(condition, "syncNowRunTimesToVarName", value.ifBlank { null })
            "aimPosition" -> withExtra(condition, "aimPosition", value)
            "aimScriptRunState" -> withExtra(condition, "aimScriptRunState", wireOf(RUN_STATE, value))
            "timeValue" -> withExtra(condition, "timeValue", value)
            "timeIntervalValue" -> withExtra(condition, "timeIntervalValue", value)
            "timeIntervalUnit" ->
                withExtra(condition, "timeIntervalUnit", wireOf(TIME_UNIT, value).toIntOrNull())
            "ignoreInitPass" -> withExtra(condition, "ignoreInitPass", truthy(value))
            "expression" -> withExtra(condition, "expression", value)
            "aiText" -> withExtra(condition, "text", value)
            "aiSpeed" -> withExtra(condition, "aiSpeed", wireOf(AI_SPEED, value))
            "matchMode" -> withExtra(condition, "matchMode", wireOf(MATCH_MODE, value))
            "matchCount" -> withExtra(condition, "matchCount", value.trim().toIntOrNull())
            "checkInMultiThreads" -> withExtra(condition, "checkInMultiThreads", truthy(value))
            "basePosX" -> withExtra(condition, "basePosX", value)
            "basePosY" -> withExtra(condition, "basePosY", value)
            else -> null
        }
    }

    /** 条件里位置对象的字段（x/y/文字/颜色/范围/相似度）。 */
    private fun applyPosition(condition: Z.Condition, field: String, value: String): Z.Condition? {
        val pos = condition.posData ?: return null
        val next = when (field) {
            "cond.x" -> pos.copy(x = ZValue.parse(value))
            "cond.y" -> pos.copy(y = ZValue.parse(value))
            "cond.text" -> pos.copy(text = value.ifBlank { null })
            "cond.color" -> pos.copy(color = value.ifBlank { null })
            "cond.minSimilarPercent" -> pos.copy(minSimilarPercent = clampPercent(value))
            "cond.similarPercent" -> pos.copy(similarPercent = clampPercent(value))
            "cond.limitArea" -> if (value.isBlank()) {
                pos.copy(limitArea = null)
            } else {
                ZArea.parse(value)?.let { pos.copy(limitArea = it) } ?: return null
            }
            else -> return null
        }
        return condition.copy(posData = next)
    }

    /**
     * 相似度阈值：**协议范围 50~100**（FIELDS.md 3.2 `minSimilarPercent` / 3.4 `similarPercent`），
     * 空串 → null（＝清空，落盘时不写）。
     *
     * ## 原来夹的是 0~100（和动作侧不一致）
     *
     * 动作侧的 `ZFieldEditor.parsePercent` 已经按协议夹 50~100，条件侧还留在 0~100 ——
     * 同一个字段两条入口行为不同：在动作卡片里拖到 30 会被抬成 50，
     * 在条件弹窗里填 30 却原样写进文件。
     * 而 0~49 在自动精灵那边不是合法阈值，匹配会退化成"几乎总能命中"，
     * 用户只会看到"明明没那个东西也点" —— 完全看不出是阈值被写坏了。
     */
    private fun clampPercent(value: String): Int? {
        val t = value.trim().removeSuffix("%").trim()
        if (t.isEmpty()) return null
        return t.toDoubleOrNull()?.toInt()?.coerceIn(50, 100)
    }

    /** 模板图：整块替换。条件里的模板和 AI 的三张图都走这里。 */
    fun applyImage(condition: Z.Condition, field: String, image: Z.ImageData?): Z.Condition? {
        if (image == null) return null
        return when (field) {
            "cond.image" -> condition.copy(posData = condition.posData?.copy(imageData = image))
            "aiImage1" -> withExtra(condition, "image1", image)
            "aiImage2" -> withExtra(condition, "image2", image)
            "aiImage3" -> withExtra(condition, "image3", image)
            else -> null
        }
    }

    // ------------------------------------------------------------ 子条件

    /*
     * `conditionSet` / `AroundCondition` 的子条件存在 `extra["conditions"]` 里。
     *
     * 这个键没有建模，所以 ZModel 解析时整块进了 extra（原样保留到落盘），
     * 值是 JSONArray，每项是一个**完整条件对象**（FIELDS.md:277 `conditions:Condition[]`）。
     *
     * 这里给它一对读写函数：界面拿 [children] 显示、用 [withChildren] 写回。
     * 不改 ZModel（那边有人在动），所以走 extra —— 落盘时 extra 会被原样写回，
     * 序列化结果和直接建模是一样的。
     */

    /**
     * 取子条件列表。
     *
     * 解析不出来的那一项跳过（一个坏条件不该让整条条件都读不出来）。
     * 注意：跳过之后再用 [withChildren] 写回，那一项就没了 —— 这只发生在
     * type 是未知值的情况下，而我们认得协议全部 13 种类型，实际碰不到。
     */
    fun children(c: Z.Condition): List<Z.Condition> {
        val arr = c.extra["conditions"] as? org.json.JSONArray ?: return emptyList()
        val out = ArrayList<Z.Condition>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { obj -> Z.Condition.parse(obj)?.let { out.add(it) } }
        }
        return out
    }

    /** 写回子条件列表。空列表 → 删掉这个键（"没有子条件"就不该留一个空数组）。 */
    fun withChildren(c: Z.Condition, list: List<Z.Condition>): Z.Condition {
        if (list.isEmpty()) return withExtra(c, "conditions", null)
        val arr = org.json.JSONArray()
        list.forEach { arr.put(it.toJson()) }
        return withExtra(c, "conditions", arr)
    }

    /**
     * 换条件类型。位置对象要跟着换，否则键名和内容对不上。
     *
     * ## 为什么不再整块丢掉位置对象
     *
     * 原来只保留 7 个通用字段，`posData` 整个换成 `create(type)` 的新对象 ——
     * 于是用户把「出现图片」改成「出现文字」时，**刚框好的识别范围也没了**。
     * 范围（`limitArea`）回答的是"在画面的哪一块里找"，跟条件类型无关，
     * 换类型不该把它清掉（动作侧换位置类型时也是保留范围的）。
     *
     * 模板/文字/颜色/相似度这些**确实**跟类型绑定，必须重建 —— 见 ZModel
     * `Condition.toJson` 的键名映射（image / text / colorData / node），
     * 搬运过来只会写到一个类型取不到的键上，条件静默恒 false。
     * 界面上换类型前会 Toast 提醒这一点。
     */
    fun changeType(condition: Z.Condition, type: Z.ConditionType): Z.Condition {
        // 选了同一个类型 = 什么都不该发生（否则等于把模板/范围清空一次）
        if (condition.type == type) return condition

        val fresh = create(type)
        // 只搬 limitArea；目标类型没有位置对象时（random/时间类）搬不过去
        val carriedPos = fresh.posData?.let { p -> condition.posData?.limitArea?.let { p.copy(limitArea = it) } ?: p }
        return fresh.copy(
            posData = carriedPos,
            runWhenFalse = condition.runWhenFalse,
            disabled = condition.disabled,
            repeatWhenFalse = condition.repeatWhenFalse,
            repeatWhenFalseLimitTimes = condition.repeatWhenFalseLimitTimes,
            repeatWhenFalseRepeatDelay = condition.repeatWhenFalseRepeatDelay,
            checkBeforeDelay = condition.checkBeforeDelay,
            desc = condition.desc
        )
    }

    private fun withExtra(c: Z.Condition, key: String, v: Any?): Z.Condition {
        val m = LinkedHashMap(c.extra)
        if (v == null) m.remove(key) else m[key] = v
        return c.copy(extra = m)
    }

    private fun truthy(v: String): Boolean =
        v.trim().let { it == "是" || it.equals("true", ignoreCase = true) || it == "1" }

    /** 少写点 `ZActionDescriber.Row(...)`，这个文件里出现几十次。 */
    private fun Row(
        label: String,
        value: String,
        kind: Kind = Kind.READONLY,
        options: List<String> = emptyList(),
        field: String = ""
    ) = ZActionDescriber.Row(label, value, kind, options, field)
}
