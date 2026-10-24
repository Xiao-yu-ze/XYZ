package com.pvz2.auto.zjs

import org.json.JSONObject
import kotlin.random.Random

/**
 * 自动精灵的长度值（`Value`）。
 *
 * 这是整个协议的地基：**所有坐标、尺寸、偏移字段都是它**。
 * 三种写法，原文见 `FIELDS.md` §0.2：
 *
 * ```
 * "50"   → 像素
 * "50dp" → 逻辑像素（density 无关）
 * "50%"  → 屏幕百分比
 * ```
 *
 * ## 为什么百分比是默认单位
 *
 * 解析规则是"**没有后缀就是百分比**，只有以数字结尾才当像素"：
 *
 * ```js
 * let t = UNIT_PERCENT;
 * e.endsWith("dp") ? (t=UNIT_DP, strip) : e.endsWith("%") ? (t=UNIT_PERCENT, strip)
 *   : /.*\d$/.test(e) && (t=UNIT_PX);
 * ```
 *
 * 所以初始化成 PERCENT、只有匹配到数字结尾才改成 PX。
 *
 * 这个默认值很关键：用户框选一个位置时，存成 `"30.13%"` 而不是 `"386"`，
 * 换台分辨率不同的设备照样点得准。存像素的话在另一台机器上就全偏了。
 *
 * ## 百分比基准是分开的
 *
 * **x / left / right 用屏幕宽，y / top / bottom 用屏幕高**。
 * 两者混用会让 16:9 屏上的坐标整体偏移。
 */
sealed interface ZValue {

    /** 解析出的原始字面量，保留用于回写。 */
    val raw: String

    data class Percent(override val raw: String, val value: Double) : ZValue
    data class Dp(override val raw: String, val value: Double) : ZValue
    data class Px(override val raw: String, val value: Double) : ZValue

    /**
     * 区间写法，例如 `"100-300"`、`"30%-70%"`、`"10dp-20dp"`。
     *
     * ## 为什么必须单独一个类型（原坑）
     *
     * FIELDS.md:55 明确「凡是 string 类型的时长/坐标，都允许 `"1-3"` 表示范围内随机」。
     * 旧实现是**在解析那一刻**就随机取一个值，然后把 `raw` 换成那个随机数：
     *
     * ```
     * parse("100-300")  →  Px("234.56", 234.56)     // 区间没了
     * parse("30%-70%")  →  Percent("30%-70%", 0.0)  // 两端带 % 时 toDoubleOrNull 失败 → 0
     * ```
     *
     * 第一种的后果是**把用户的脚本改坏**（存盘时按 `raw` 写回，区间永久消失，
     * 从此每次点同一个点）；第二种的后果是点击稳定落到屏幕左边缘（x=0）。
     * 两者都不报错。
     *
     * 正确做法是保留原文，只在 [toPx] 时才在区间内随机 —— 这样
     * ① 落盘原样、② 每次执行都重新随机，正是反检测想要的效果。
     */
    data class Range(val lo: ZValue, val hi: ZValue, override val raw: String) : ZValue

    /**
     * 换算成像素。
     *
     * @param percentBase 百分比基准 —— **x 轴传屏幕宽，y 轴传屏幕高**
     * @param density 屏幕密度，用于 dp
     */
    fun toPx(percentBase: Int, density: Float): Float = when (this) {
        is Percent -> (percentBase * value / 100.0).toFloat()
        is Dp -> (value * density).toFloat()
        is Px -> value.toFloat()
        // 两端各自换算成像素后再随机，所以 "30%-70%" 与 "384-896" 等价，
        // 混合单位（虽然没人这么写）也不会算错。
        is Range -> {
            val a = lo.toPx(percentBase, density)
            val b = hi.toPx(percentBase, density)
            if (b <= a) a else a + Random.nextFloat() * (b - a)
        }
    }

    companion object {

        /**
         * 解析一个字面量。
         *
         * 区间写法 `"1-3"` / `"30%-70%"`（协议里表示范围内随机）解析成
         * [Range]，**原文保留**，随机发生在 [toPx] 那一刻。
         *
         * 解析不出来时退化成 0%：宁可点到左上角也不要崩，
         * 而且 0% 在界面上很容易看出来是没设置。
         */
        fun parse(text: String?): ZValue {
            val s = text?.trim().orEmpty()
            if (s.isEmpty()) return Percent("0%", 0.0)

            // 区间：两端各自解析成 ZValue（所以带 % / dp 后缀也认）。
            // 负号："-5--1" 里第一个 '-' 是符号，所以从下标 1 开始找分隔符。
            //
            // ★ 以前这里用 toDoubleOrNull 判两端 —— "30%-70%" 的后半段
            //   是 "70%" 而不是数字，判不过去，于是整串落到 parseSingle，
            //   变成 Percent("30%-70%", 0.0)。现在改成递归解析。
            val dash = s.indexOf('-', startIndex = 1)
            if (dash > 0 && !s.endsWith("-")) {
                val lo = parseSingleOrNull(s.substring(0, dash))
                val hi = parseSingleOrNull(s.substring(dash + 1))
                if (lo != null && hi != null) return Range(lo, hi, s)
            }
            return parseSingle(s)
        }

        /** 不含区间的解析；认不出时退化成 0%（不崩）。 */
        private fun parseSingle(text: String): ZValue =
            parseSingleOrNull(text) ?: Percent("0%", 0.0)

        /** 不含区间的解析；认不出返回 null（供区间判断用，不能和"真的是 0%"混淆）。 */
        private fun parseSingleOrNull(text: String): ZValue? {
            val s = text.trim()
            if (s.isEmpty()) return null
            return when {
                s.endsWith("dp") ->
                    s.dropLast(2).toDoubleOrNull()?.let { Dp(s, it) }

                s.endsWith("%") ->
                    s.dropLast(1).toDoubleOrNull()?.let { Percent(s, it) }

                // 以数字结尾 = 像素（协议规则）
                s.lastOrNull()?.isDigit() == true ->
                    s.filter { it.isDigit() || it == '.' || it == '-' }.toDoubleOrNull()
                        ?.let { Px(s, it) }

                else -> null
            }
        }
    }
}

/**
 * 屏幕区域（`limitArea`），也就是 ROI。
 *
 * 协议允许三种写法（`FIELDS.md` §0.3）：
 *
 * ```
 * "33.04% 63.38% 38.41% 85.41%"        ← 左 上 右 下（文件里最常见）
 * "10%-20% 30%-40%"                     ← x1-x2 y1-y2
 * {"left":"10%","top":100,"right":"200dp","bottom":"50%"}
 * ```
 *
 * ## 注意：这是「左 上 右 下」不是「左 上 宽 高」
 *
 * 和 Android `Rect` 不一样 —— 第 3、4 个值是 right/bottom 的**绝对位置**，
 * 不是宽高。搞错的话 ROI 会缩成一条线或超出屏幕。
 */
data class ZArea(
    val left: ZValue,
    val top: ZValue,
    val right: ZValue,
    val bottom: ZValue
) {
    /**
     * 换算成像素矩形 `[left, top, right, bottom]`。
     *
     * ## 这里曾经返回的是 [左,上,宽,高]
     *
     * 而 [com.pvz2.auto.zjs.ZPosResolver.searchRect] 的契约、以及所有
     * 消费它的地方（执行器、预览绘制、匹配器）都按 `[左,上,右,下]` 用。
     *
     * 后果是:  `"320 180 960 540"` → `[320,180,640,360]`，被当成
     * right=640/bottom=360，于是搜索范围被算成 320×180 —— 只有真实
     * 区域的四分之一。现象是"模板比范围还大"或者"明明在画面里却找不到"，
     * 而且不会有任何报错。
     *
     * @param screenWidth  物理宽（left/right 的百分比基准）
     * @param screenHeight 物理高（top/bottom 的百分比基准）
     */
    fun toPxRect(screenWidth: Int, screenHeight: Int, density: Float): IntArray {
        val l = left.toPx(screenWidth, density).toInt()
        val t = top.toPx(screenHeight, density).toInt()
        val r = right.toPx(screenWidth, density).toInt()
        val b = bottom.toPx(screenHeight, density).toInt()
        // 夹到屏幕内并保证右下不小于左上 —— 越界的 ROI 会让 OpenCV 直接崩。
        val cl = l.coerceIn(0, screenWidth - 1)
        val ct = t.coerceIn(0, screenHeight - 1)
        val cr = r.coerceIn(cl + 1, screenWidth)
        val cb = b.coerceIn(ct + 1, screenHeight)
        return intArrayOf(cl, ct, cr, cb)
    }

    /** 协议里的落盘写法：`左 上 右 下`，空格分隔。 */
    override fun toString(): String =
        "${left.raw} ${top.raw} ${right.raw} ${bottom.raw}"

    companion object {

        /**
         * 从像素矩形构造区域 —— **框选 ROI 之后写回用的就是它**。
         *
         * ## 为什么用像素而不是百分比
         *
         * 自动精灵 存百分比是因为它要跑在用户各种分辨率的手机上。
         * 我们**只有一块虚拟屏，固定 1280×720**，所以像素既精确又简单 ——
         * 百分比反而会引入取整误差（"30.16%" 反算回 386.05 再取整）。
         *
         * 外部脚本里出现的 `%` 仍然能解析（[ZValue.parse]），只是我们自己
         * 写出去的永远是整数像素。
         */
        fun fromPxRect(x: Int, y: Int, w: Int, h: Int, screenWidth: Int, screenHeight: Int): ZArea {
            fun px(v: Int): ZValue = ZValue.Px(v.toString(), v.toDouble())
            return ZArea(
                left = px(x),
                top = px(y),
                right = px(x + w),
                bottom = px(y + h)
            )
        }

        /**
         * 对象写法：`{"left":"10%","top":100,"right":"200dp","bottom":"50%"}`。
         *
         * ## 原坑
         *
         * FIELDS.md:51 明确这是三种合法写法之一，但以前只实现了字符串分支
         * （`optString("limitArea")`）。对对象调用 `optString` 拿到的是**JSON 文本**
         * `{"left":...}`，按空格切只有一段 → 解析 null；而调用方
         * （[Z.PosData.parse]）又把 `limitArea` 记成"已处理"，
         * 于是这块区域**读写两头都丢**：搜索范围静默变成全屏。
         *
         * 四个键缺任何一个都返回 null（交给调用方保留原文），不做部分填充 ——
         * 半块 ROI 比没有 ROI 更危险。
         */
        fun parseObject(o: JSONObject?): ZArea? {
            if (o == null) return null
            for (k in listOf("left", "top", "right", "bottom")) {
                if (!o.has(k) || o.isNull(k)) return null
            }
            return ZArea(
                left = ZValue.parse(o.opt("left").toString()),
                top = ZValue.parse(o.opt("top").toString()),
                right = ZValue.parse(o.opt("right").toString()),
                bottom = ZValue.parse(o.opt("bottom").toString())
            )
        }

        fun parse(raw: String?): ZArea? {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty()) return null
            val parts = s.split(Regex("\\s+")).filter { it.isNotEmpty() }
            return when (parts.size) {
                4 -> ZArea(
                    left = ZValue.parse(parts[0]),
                    top = ZValue.parse(parts[1]),
                    right = ZValue.parse(parts[2]),
                    bottom = ZValue.parse(parts[3])
                )

                // "x1-x2 y1-y2" 形式：拆成两端。
                2 -> {
                    val (x1, x2) = splitRange(parts[0]) ?: return null
                    val (y1, y2) = splitRange(parts[1]) ?: return null
                    ZArea(
                        left = ZValue.parse(x1),
                        top = ZValue.parse(y1),
                        right = ZValue.parse(x2),
                        bottom = ZValue.parse(y2)
                    )
                }

                else -> null
            }
        }

        /** `"10%-20%"` → (`"10%"`, `"20%"`)；不是区间返回 null。 */
        private fun splitRange(text: String): Pair<String, String>? {
            val dash = text.indexOf('-', startIndex = 1)
            if (dash <= 0) return null
            return text.substring(0, dash) to text.substring(dash + 1)
        }
    }
}
