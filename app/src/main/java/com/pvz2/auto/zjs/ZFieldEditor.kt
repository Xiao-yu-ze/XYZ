package com.pvz2.auto.zjs

/**
 * 按「字段路径」修改一个动作。
 *
 * ## 为什么用路径字符串
 *
 * 描述器（[ZActionDescriber]）已经把每个可编辑字段标成 `"desc"`、`"位置.x"`、
 * `"终点.limitArea"` 这样的路径。UI 拿到的就是这些字符串，
 * 点哪个字段就传哪个路径回来 —— 不需要 UI 知道 `posData` 还是 `startPos`。
 *
 * ## 为什么全是不可变拷贝
 *
 * 模型都是 `data class`。返回新对象而不是就地改，好处是：
 *  - 撤销/重做只要留一份旧引用；
 *  - 改错了不会把原对象污染到别处（同一个 Action 可能被多个地方引用）。
 *
 * ## 值一律按字符串处理
 *
 * 协议里几乎所有字段都是字符串（`duration` 支持 `"100-300"` 区间、
 * `x` 支持百分比和 dp）。这里不做类型转换，只把字符串放进去 ——
 * 转换留给执行器，因为它才知道当前屏幕尺寸。
 */
object ZFieldEditor {

    /** 位置字段的前缀 → 取哪个 PosData。 */
    private val POS_PREFIXES = mapOf(
        "位置" to PosSlot.MAIN,
        "起点" to PosSlot.START,
        "终点" to PosSlot.END
    )

    private enum class PosSlot { MAIN, START, END }

    /**
     * 写模板图。
     *
     * ## 为什么单独一个方法而不是走 [apply] 的字符串
     *
     * 模板是一整块 base64 加六个尺寸字段，塞进 String 再解析一遍
     * 既浪费又容易在转义上出错。这里直接收结构体。
     *
     * @param field 描述器给的路径，如 `"位置.image"`；`null` 表示清空模板
     */
    fun applyImage(action: Z.Action, field: String, image: Z.ImageData?): Z.Action? {
        val dot = field.indexOf('.')
        if (dot <= 0) return null
        val slot = POS_PREFIXES[field.substring(0, dot)] ?: return null
        val pos = when (slot) {
            PosSlot.MAIN -> action.posData
            PosSlot.START -> action.startPos
            PosSlot.END -> action.endPos
        } ?: return null

        val updated = pos.copy(imageData = image)
        return when (slot) {
            PosSlot.MAIN -> action.copy(posData = updated)
            PosSlot.START -> action.copy(startPos = updated)
            PosSlot.END -> action.copy(endPos = updated)
        }
    }

    /**
     * 把一个字段改成 [value]。
     *
     * @param action 原动作
     * @param field  描述器给的路径，如 `"desc"`、`"位置.x"`
     * @param value  新值；空字符串表示清空该字段（落盘时不写）
     * @return 新的动作；路径不认识时返回 null（调用方应记录，不要静默）
     */
    fun apply(action: Z.Action, field: String, value: String): Z.Action? {
        // ---- 位置类：前缀 + 字段名
        val dot = field.indexOf('.')
        if (dot > 0) {
            val prefix = field.substring(0, dot)
            val name = field.substring(dot + 1)
            val slot = POS_PREFIXES[prefix] ?: return null
            val pos = when (slot) {
                PosSlot.MAIN -> action.posData
                PosSlot.START -> action.startPos
                PosSlot.END -> action.endPos
            } ?: return null

            val updated = applyPos(pos, name, value) ?: return null
            return when (slot) {
                PosSlot.MAIN -> action.copy(posData = updated)
                PosSlot.START -> action.copy(startPos = updated)
                PosSlot.END -> action.copy(endPos = updated)
            }
        }

        // ---- 通用字段
        val c = action.common
        val newCommon = when (field) {
            "desc" -> c.copy(desc = value.ifBlank { null })
            "jumpId" -> c.copy(jumpId = value.ifBlank { null })
            "duration" -> c.copy(duration = value.ifBlank { null })
            "delay" -> c.copy(delay = value.ifBlank { null })
            "repeatDelayValue" -> c.copy(repeatDelayValue = value.ifBlank { null })
            "repeatCount" -> c.copy(repeatCount = value.trim().toIntOrNull())
            // 枚举类字段：接受显示值（是/否）也接受协议值（true/false）
            "fastRepeatMode" -> c.copy(fastRepeatMode = truthy(value))
            "disabled" -> c.copy(disabled = truthy(value))
            // 「等待候选」的两个参数。0 / 空 = 一直等。
            "waitTimeoutMs" -> return action.copy(
                waitTimeoutMs = value.trim().toIntOrNull()?.coerceAtLeast(0)
            )
            "waitIntervalMs" -> return action.copy(
                waitIntervalMs = value.trim().toIntOrNull()?.coerceIn(20, 60_000)
            )
            else -> return null
        }
        return action.copy(common = newCommon)
    }

    /**
     * 改位置对象里的一个字段。
     *
     * 坐标传空串表示清空。`limitArea` 用 [ZArea.parse] 校验 ——
     * 格式不对就拒绝，不把坏值写进去（坏值会让执行器解析失败，
     * 而那时用户已经忘了自己填过什么）。
     */
    private fun applyPos(pos: Z.PosData, name: String, value: String): Z.PosData? = when (name) {
        "x" -> pos.copy(x = parseOrNull(value))
        "y" -> pos.copy(y = parseOrNull(value))
        "text" -> pos.copy(text = value.ifBlank { null })
        "color" -> pos.copy(color = value.ifBlank { null })
        // 多命中时选第几个："2" / "-1" / "0"(随机) / "1-3" / "1,3,4"
        "indexNum" -> pos.copy(indexNum = value.ifBlank { null })
        // 命中后的偏移，格式同 x/y（Value）
        "xOffset" -> pos.copy(xOffset = parseOrNull(value))
        "yOffset" -> pos.copy(yOffset = parseOrNull(value))
        "limitArea" -> {
            if (value.isBlank()) {
                pos.copy(limitArea = null)
            } else {
                // 格式不对就拒绝，返回 null 让调用方报错
                val area = ZArea.parse(value) ?: return null
                pos.copy(limitArea = area)
            }
        }
        // 相似度阈值，0~100。用户常填 "90%" 或 "90"，两种都收；
        // 超范围直接夹住而不是拒绝 —— 用户想表达的是"最宽松/最严格"，
        // 弹个错只会让他再猜一次。清空 = 回到协议默认值。
        "minSimilarPercent" -> if (value.isBlank()) {
            pos.copy(minSimilarPercent = null)
        } else {
            parsePercent(value)?.let { pos.copy(minSimilarPercent = it) }
        }
        "similarPercent" -> if (value.isBlank()) {
            pos.copy(similarPercent = null)
        } else {
            parsePercent(value)?.let { pos.copy(similarPercent = it) }
        }
        // 下面这些在协议里是可选的识别字段，模型没单独建属性，统一放 extra。
        // 好处是：**不认识/不常用字段不会因为编辑一次就被丢掉**。
        "quickSearch" -> pos.copy(
            extra = pos.extra.toMutableMap().apply {
                val on = value == "是" || value.equals("true", ignoreCase = true)
                if (on) put("quickSearch", true) else remove("quickSearch")
            }
        )
        // searchMode / imageScaleType / ocrMode：枚举字符串
        // limitPosX / limitPosY：协议里是 Value（% / dp / px），原样存
        "searchMode", "imageScaleType", "ocrMode", "limitPosX", "limitPosY" ->
            pos.copy(
                extra = pos.extra.toMutableMap().apply {
                    if (value.isBlank()) remove(name) else put(name, value)
                }
            )
        else -> null
    }

    /** "90%" / "90" → 90，并夹到 0~100。空串 → null 表示清空。 */
    private fun parsePercent(raw: String): Int? {
        val t = raw.trim().removeSuffix("%").trim()
        if (t.isEmpty()) return null
        return t.toDoubleOrNull()?.toInt()?.coerceIn(0, 100)
    }

    /** 空串 → null；否则按 [ZValue] 校验后保留原字符串。 */
    private fun parseOrNull(raw: String): ZValue? =
        if (raw.isBlank()) null else ZValue.parse(raw)

    private fun truthy(v: String): Boolean =
        v.trim().let { it == "是" || it.equals("true", ignoreCase = true) || it == "1" }

    /**
     * 给某个动作换一个类型。
     *
     * ## 为什么这里特别小心
     *
     * `type` 和 `posData.type` 是**绑定**的（FIELDS.md 10.2）：
     *
     * ```js
     * const S = { 点击: location, 点击图片: image, 点击文字: text, ... }
     * ```
     *
     * 自动精灵在 `set posData` 时若发现不匹配，会**自动改 type**。
     * 我们也一样：换类型的同一时刻位置对象跟着换，否则存出来就是一个
     * 自相矛盾的动作 —— 「点击图片」带着一个 location 位置，
     * 执行器按 image 去找会永远匹配不到，而用户完全看不出哪里错了。
     */
    fun changeType(action: Z.Action, newType: Z.ActionType): Z.Action {
        val expected = expectedPosType(newType) ?: return action.copy(type = newType)
        val current = action.posData

        val newPos = when {
            current == null -> Z.PosData(type = expected)
            current.type == expected -> current
            else -> {
                // 保留能带过去的坐标，换掉类型
                val keepX = current.x
                val keepY = current.y
                val keepArea = current.limitArea
                if (expected == Z.PosType.LOCATION) {
                    Z.PosData(
                        type = Z.PosType.LOCATION,
                        x = keepX, y = keepY,
                        limitArea = keepArea
                    )
                } else {
                    Z.PosData(
                        type = expected,
                        limitArea = keepArea,
                        // 非坐标类型不支持 x/y，但保留原来的值没意义
                        x = null, y = null
                    )
                }
            }
        }
        return action.copy(type = newType, posData = newPos)
    }

    /** 动作类型要求的位置类型。返回 null = 这个动作没有位置。 */
    fun expectedPosType(type: Z.ActionType): Z.PosType? = when (type) {
        Z.ActionType.CLICK -> Z.PosType.LOCATION
        Z.ActionType.CLICK_IMAGE -> Z.PosType.IMAGE
        Z.ActionType.CLICK_TEXT -> Z.PosType.TEXT
        Z.ActionType.CLICK_COLOR -> Z.PosType.COLOR
        Z.ActionType.CLICK_NODE -> Z.PosType.NODE
        Z.ActionType.AI_CLICK -> Z.PosType.AI_LOC
        Z.ActionType.SWIPE -> Z.PosType.LOCATION
        else -> null
    }
}
