package com.pvz2.auto.zjs

import com.pvz2.auto.zjs.ZActionDescriber.Kind

/**
 * 条件与监听槽位的增删改。
 *
 * ## 为什么单独一个文件
 *
 * [ZFieldEditor] 管"改一个标量字段"，这里管"挂上一个条件 / 加一个监听" ——
 * 后者要构造新的子对象，语义不同。混在一起会让那个类越来越难读。
 *
 * ## 条件和监听都是嵌套结构
 *
 * - 一个动作最多有 **1 个** `condition`（FIELDS.md:280「单个动作的 condition
 *   只有一个条件对象」），AND/OR 靠 `conditionSet.matchMode` 表达
 * - 一个动作有 **7 个**监听槽位，每个槽位是**一个完整动作**
 *
 * 所以"加条件"是设一个字段，"加监听"是往 map 里塞一个 Action，两者不能混谈。
 */
object ZConditionEditor {

    /** 13 种条件类型的中文名，用于选择列表。顺序与 FIELDS.md:266-278 一致。 */
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

    /**
     * 新建一个条件。
     *
     * 每种类型带一个**合理的默认值**，而不是全空 —— 空条件执行时
     * 必然判 false，用户加完发现动作永远不跑，会以为是我们的 bug。
     *
     * 默认值取自 FIELDS.md 各条目的说明。
     */
    fun create(type: Z.ConditionType): Z.Condition = when (type) {
        // 位置类：给一个屏幕正中的位置，用户接着去取点
        Z.ConditionType.IMAGE -> Z.Condition(
            type = type,
            posData = Z.PosData(type = Z.PosType.IMAGE)
        )

        Z.ConditionType.TEXT -> Z.Condition(
            type = type,
            posData = Z.PosData(type = Z.PosType.TEXT)
        )

        Z.ConditionType.COLOR -> Z.Condition(
            type = type,
            posData = Z.PosData(type = Z.PosType.COLOR)
        )

        Z.ConditionType.NODE -> Z.Condition(
            type = type,
            posData = Z.PosData(type = Z.PosType.NODE)
        )

        // 随机：默认 50%（FIELDS.md:273）
        Z.ConditionType.RANDOM -> Z.Condition(
            type = type,
            extra = mutableMapOf("percent" to 50)
        )

        // 限制运行次数：默认 1 次，跑完就停
        Z.ConditionType.LIMIT_RUN_TIMES -> Z.Condition(
            type = type,
            extra = mutableMapOf("limitTimes" to 1, "resetTime" to "afterFinish")
        )

        // 时间间隔：默认 1 秒（单位 0=毫秒 1=秒）
        Z.ConditionType.TIME_INTERVAL -> Z.Condition(
            type = type,
            extra = mutableMapOf(
                "timeIntervalValue" to "1",
                "timeIntervalUnit" to 1,
                "ignoreInitPass" to false
            )
        )

        // 条件组：默认 AND
        Z.ConditionType.CONDITION_SET, Z.ConditionType.AROUND -> Z.Condition(
            type = type,
            extra = mutableMapOf("matchMode" to "and")
        )

        else -> Z.Condition(type = type)
    }

    /** 改条件的某个字段。返回新对象；路径不认识返回 null。 */
    fun apply(condition: Z.Condition, field: String, value: String): Z.Condition? =
        when (field) {
            "desc" -> condition.copy(desc = value.ifBlank { null })
            "runWhenFalse" -> condition.copy(runWhenFalse = truthy(value))
            "disabled" -> condition.copy(disabled = truthy(value))
            "repeatWhenFalse" -> condition.copy(repeatWhenFalse = truthy(value))
            "repeatWhenFalseLimitTimes" ->
                condition.copy(repeatWhenFalseLimitTimes = value.trim().toIntOrNull())

            "repeatWhenFalseRepeatDelay" ->
                condition.copy(repeatWhenFalseRepeatDelay = value.trim().toIntOrNull())

            "checkBeforeDelay" -> condition.copy(checkBeforeDelay = truthy(value))

            // 类型专属字段都落在 extra 里
            "percent" -> withExtra(condition, "percent", value.trim().toIntOrNull())
            "limitTimes" -> withExtra(condition, "limitTimes", value.trim().toIntOrNull())
            "matchMode" -> withExtra(condition, "matchMode", value)
            "expression" -> withExtra(condition, "expression", value)
            "timeIntervalValue" -> withExtra(condition, "timeIntervalValue", value)
            else -> null
        }

    /** 换条件类型。位置对象要跟着换，否则键名和内容对不上。 */
    fun changeType(condition: Z.Condition, type: Z.ConditionType): Z.Condition {
        val fresh = create(type)
        // 保留通用的开关，其余用新类型的默认值
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

    private fun withExtra(
        c: Z.Condition,
        key: String,
        v: Any?
    ): Z.Condition {
        val m = LinkedHashMap(c.extra)
        if (v == null) m.remove(key) else m[key] = v
        return c.copy(extra = m)
    }

    private fun truthy(v: String): Boolean =
        v.trim().let { it == "是" || it.equals("true", ignoreCase = true) || it == "1" }

    /**
     * 条件摊平成可编辑的行。
     *
     * 与 [ZActionDescriber] 同一套 [ZActionDescriber.Row] 结构，UI 直接复用。
     */
    fun describe(c: Z.Condition): List<ZActionDescriber.Row> {
        val rows = ArrayList<ZActionDescriber.Row>()

        rows += ZActionDescriber.Row("条件类型", TYPE_LABELS.firstOrNull { it.first == c.type }?.second ?: c.type.wire)

        // 位置类条件：坐标/区域可编辑
        c.posData?.let { pos ->
            if (pos.type == Z.PosType.LOCATION) {
                rows += ZActionDescriber.Row("位置 X", pos.x?.raw.orEmpty(), Kind.COORD, field = "cond.x")
                rows += ZActionDescriber.Row("位置 Y", pos.y?.raw.orEmpty(), Kind.COORD, field = "cond.y")
            }
            pos.text?.let { rows += ZActionDescriber.Row("文字", it, Kind.TEXT, field = "cond.text") }
            pos.color?.let { rows += ZActionDescriber.Row("颜色", it, Kind.TEXT, field = "cond.color") }
            pos.imageData?.let { img ->
                val size = if (img.width != null && img.height != null) "${img.width}×${img.height}" else "未知尺寸"
                rows += ZActionDescriber.Row("模板", size, Kind.IMAGE, field = "cond.image")
            }
            pos.limitArea?.let {
                rows += ZActionDescriber.Row("识别区域", it.toString(), Kind.AREA, field = "cond.limitArea")
            }
        }

        // 类型专属参数
        when (c.type) {
            Z.ConditionType.RANDOM -> rows += ZActionDescriber.Row(
                "百分比", c.extra["percent"]?.toString() ?: "50", Kind.NUMBER, field = "percent"
            )

            Z.ConditionType.LIMIT_RUN_TIMES -> {
                rows += ZActionDescriber.Row(
                    "限制次数", c.extra["limitTimes"]?.toString().orEmpty(), Kind.NUMBER, field = "limitTimes"
                )
                rows += ZActionDescriber.Row(
                    "清零时机", c.extra["resetTime"]?.toString() ?: "afterFinish", Kind.ENUM,
                    options = listOf("afterFinish", "onLoad", "afterRepeat")
                )
            }

            Z.ConditionType.TIME_INTERVAL -> rows += ZActionDescriber.Row(
                "间隔值", c.extra["timeIntervalValue"]?.toString().orEmpty(), Kind.TEXT, field = "timeIntervalValue"
            )

            Z.ConditionType.JS_EXPRESSION -> rows += ZActionDescriber.Row(
                "表达式", c.extra["expression"]?.toString().orEmpty(), Kind.TEXT, field = "expression"
            )

            Z.ConditionType.CONDITION_SET, Z.ConditionType.AROUND -> rows += ZActionDescriber.Row(
                "匹配方式", c.extra["matchMode"]?.toString() ?: "and", Kind.ENUM,
                options = listOf("and", "or", "count"), field = "matchMode"
            )

            else -> Unit
        }

        // 通用开关
        rows += ZActionDescriber.Row("备注", c.desc.orEmpty(), Kind.TEXT, field = "desc")
        rows += ZActionDescriber.Row(
            "取反", if (c.runWhenFalse) "是" else "否", Kind.ENUM,
            options = listOf("否", "是"), field = "runWhenFalse"
        )
        rows += ZActionDescriber.Row(
            "禁用", if (c.disabled) "是" else "否", Kind.ENUM,
            options = listOf("否", "是"), field = "disabled"
        )
        rows += ZActionDescriber.Row(
            "不成立时重复", if (c.repeatWhenFalse) "是" else "否", Kind.ENUM,
            options = listOf("否", "是"), field = "repeatWhenFalse"
        )
        if (c.repeatWhenFalse) {
            rows += ZActionDescriber.Row(
                "最多重试", c.repeatWhenFalseLimitTimes?.toString().orEmpty(), Kind.NUMBER,
                field = "repeatWhenFalseLimitTimes"
            )
        }
        rows += ZActionDescriber.Row(
            "先等待再检查", if (c.checkBeforeDelay) "是" else "否", Kind.ENUM,
            options = listOf("否", "是"), field = "checkBeforeDelay"
        )

        return rows
    }
}
