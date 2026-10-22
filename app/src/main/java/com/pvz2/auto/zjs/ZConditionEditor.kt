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

    /** 13 种条件类型的中文名。顺序与 FIELDS.md:264-278 一致。 */
    val TYPE_LABELS: List<Pair<Z.ConditionType, String>> = listOf(
        Z.ConditionType.IMAGE to "出现图片",
        Z.ConditionType.TEXT to "出现文字",
        Z.ConditionType.COLOR to "出现颜色",
        Z.ConditionType.NODE to "出现节点",
        Z.ConditionType.AI_CLOUD to "AI 云识别",
        Z.ConditionType.LIMIT_RUN_TIMES to "限制运行次数",
        Z.ConditionType.SCRIPT_RUN_STATE to "动作运行状态",
        Z.ConditionType.RANDOM to "随机运行",
        Z.ConditionType.TIME_AFTER to "在时间之后",
        Z.ConditionType.TIME_INTERVAL to "时间间隔",
        Z.ConditionType.JS_EXPRESSION to "JS 表达式",
        Z.ConditionType.CONDITION_SET to "多个条件",
        Z.ConditionType.AROUND to "位置周围条件"
    )

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

        rows += Row(
            "条件类型",
            TYPE_LABELS.firstOrNull { it.first == c.type }?.second ?: c.type.wire
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
        if (c.repeatWhenFalse) {
            rows += Row(
                "最多重试", c.repeatWhenFalseLimitTimes?.toString().orEmpty(),
                Kind.NUMBER, field = "repeatWhenFalseLimitTimes"
            )
            rows += Row(
                "重试间隔(ms)", c.repeatWhenFalseRepeatDelay?.toString().orEmpty(),
                Kind.NUMBER, field = "repeatWhenFalseRepeatDelay"
            )
        }
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
            "cond.minSimilarPercent" -> pos.copy(
                minSimilarPercent = value.removeSuffix("%").trim().toIntOrNull()?.coerceIn(0, 100)
            )
            "cond.similarPercent" -> pos.copy(
                similarPercent = value.removeSuffix("%").trim().toIntOrNull()?.coerceIn(0, 100)
            )
            "cond.limitArea" -> if (value.isBlank()) {
                pos.copy(limitArea = null)
            } else {
                ZArea.parse(value)?.let { pos.copy(limitArea = it) } ?: return null
            }
            else -> return null
        }
        return condition.copy(posData = next)
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

    /** 换条件类型。位置对象要跟着换，否则键名和内容对不上。 */
    fun changeType(condition: Z.Condition, type: Z.ConditionType): Z.Condition {
        val fresh = create(type)
        return fresh.copy(
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
