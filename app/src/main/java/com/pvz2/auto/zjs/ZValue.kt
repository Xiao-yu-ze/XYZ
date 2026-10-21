package com.pvz2.auto.zjs

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
     * 换算成像素。
     *
     * @param percentBase 百分比基准 —— **x 轴传屏幕宽，y 轴传屏幕高**
     * @param density 屏幕密度，用于 dp
     */
    fun toPx(percentBase: Int, density: Float): Float = when (this) {
        is Percent -> (percentBase * value / 100.0).toFloat()
        is Dp -> (value * density).toFloat()
        is Px -> value.toFloat()
    }

    companion object {

        /**
         * 解析一个字面量。
         *
         * 支持区间写法 `"1-3"`（协议里表示范围内随机），此时取随机值 ——
         * 用于"点击位置每次略有不同"这类反检测场景。
         *
         * 解析不出来时退化成 0%：宁可点到左上角也不要崩，
         * 而且 0% 在界面上很容易看出来是没设置。
         */
        fun parse(text: String?): ZValue {
            val s = text?.trim().orEmpty()
            if (s.isEmpty()) return Percent("0%", 0.0)

            // 区间 "a-b"：随机取一个。
            // 注意负号："-5--1" 也是合法区间，所以只在中间位置切分。
            val dash = s.indexOf('-', startIndex = 1)
            if (dash > 0 && !s.endsWith("-")) {
                val lo = s.substring(0, dash).toDoubleOrNull()
                val hi = s.substring(dash + 1).toDoubleOrNull()
                if (lo != null && hi != null && hi >= lo) {
                    val picked = if (hi == lo) lo else lo + Random.nextDouble() * (hi - lo)
                    // 单位跟着整体走，取后半段的单位
                    return parseSingle("$picked")
                }
            }
            return parseSingle(s)
        }

        /** 不含区间的解析。 */
        private fun parseSingle(text: String): ZValue {
            val s = text.trim()
            return when {
                s.endsWith("dp") ->
                    Dp(s, s.dropLast(2).toDoubleOrNull() ?: 0.0)

                s.endsWith("%") ->
                    Percent(s, s.dropLast(1).toDoubleOrNull() ?: 0.0)

                // 以数字结尾 = 像素（协议规则）
                s.lastOrNull()?.isDigit() == true ->
                    Px(s, s.filter { it.isDigit() || it == '.' || it == '-' }.toDoubleOrNull() ?: 0.0)

                else -> Percent("0%", 0.0)
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
     * 换算成像素矩形。
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
        return intArrayOf(cl, ct, cr - cl, cb - ct)
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
