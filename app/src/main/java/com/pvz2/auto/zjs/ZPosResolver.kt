package com.pvz2.auto.zjs

/**
 * 把协议里的位置对象换算成**虚拟屏像素坐标**。
 *
 * ## 为什么单独一层
 *
 * 位置对象的坐标是 `Value`：可以是 `"50%"`、`"200dp"`、`"640"`（像素）。
 * 执行器只关心"点哪里"，不该每次都自己判三种单位。
 *
 * ## `limitArea` 的语义
 *
 * `limitArea` 是**搜索范围**（FIELDS.md:0 的 `limitArea` = left top right bottom），
 * 不是点击区域。对坐标类位置它没有意义；对图片/文字类它缩小匹配范围，
 * 既提速又避免误匹配。
 */
object ZPosResolver {

    /** 换算后的一次命中。 */
    data class Hit(val x: Int, val y: Int)

    /** 屏幕参数，一次算好反复用。 */
    data class Screen(val width: Int, val height: Int, val density: Float)

    /**
     * 坐标类位置 → 像素点。
     *
     * x 用屏幕宽做百分比基准，y 用高 —— 传反了会让坐标整体偏移。
     */
    fun resolveLocation(pos: Z.PosData, screen: Screen): Hit {
        val x = pos.x?.toPx(screen.width, screen.density) ?: (screen.width / 2f)
        val y = pos.y?.toPx(screen.height, screen.density) ?: (screen.height / 2f)

        // 命中后的偏移（FIELDS.md:144-145）
        val ox = pos.xOffset?.toPx(screen.width, screen.density) ?: 0f
        val oy = pos.yOffset?.toPx(screen.height, screen.density) ?: 0f

        return Hit(
            x = (x + ox).toInt().coerceIn(0, screen.width - 1),
            y = (y + oy).toInt().coerceIn(0, screen.height - 1)
        )
    }

    /**
     * 位置对象的**搜索区域**（像素矩形 `[left, top, right, bottom]`）。
     *
     * 没设 `limitArea` 时返回整屏 —— 调用方可以无条件当矩形用。
     */
    fun searchRect(pos: Z.PosData, screen: Screen): IntArray {
        val area = pos.limitArea
            ?: return intArrayOf(0, 0, screen.width, screen.height)
        return area.toPxRect(screen.width, screen.height, screen.density)
    }

    /**
     * 多命中时选第几个（FIELDS.md:146 的 `indexNum`）。
     *
     * - `null` / 空 → 第一个
     * - `"2"` → 第 2 个（1 基，和界面上"选第几个"一致）
     * - `"-1"` → 倒数第一个
     * - `"0"` → 随机
     * - `"1-3"` → 区间内随机
     * - `"1,3,4"` → 从这几个里随机取一个已命中的
     *
     * 越界时退回第一个 —— 宁可点到第一个也不要什么都不做，
     * 后者在用户看来就是"点没生效"。
     */
    fun pickIndex(indexNum: String?, hitCount: Int): Int {
        if (hitCount <= 0) return -1
        val spec = indexNum?.trim().orEmpty()
        if (spec.isEmpty()) return 0

        // 逗号列表：从列举的位置里挑一个存在的
        if (spec.contains(',')) {
            val candidates = spec.split(',').mapNotNull { it.trim().toIntOrNull() }
                .map { if (it > 0) it - 1 else hitCount + it }
                .filter { it in 0 until hitCount }
            return candidates.randomOrNull() ?: 0
        }

        // 区间：范围内的随机
        if (spec.contains('-') && !spec.startsWith("-")) {
            val parts = spec.split('-', limit = 2)
            val lo = parts.getOrNull(0)?.trim()?.toIntOrNull()
            val hi = parts.getOrNull(1)?.trim()?.toIntOrNull()
            if (lo != null && hi != null) {
                val a = (lo - 1).coerceAtLeast(0)
                val b = (hi - 1).coerceAtMost(hitCount - 1)
                if (a <= b) return (a..b).random()
            }
            return 0
        }

        val n = spec.toIntOrNull() ?: return 0
        return when {
            n == 0 -> (0 until hitCount).random()
            n > 0 -> (n - 1).coerceIn(0, hitCount - 1)
            else -> (hitCount + n).coerceIn(0, hitCount - 1)
        }
    }
}
