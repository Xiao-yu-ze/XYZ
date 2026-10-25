package com.pvz2.auto.zjs

/**
 * 把条件翻译成一句人话。
 *
 * ## 为什么要摘要而不是逐字段列出
 *
 * 条件挂在动作上，编辑器里一行显示一个条件。逐字段列会挤满屏幕，
 * 而且用户最常问的就是"这个动作在什么条件下才跑"，
 * 一句话答完最有用。
 */
object ZConditionDescriber {

    /** 一句话摘要，例如「图片存在」「随机 30%」「运行次数 < 5」。 */
    fun summary(c: Z.Condition): String {
        val base = when (c.type) {
            Z.ConditionType.IMAGE -> "图片匹配" + posHint(c)
            Z.ConditionType.TEXT -> "文字匹配" + posHint(c)
            Z.ConditionType.COLOR -> "颜色匹配" + posHint(c)
                Z.ConditionType.AI_CLOUD -> "AI 识别" + posHint(c)
            Z.ConditionType.LIMIT_RUN_TIMES -> "运行次数限制"
            Z.ConditionType.SCRIPT_RUN_STATE -> "脚本运行状态"
            Z.ConditionType.RANDOM -> "随机 " + percentOf(c) + "%"
            Z.ConditionType.TIME_AFTER -> "指定时间之后"
            Z.ConditionType.TIME_INTERVAL -> "时间区间内"
            Z.ConditionType.JS_EXPRESSION -> "JS 表达式"
            Z.ConditionType.CONDITION_SET -> "条件组"
            Z.ConditionType.AROUND -> "周围条件"
        }

        val parts = ArrayList<String>()

        // 反相是最容易看漏的，放最前面。
        //
        // ★ 不能 return —— 「反相」和「不成立时重复检查」是两个**独立开关**，
        //   可以同时开启（FIELDS.md:254/256）。提前 return 会把重试次数吞掉，
        //   用户就看不到这条条件其实还会重试 5 次。
        if (c.runWhenFalse) parts += "取反"

        parts += base

        if (c.repeatWhenFalse) {
            val lim = c.repeatWhenFalseLimitTimes
            parts += if (lim == null || lim == 0) "不成立时反复检查" else "不成立时最多重试 $lim 次"
        }
        return parts.joinToString("，")
    }

    /** 条件里带位置的，补一句"在哪"。 */
    private fun posHint(c: Z.Condition): String {
        val pos = c.posData ?: return ""
        return when (pos.type) {
            Z.PosType.LOCATION -> {
                val x = pos.x?.raw
                val y = pos.y?.raw
                if (x != null && y != null) " @($x,$y)" else ""
            }

            Z.PosType.IMAGE -> {
                val img = pos.imageData
                val w = img?.width
                val h = img?.height
                if (w != null && h != null) " ${w}×$h" else ""
            }

            Z.PosType.TEXT -> pos.text?.let { " \"$it\"" }.orEmpty()
            Z.PosType.COLOR -> pos.color?.let { " #$it" }.orEmpty()
            else -> ""
        }
    }

    /**
     * `random` 条件的百分比。
     *
     * 字段名是 `percent`（FIELDS.md:273），**默认 50**。
     * 缺省时显示 50 而不是 "?" —— 真实脚本里默认值通常不写，
     * 显示 "?" 会让用户以为这个条件没配置好。
     */
    private fun percentOf(c: Z.Condition): String {
        val v = c.extra["percent"] ?: c.extra["probability"]
        return v?.toString()?.takeIf { it.isNotBlank() } ?: "50"
    }
}
