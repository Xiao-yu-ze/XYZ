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

        // ---- 动作类型：转发给 [changeType]
        //
        // ★ 原坑：「类型」行是只读、没有 field，而 changeType() 全仓没有调用方
        //   （死代码）。用户想把「点击图片」改成「点击文字」只能删了重建 ——
        //   模板、阈值、范围全丢。现在描述器把「类型」行出成 ENUM
        //   （选项见 [actionTypeLabels]），选完从这里转发，位置对象由
        //   changeType 一并换掉（type 与 posData.type 是**绑定**的，FIELDS.md 10.2）。
        if (field == "type") {
            val t = actionTypeFrom(value) ?: return null
            return changeType(action, t)
        }

        // ---- 通用字段
        val c = action.common
        val newCommon = when (field) {
            "desc" -> c.copy(desc = value.ifBlank { null })
            "jumpId" -> c.copy(jumpId = value.ifBlank { null })
            "duration" -> c.copy(duration = value.ifBlank { null })
            // ★ 只收纯数字或区间（"3" / "1-3"）。单位**单独一个字段** delayUnit。
            //
            //   原坑：描述器把值和单位拼成 "3 s" 塞进这一行，这里又原样写回
            //   delay，执行器 randomIn("3 s") 解析不出数字 → 回落默认 200ms。
            //   用户改的等待等于没改，界面上却一直显示 "3 s"，没有任何报错。
            "delay" -> c.copy(delay = value.ifBlank { null })
            // 时间单位（FIELDS.md §0.1：0=毫秒 1=秒 2=分钟 3=小时，默认 1=秒）
            //
            // 三种写法都收：界面选项（ms/s/min/h）、中文（毫秒/秒/分钟/小时）、
            // 协议数字（0~3）。界面文案以后改了也不会写不进去。
            "delayUnit" -> {
                val u = parseTimeUnit(value) ?: return null
                c.copy(delayUnit = u)
            }
            // 重复间隔同理：值、单位分两行（原坑与 delay 完全一样）
            "repeatDelayValue" -> c.copy(repeatDelayValue = value.ifBlank { null })
            "repeatDelayUnit" -> {
                val u = parseTimeUnit(value) ?: return null
                c.copy(repeatDelayUnit = u)
            }
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
            // ★ 不是通用字段 → 交给「动作类型专属字段」表（写 extra）。
            //
            //   原坑：这里直接 `return null`，于是 keyCode / inputText /
            //   jumpToPosition 这些动作专属参数**根本没有写回通道** ——
            //   界面要么没这一行，要么只读，动作建出来却配不了。
            else -> return applyExtra(action, field, value)
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
        // 相似度阈值，协议范围 **50~100**（FIELDS.md 3.2 `minSimilarPercent` 默认 90 /
        // FIELDS.md 3.4 `similarPercent` 默认 100，两者都写明 50–100）。
        //
        // ★ 原坑：这里按 0~100 夹。填 0 会被当成合法值存进脚本 ——
        //   阈值 0 等于「什么都算命中」，动作会在整屏上乱点；填 10 也一样不合法。
        //   现在按协议下限夹到 50：用户想表达"最宽松"就是 50，不会再写进坏值。
        //   用户常填 "90%" 或 "90"，两种都收；清空 = 回到协议默认值。
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

    /**
     * "90%" / "90" → 90，并夹到协议允许的 **50~100**。空串 → null 表示清空。
     *
     * ★ 原坑：夹到 0~100。0~49 在协议里不是合法阈值（FIELDS.md 3.2/3.4 都写 50–100），
     *   写进脚本后 自动精灵 那边也读不了；阈值 0 更会让匹配"永远命中"。
     */
    private fun parsePercent(raw: String): Int? {
        val t = raw.trim().removeSuffix("%").trim()
        if (t.isEmpty()) return null
        return t.toDoubleOrNull()?.toInt()?.coerceIn(50, 100)
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
        val expected = expectedPosType(newType)
            ?: return action.copy(
                // ★ 新类型不需要位置（按键、打开链接、控制执行…）。
                //
                //   原坑：这里只 copy(type)，**旧 posData 原样留着** ——
                //   于是「点击」改成「按键」后，脚本里会出现一条带 posData 的
                //   按键动作。自动精灵按 type 取字段，多出来的 posData 是垃圾；
                //   而我们的界面还会继续显示"位置 X/Y"，用户以为自己改错了。
                type = newType,
                posData = null
            )
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

        // ★ 换成「滑动」时把起终点补齐。
        //
        //   滑动的位置在 startPos / endPos，不在 posData（FIELDS.md 10.7）。
        //   原来切到滑动只写 posData，startPos/endPos 仍是 null → 解析器拿不到
        //   起点，动作直接跑不了，而界面上看起来"有坐标"。用 posData 兜底。
        if (newType == Z.ActionType.SWIPE) {
            return action.copy(
                type = newType,
                posData = newPos,
                startPos = action.startPos ?: newPos,
                endPos = action.endPos ?: newPos
            )
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

    // ------------------------------------------------------ 动作类型专属字段
    //
    // FIELDS.md §10：26 种动作各自的参数（keyCode / inputText / jumpToPosition …）
    // 在模型里都落在 `Action.extra` —— ZModel 刻意不逐个建属性，就是为了让不认识的
    // 字段也能原样带过去。
    //
    // ★ 原坑：界面对 extra 只做**只读**兜底展示（ZActionDescriber 尾部那段），
    //   写回通道更是不存在（apply 的 else 直接 return null）。于是
    //   「按键」没有 keyCode → 运行时被 skip（ZExecutor 报"缺 keyCode"）、
    //   「跳转到」没有目标位置 → 跳不动。「能建、能存、配不了」最难查。
    //
    // 这里给最小可用集：能改的键见下面三张表，描述器照同一批键出可编辑行
    // （去重按"本次实际出了哪些行"做，不能用全局集合 —— 全局集合会把别的动作类型
    //  带来的同名键也一起藏掉，那种键应当继续以只读行展示）。

    /** 布尔型 extra。写回 true；清空 = 删掉这个键（缺省即 false）。 */
    private val EXTRA_BOOLS = setOf(
        "highSpeedTap", "clearBeforeInput", "ContinueParentExecute",
        "closeBeforeOpen", "onlyClose"
    )

    /** 整型 extra。`keyCode` 就是 Android KeyEvent keycode（FIELDS.md 10.10）。 */
    private val EXTRA_INTS = setOf("keyCode")

    /** 字符串型 extra。空串 = 删掉这个键。 */
    private val EXTRA_STRINGS = setOf(
        "jumpToPosition", "aimActionPosition", "appPackageName",
        "link", "promptType", "promptText", "inputText"
    )

    /** 动作专属字段的写回。不认识的键返回 null（调用方会提示"值不合法"）。 */
    private fun applyExtra(action: Z.Action, field: String, value: String): Z.Action? = when {
        // 布尔：关掉时**删键**而不是写 false。
        // 协议里这些开关缺省就是 false，写一堆 `"highSpeedTap":false` 只是脏文件
        // （同一个文件里 `quickSearch` 也是这个写法，保持一致）。
        field in EXTRA_BOOLS ->
            action.copy(extra = extraWith(action.extra, field, truthy(value).takeIf { it }))

        field in EXTRA_INTS ->
            action.copy(extra = extraWith(action.extra, field, value.trim().toIntOrNull()))

        field in EXTRA_STRINGS ->
            action.copy(extra = extraWith(action.extra, field, value.ifBlank { null }))

        else -> null
    }

    /** 改 extra 里的一个键：`null` 表示删掉（落盘时就不写，等于回默认值）。 */
    private fun extraWith(
        src: Map<String, Any?>,
        key: String,
        v: Any?
    ): MutableMap<String, Any?> {
        val m = LinkedHashMap(src)
        if (v == null) m.remove(key) else m[key] = v
        return m
    }

    /**
     * 时间单位。
     *
     * 界面选项（ms/s/min/h）、中文（毫秒/秒/分钟/小时）、协议数字（0~3）、
     * 枚举名（SECONDS）都收 —— 界面文案改一次不至于把落盘单位写坏。
     */
    private fun parseTimeUnit(raw: String): Z.TimeUnit? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        t.toIntOrNull()?.let { wire -> return Z.TimeUnit.entries.firstOrNull { it.wire == wire } }
        Z.TimeUnit.entries.firstOrNull { it.name.equals(t, ignoreCase = true) }?.let { return it }
        return when (t.lowercase()) {
            "ms", "毫秒" -> Z.TimeUnit.MILLISECONDS
            "s", "秒" -> Z.TimeUnit.SECONDS
            "min", "分钟" -> Z.TimeUnit.MINUTES
            "h", "小时" -> Z.TimeUnit.HOURS
            else -> null
        }
    }

    /**
     * 动作类型的界面标签。
     *
     * 6 个「控制执行」在协议里共用 `type:"控制执行"`，靠 `controlRunType` 区分
     * （FIELDS.md 第 1 节），所以标签必须带上它 —— 否则下拉里会出现六个
     * 一模一样的「控制执行」，选哪个都分不清。
     */
    fun actionTypeLabel(type: Z.ActionType): String =
        type.controlRunType?.let { "${type.wire}·$it" } ?: type.wire

    /** 「类型」下拉的选项。必须与 [actionTypeLabel] 一一对应。 */
    fun actionTypeLabels(): List<String> = Z.ActionType.entries.map { actionTypeLabel(it) }

    /** 从下拉选中的标签找回枚举。认不出返回 null（调用方提示不合法）。 */
    private fun actionTypeFrom(raw: String): Z.ActionType? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        actionTypeLabels().forEachIndexed { i, label ->
            if (label == t) return Z.ActionType.entries[i]
        }
        Z.ActionType.entries.firstOrNull { it.name.equals(t, ignoreCase = true) }?.let { return it }
        // "控制执行·jumpTo" 拆开按 wire + controlRunType 找
        val dot = t.indexOf('·')
        if (dot > 0) {
            val wire = t.substring(0, dot).trim()
            val ctrl = t.substring(dot + 1).trim()
            return Z.ActionType.entries.firstOrNull {
                it.wire == wire && it.controlRunType == ctrl
            }
        }
        // 只有 wire（非控制执行的类型）
        return Z.ActionType.ofWire(t, null)
    }
}
