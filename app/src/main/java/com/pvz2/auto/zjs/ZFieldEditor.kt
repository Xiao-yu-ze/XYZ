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
        /*
         * ★ 候选字段必须**先**处理。
         *
         * 下面那段看到字段里有 "." 就当"位置前缀 + 字段名"（位置./起点./终点.），
         * 前缀不在 POS_PREFIXES 里直接 return null —— 于是 `cand.add`、
         * `cand.0.cond` 这些全被挡在门外：用户点「添加候选」毫无反应
         * （真机反馈）。
         */
        if (field.startsWith("cand.")) return applyCandidate(action, field, value)
        if (field == "onTimeoutMode") return applyTimeoutMode(action, value)

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
            // ★ 三个"时长/等待"字段只放行 `数字` 或 `数字-数字`（区间），其余一律拒收。
            //
            //   原坑：这里什么都不校验，于是 "3 s" / "abc" / "1-" 全都原样落盘。
            //   执行器 randomIn(value) 解析不出数字时**静默回落默认值**
            //   （交互动作 200ms、滑动 1000ms），用户看到的是"我明明填了 3 秒，
            //   它几乎立刻就跑完了"，而且没有任何报错。
            //   宁可在输入那一刻拒收并提示（页面会弹"值不合法，未保存"），
            //   也不要写进一个注定被忽略的值。
            //
            //   空串仍然合法 = 清空该字段（落盘时不写，执行器走默认值）。
            "duration" -> {
                val t = value.trim()
                if (t.isNotEmpty() && !isNumberOrRange(t)) return null
                c.copy(duration = t.ifBlank { null })
            }
            // 值的单位**单独一个字段** delayUnit（见下），这一行只放数字。
            //
            //   更早的原坑：描述器把值和单位拼成 "3 s" 塞进这一行，这里又原样
            //   写回 delay，执行器 randomIn("3 s") 解析不出数字 → 回落 200ms。
            "delay" -> {
                val t = value.trim()
                if (t.isNotEmpty() && !isNumberOrRange(t)) return null
                c.copy(delay = t.ifBlank { null })
            }
            // 时间单位（FIELDS.md §0.1：0=毫秒 1=秒 2=分钟 3=小时，默认 1=秒）
            //
            // 三种写法都收：界面选项（ms/s/min/h）、中文（毫秒/秒/分钟/小时）、
            // 协议数字（0~3）。界面文案以后改了也不会写不进去。
            "delayUnit" -> {
                val u = parseTimeUnit(value) ?: return null
                c.copy(delayUnit = u)
            }
            // 重复间隔同理：值、单位分两行，值同样只放行数字/区间。
            "repeatDelayValue" -> {
                val t = value.trim()
                if (t.isNotEmpty() && !isNumberOrRange(t)) return null
                c.copy(repeatDelayValue = t.ifBlank { null })
            }
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
            /*
             * 相似度是**唯一的容差旋钮**。
             *
             * 从 Maa 转来的颜色带 colorLower/colorUpper（逐通道区间，可能是非对称的）。
             * 如果只改 similarPercent 而区间不动，用户调旋钮就毫无反应 ——
             * 表现就是"调了没用"。所以这里同步把区间按新容差重算：
             * 容差 = (100 - 相似度)% × 255，围绕 `color` 对称展开。
             *
             * 代价：区间会从"非对称"变成"对称"（略宽）。这是可接受的取舍 ——
             * 用户要的是"一个能调的旋钮"，而不是两套并行、会互相矛盾的判定。
             */
            "similarPercent" -> if (value.isBlank()) {
                pos.copy(similarPercent = null)
            } else {
                parsePercent(value)?.let { pct ->
                    val tol = ((100 - pct) / 100.0 * 255.0).toInt().coerceIn(0, 255)
                    val rgb = ZFieldEditor.parseHex(pos.color.orEmpty())
                    val extra2 = if (rgb != null) {
                        pos.extra.toMutableMap().apply {
                            put("colorLower", listOf(
                                (rgb[0] - tol).coerceIn(0, 255),
                                (rgb[1] - tol).coerceIn(0, 255),
                                (rgb[2] - tol).coerceIn(0, 255)
                            ))
                            put("colorUpper", listOf(
                                (rgb[0] + tol).coerceIn(0, 255),
                                (rgb[1] + tol).coerceIn(0, 255),
                                (rgb[2] + tol).coerceIn(0, 255)
                            ))
                        }
                    } else {
                        pos.extra
                    }
                    pos.copy(similarPercent = pct, extra = extra2)
                }
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
        // imageFilter：匹配器真读它（grey/bw/reverse，ZRealMatcher:895-930），界面原来没入口。
        // 选「（无）」= 清掉键，与协议"缺省即不过滤"一致；filter 是同义键，只留一份。
        "imageFilter" -> pos.copy(
            extra = pos.extra.toMutableMap().apply {
                if (value.isBlank() || value == "（无）") remove("imageFilter")
                else {
                    put("imageFilter", value)
                    remove("filter")
                }
            }
        )
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
    /** `#rrggbb` → [r,g,b]；不带 # 也认。解析不了返回 null。 */
    private fun parseHex(raw: String): IntArray? {
        val h = raw.trim().removePrefix("#")
        if (h.length != 6) return null
        val v = h.toIntOrNull(16) ?: return null
        return intArrayOf((v shr 16) and 0xFF, (v shr 8) and 0xFF, v and 0xFF)
    }

    /** 条件类型 → 它该用的位置类型。 */
    private fun posTypeOf(t: Z.ConditionType): Z.PosType = when (t) {
        Z.ConditionType.IMAGE -> Z.PosType.IMAGE
        Z.ConditionType.TEXT -> Z.PosType.TEXT
        Z.ConditionType.COLOR -> Z.PosType.COLOR
        else -> Z.PosType.LOCATION
    }

    /**
     * 动作类型在**帮助表**里的键：`wire` 或 `wire·controlRunType`。
     *
     * 6 个「控制执行」子类型必须带 controlRunType 才分得开（否则六条说明一模一样），
     * 加动作的选择器和卡片里的「类型」行都得用同一套键，不然问号会空掉。
     */
    fun actionTypeHelpKey(t: Z.ActionType): String =
        t.controlRunType?.let { "${t.wire}·$it" } ?: t.wire

    private fun parsePercent(raw: String): Int? {
        val t = raw.trim().removeSuffix("%").trim()
        if (t.isEmpty()) return null
        return t.toDoubleOrNull()?.toInt()?.coerceIn(50, 100)
    }

    /** 空串 → null；否则按 [ZValue] 校验后保留原字符串。 */
    private fun parseOrNull(raw: String): ZValue? =
        if (raw.isBlank()) null else ZValue.parse(raw)

    /**
     * 数字或区间：`"100"` / `"100-300"`。
     *
     * 协议里 `delay` / `duration` / `repeatDelayValue` 都是这个形状
     * （FIELDS.md §0.4：`"1-3"` = 范围内随机）。执行器 `randomIn()` 也只认这一种，
     * 其它写法会被静默当成"没填"。
     *
     * 不接受负数：这几处语义上都是时长，负值无意义，写进去只会被当成解析失败。
     * 调用方负责 `trim()`。
     */
    private fun isNumberOrRange(s: String): Boolean = NUMBER_OR_RANGE.matches(s)

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
     *
     * ## ★ 会丢数据 —— 调用方必须先确认
     *
     * 换类型会重建位置对象，有两种情况**不可逆地丢东西**：
     *  1. 新类型不需要位置（按键 / 打开链接 / 控制执行…）→ 整个 `posData` 丢掉；
     *  2. 新类型要位置但不是图片（点击图片 → 点击文字 / 点击）→ `imageData` 丢掉。
     *
     * `imageData` 是用户框选 + PNG 编码出来的（一次框选、一次编码），丢了没有撤销；
     * `limitArea` 也是框出来的。本类不做弹窗（它不认识 Context），所以**调用方
     * 必须**在提交「类型」之前先问 [changeTypeWouldLoseTemplate]，为 true 就弹一次
     * 确认再提交。否则就是"点一下下拉框，模板没了，还没有任何提示"。
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
                // 换类型必须清掉它，否则 toJson 会优先写回旧的原串：
                // 界面显示「控制执行·stop」，文件里还是那个认不出的值。
                rawControlRunType = null,
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
                // 换类型必须清掉它，否则 toJson 会优先写回旧的原串：
                // 界面显示「控制执行·stop」，文件里还是那个认不出的值。
                rawControlRunType = null,
                posData = newPos,
                startPos = action.startPos ?: newPos,
                endPos = action.endPos ?: newPos
            )
        }
        // 换类型要清掉 rawControlRunType：toJson 会优先写回那个旧原串，
        // 否则界面显示「控制执行·stop」，文件里还是那个认不出的值。
        // 滑动不持有 posData（协议 10.7 只有 startPos/endPos），换类型时同理
        return action.copy(
            type = newType,
            rawControlRunType = null,
            posData = if (newType == Z.ActionType.SWIPE) null else newPos,
            startPos = if (newType == Z.ActionType.SWIPE) (action.startPos ?: newPos) else action.startPos,
            endPos = if (newType == Z.ActionType.SWIPE) (action.endPos ?: newPos) else action.endPos
        )
    }

    /** 动作类型要求的位置类型。返回 null = 这个动作没有位置。 */
    fun expectedPosType(type: Z.ActionType): Z.PosType? = when (type) {
        Z.ActionType.CLICK -> Z.PosType.LOCATION
        Z.ActionType.CLICK_IMAGE -> Z.PosType.IMAGE
        Z.ActionType.CLICK_TEXT -> Z.PosType.TEXT
        Z.ActionType.CLICK_COLOR -> Z.PosType.COLOR
        Z.ActionType.SWIPE -> Z.PosType.LOCATION
        else -> null
    }

    /**
     * 把 [action] 换成 [newType] 会不会丢掉用户已经做好的**模板或识别范围**。
     *
     * ★ 纯函数，不碰 Context、不改任何东西 —— 只回答"要不要先问一句"。
     *   页面在提交「类型」下拉之前调它；返回 true 就弹一次确认，
     *   用户点「取消」就别改（见 [changeType] 的 KDoc）。
     *
     * 判定覆盖两条真实的丢数据路径（[changeType] 会重建位置对象）：
     *  1. **新类型不需要位置**（按键 / 打开链接 / 控制执行…）→ 整个 `posData`
     *     被丢掉，模板和范围一起没；
     *  2. **新类型要位置、但不是图片**（点击图片 → 点击文字 / 点击）→ 重建出来的
     *     `PosData` 只保留 `limitArea`，`imageData` 保不住。
     *
     * 注意第二种容易被漏掉：从"点击图片"切到"点击文字"看起来只是换了识别方式，
     * 但模板（base64）会消失，而用户完全没有心理预期。
     *
     * 不返回 true 的情况：位置类型不变（图片 → 图片，或坐标 → 坐标）只清/留
     * `limitArea`，模板不受影响；以及本来就没有模板也没有范围（没什么可丢的）。
     *
     * 说明：坐标（`x`/`y`）在切到无位置类型时同样会丢，但重新取点的成本远低于
     * 重新截图 + 编码，所以不纳入"要不要确认"的判据 —— 否则每次换类型都要弹窗，
     * 用户会条件反射地点掉。
     */
    fun changeTypeWouldLoseTemplate(action: Z.Action, newType: Z.ActionType): Boolean {
        if (newType == action.type) return false
        val pos = action.posData ?: return false
        val hasTemplate = pos.imageData != null
        val hasArea = pos.limitArea != null
        if (!hasTemplate && !hasArea) return false

        val expected = expectedPosType(newType)
        // ① 新类型没有位置 → 整个 posData 丢弃
        if (expected == null) return true
        // ② 新类型要位置但不是图片 → 重建 PosData 时 imageData 保不住
        if (hasTemplate && expected != Z.PosType.IMAGE) return true
        // ③ 位置类型没变（或仍要图片）：只保留 limitArea，不会丢东西
        return false
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

    /**
     * 时长/等待类字段的合法形状：`100` 或 `100-300`（FIELDS.md §0.4）。
     *
     * 见 [isNumberOrRange]。放成常量是为了不在每次输入时重新编译正则。
     */
    private val NUMBER_OR_RANGE = Regex("^\\d+(-\\d+)?$")

    /** 布尔型 extra。写回 true；清空 = 删掉这个键（缺省即 false）。 */
    private val EXTRA_BOOLS = setOf(
        "highSpeedTap", "clearBeforeInput", "ContinueParentExecute",
        "closeBeforeOpen", "onlyClose",
        // 执行脚本：跑完子脚本要不要回到本脚本（缺省 false = 整条任务结束）
        "continueCurrentAfterFinish",
        // 设置变量：弹窗只问一次
        "dialogShowOnce"
    )

    /** 整型 extra。`keyCode` 就是 Android KeyEvent keycode（FIELDS.md 10.10）。 */
    private val EXTRA_INTS = setOf(
        "keyCode",
        // 游戏动作：卡槽 1-8、行 1-5、列 1-9（都是整数，空串=删键）
        "pvzSlot", "pvzRow", "pvzCol"
    )

    /** 游戏动作属于哪条链（缺省单次；空串=删键）。 */
    private val EXTRA_CHAINS = setOf("pvzChain")

    /** 字符串型 extra。空串 = 删掉这个键。 */
    private val EXTRA_STRINGS = setOf(
        "jumpToPosition", "aimActionPosition", "appPackageName",
        "link", "promptType", "promptText", "inputText",
        // ★ 运行JS代码 / 执行脚本 的参数。没有这两项时，界面上改完会弹
        //   「值不合法，未保存」—— 用户以为改了，其实文件没动。
        "jsCode", "filePath", "promptTitle",
        // 设置变量的交互弹窗（FIELDS 576-580）
        "dialogTitle", "dialogOKText", "dialogCancelText", "dialogAutoClickBtn",
        // 识别后等待（Maa 的 pre_delay 位置）
        "delayAfterLocate"
    )

    /** 取出（或新建）`extra["vars"]` 数组，改完写回；空数组就把键删掉。 */
    private fun withVars(action: Z.Action, block: (org.json.JSONArray) -> Unit): Z.Action {
        val raw = action.extra["vars"]
        val arr = runCatching {
            when (raw) {
                is org.json.JSONArray -> raw
                is String -> org.json.JSONArray(raw)
                null -> org.json.JSONArray()
                else -> org.json.JSONArray(raw.toString())
            }
        }.getOrNull() ?: org.json.JSONArray()
        block(arr)
        return action.copy(
            extra = action.extra.toMutableMap().apply {
                if (arr.length() == 0) remove("vars") else put("vars", arr)
            }
        )
    }

    /** 动作专属字段的写回。不认识的键返回 null（调用方会提示"值不合法"）。 */
    /**
     * 「属于哪条链」界面值是中文（单次/循环/收尾），落盘要写 wire（once/loop/end）。
     *
     * 空串 → null（删键），正好等于缺省「单次」，不往文件里塞默认值。
     */
    fun chainKeyOf(label: String): String? {
        val t = label.trim()
        if (t.isEmpty()) return null
        return PvzChainModel.LABELS.firstOrNull { it.second == t }?.first ?: t
    }

    private fun applyExtra(action: Z.Action, field: String, value: String): Z.Action? = when {
        // 布尔：关掉时**删键**而不是写 false。
        // 协议里这些开关缺省就是 false，写一堆 `"highSpeedTap":false` 只是脏文件
        // （同一个文件里 `quickSearch` 也是这个写法，保持一致）。
        field in EXTRA_BOOLS ->
            action.copy(extra = extraWith(action.extra, field, truthy(value).takeIf { it }))

        field in EXTRA_INTS ->
            action.copy(extra = extraWith(action.extra, field, value.trim().toIntOrNull()))

        field in EXTRA_CHAINS ->
            action.copy(extra = extraWith(action.extra, field, chainKeyOf(value)))

        /*
         * `vars`（设置变量）：界面用 `名字=值;名字2=值2` 表达。
         *
         * ★ 必须**合并**而不是重建：`value` 里还有 showInput / mustInput /
         *   rememberInputValue / showInputLabel 这些字段（FIELDS.md §8.2），
         *   重建会把"运行时问用户"的设置整块丢掉。
         *
         * 值能当数字就按 number 存、否则 string —— 协议里 varType 决定后面
         * 怎么求值，类型存错会让「关卡+1」变成字符串拼接。
         */
        field == "vars" -> withVars(action) { arr ->
            val byName = HashMap<String, org.json.JSONObject>()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { o ->
                    val n = o.optString("name").trim()
                    if (n.isNotEmpty()) byName[n] = o
                }
            }
            val keep = ArrayList<org.json.JSONObject>()
            value.split(';', '\n', '；').forEach { piece ->
                val seg = piece.trim()
                if (seg.isEmpty()) return@forEach
                val eq = seg.indexOf('=')
                if (eq <= 0) return@forEach
                val name = seg.substring(0, eq).trim()
                val raw = seg.substring(eq + 1).trim()
                if (name.isEmpty()) return@forEach
                val isNum = raw.toDoubleOrNull() != null
                val o = byName[name] ?: org.json.JSONObject().apply { put("name", name) }
                val v = o.optJSONObject("value") ?: org.json.JSONObject()
                v.put("varType", if (isNum) "number" else "string")
                v.put("valueExp", if (isNum) raw else "\"" + raw + "\"")
                o.put("value", v)
                keep.add(o)
            }
            keep.forEachIndexed { i, o -> arr.put(i, o) }
            while (arr.length() > keep.size) arr.remove(arr.length() - 1)
        }

        // 哪些变量要在"点运行"时问用户（showInput = 从键盘输入）
        field == "vars.input" -> withVars(action) { arr ->
            val want = value.split(';', '\n', '；', ',', '，')
                .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val n = o.optString("name").trim()
                val v = o.optJSONObject("value") ?: org.json.JSONObject()
                if (n in want) v.put("showInput", true) else v.remove("showInput")
                o.put("value", v)
            }
        }

        // 哪些是"不必填"（协议里 mustInput 默认 true）
        field == "vars.mustInput" -> withVars(action) { arr ->
            val optional = value.split(';', '\n', '；', ',', '，')
                .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val n = o.optString("name").trim()
                val v = o.optJSONObject("value") ?: org.json.JSONObject()
                if (n in optional) v.put("mustInput", false) else v.remove("mustInput")
                o.put("value", v)
            }
        }

        // 记住上次输入（对所有"要问"的变量生效）
        field == "vars.remember" -> withVars(action) { arr ->
            val on = truthy(value)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val v = o.optJSONObject("value") ?: continue
                if (on) v.put("rememberInputValue", true) else v.remove("rememberInputValue")
            }
        }

        /*
         * 找字滤镜：写 `filter` 键（匹配器的 filterType 先看 imageFilter、再看 filter）。
         * 「（无）」= 把键删掉，和协议"缺省即不过滤"一致。
         */
        field == "filter" -> action.copy(
            extra = action.extra.toMutableMap(),
            posData = action.posData
        ).let { a ->
            a.posData?.let { pd ->
                a.copy(
                    posData = pd.copy(
                        extra = pd.extra.toMutableMap().apply {
                            if (value.isBlank() || value == "（无）") {
                                remove("filter"); remove("imageFilter")
                            } else {
                                put("filter", value); remove("imageFilter")
                            }
                        }
                    )
                )
            } ?: a
        }

        // 「等待候选」：候选编辑 / 超时行为（实现见下面两个 helper）
        field.startsWith("cand.") -> applyCandidate(action, field, value)
        field == "onTimeoutMode" -> applyTimeoutMode(action, value)

        field in EXTRA_STRINGS ->
            action.copy(extra = extraWith(action.extra, field, value.ifBlank { null }))

        else -> null
    }

    /**
     * 改 extra 里的一个键：`null` 表示删掉（落盘时就不写，等于回默认值）。
     *
     * ★ 顺带清掉**大小写变体**。
     *
     *   协议里这些键是大小写敏感的（`ContinueParentExecute` 首字母大写），
     *   而用户拿到的手写/别家工具生成的脚本里可能有 `continueParentExecute`
     *   这种变体。不清的话会出现两条同义键：
     *     · 我们在界面上写的是正确大小写的那条 → 执行器读得到，行为对；
     *     · 旧变体留在文件里 → 界面上多一行灰色只读、自动精灵那边读哪个不确定。
     *   所以只要写的键和某个已有键"仅大小写不同"，就以这次写入为准，把变体删掉。
     *   （只删变体，不动其它键 —— 用户的不认识字段必须原样保留。）
     */
    /** 「等待候选」的候选编辑：cand.<i>.cond / .color / .text / .jumpTo / .del，以及 cand.add。 */
    private fun applyCandidate(action: Z.Action, field: String, value: String): Z.Action? {
        val rest = field.removePrefix("cand.")
        if (rest == "add") {
            return action.copy(candidates = action.candidates + Z.Action.Candidate())
        }
        val dot = rest.indexOf('.')
        if (dot <= 0) return null
        val i = rest.substring(0, dot).toIntOrNull() ?: return null
        val what = rest.substring(dot + 1)
        val list = action.candidates.toMutableList()
        val old = list.getOrNull(i) ?: return null

        fun withCond(c: Z.Condition?): Z.Action {
            list[i] = old.copy(condition = c)
            return action.copy(candidates = list)
        }

        return when (what) {
            "del" -> {
                if (value.trim() != "删除") return action
                list.removeAt(i)
                action.copy(candidates = list)
            }
            "jumpTo" -> {
                list[i] = old.copy(jumpTo = value.trim().ifBlank { null })
                action.copy(candidates = list)
            }
            "cond" -> {
                val t = when (value.trim()) {
                    "出现颜色" -> Z.ConditionType.COLOR
                    "出现文字" -> Z.ConditionType.TEXT
                    "出现图片" -> Z.ConditionType.IMAGE
                    else -> null
                }
                if (t == null) withCond(null) else {
                    // 换类型时保留已有位置对象（颜色/文字都靠它），没有就按类型新建
                    val pos = old.condition?.posData ?: Z.PosData(type = posTypeOf(t))
                    withCond(Z.Condition(type = t, posData = pos))
                }
            }
            "color" -> {
                val c = old.condition ?: return null
                val pd = c.posData ?: Z.PosData(type = Z.PosType.COLOR)
                withCond(c.copy(posData = pd.copy(color = value.trim().ifBlank { null })))
            }
            "text" -> {
                val c = old.condition ?: return null
                val pd = c.posData ?: Z.PosData(type = Z.PosType.TEXT)
                withCond(c.copy(posData = pd.copy(text = value.trim().ifBlank { null })))
            }
            else -> null
        }
    }

    /** 等待候选「超时后」：一个枚举同时管 onTimeoutStop 与 onTimeoutJump。 */
    private fun applyTimeoutMode(action: Z.Action, value: String): Z.Action {
        val extra = action.extra.toMutableMap()
        when (value.trim()) {
            "停止任务" -> {
                extra["onTimeoutStop"] = true
                extra.remove("onTimeoutJump")
            }
            "跳到标识" -> {
                extra.remove("onTimeoutStop")
                if ((extra["onTimeoutJump"] as? String).isNullOrBlank()) extra["onTimeoutJump"] = ""
            }
            else -> {
                extra.remove("onTimeoutStop")
                extra.remove("onTimeoutJump")
            }
        }
        return action.copy(extra = extra)
    }

    private fun extraWith(
        src: Map<String, Any?>,
        key: String,
        v: Any?
    ): MutableMap<String, Any?> {
        val m = LinkedHashMap(src)
        val variants = m.keys.filter { it != key && it.equals(key, ignoreCase = true) }
        variants.forEach { m.remove(it) }
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
        type.controlRunType?.let { "${type.wire}·${Z.controlRunTypeLabel(it)}" } ?: type.wire

    /** 「类型」下拉的选项。必须与 [actionTypeLabel] 一一对应。 */
    /**
     * 「类型」下拉的选项。
     *
     * ★ AI 点击不出现在下拉里：它要调云端 AI 服务（协议 `aiCloud`），
     *   本机没有那个接口，选了也跑不起来。枚举本身**保留** ——
     *   删掉的话，别人脚本里已有的 AI 点击会因为"未知类型"而解析失败，
     *   整份文件打不开（协议 P8() 对未知 type 是抛异常）。
     */
    /**
     * 「能加/能换」的动作类型列表（界面专用）。
     *
     * 统一在一处过滤：`运行多个动作` 在子动作列表里不能嵌套（includeRunMulti=false）；
     * 各处如果各自 `ActionType.entries.sortedBy{}`，过滤就会漏掉某一处。
     */
    fun addableActionTypes(includeRunMulti: Boolean = true): List<Z.ActionType> =
        Z.ActionType.entries
            .filter { includeRunMulti || it != Z.ActionType.RUN_MULTI }
            .sortedBy { if (it.executable) 0 else 1 }

    fun actionTypeLabels(): List<String> =
        Z.ActionType.entries.map { actionTypeLabel(it) }

    /** 从下拉选中的标签找回枚举。认不出返回 null（调用方提示不合法）。 */
    private fun actionTypeFrom(raw: String): Z.ActionType? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        /*
         * ★ 必须按**标签反查枚举**，不能用 `entries[i]`。
         *
         *   `actionTypeLabels()` 过滤掉了 AI_CLICK（它要云接口，本机不跑），
         *   而 `ActionType.entries` 里 AI_CLICK 排第 6 —— 于是从第 6 项起
         *   下标整体错位一位：用户在下拉里选「滑动」，拿到的却是「AI点击」，
         *   还会连带把 posData 重建成 aiLoc 存进文件。这是静默的数据损坏。
         */
        Z.ActionType.entries.firstOrNull { actionTypeLabel(it) == t }?.let { return it }
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
