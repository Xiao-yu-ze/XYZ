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

    /**
     * 换算后的一次命中。
     *
     * [score] 是这个命中点的相似度/相关度（图片是 matchTemplate 的相关系数，
     * 文字是文字相似度）。**默认 0.0 是有意的**：
     *
     *  - `ZRealMatcher` 和测试里的假 matcher 都是 `Hit(x, y)` 两个参数构造的，
     *    加一个带默认值的新字段不会打断它们编译；
     *  - 协议里 `indexNum` 缺省的含义是 **mostMatched（相似度最高）**，不是"第一个"。
     *    `ZExecutor.locate` 用 `maxByOrNull { it.score }` 实现；全 0 时取第一个，
     *    恰好等于改造前的行为 —— 对方还没开始传分数时不会突然变怪。
     */
    data class Hit(val x: Int, val y: Int, val score: Double = 0.0)

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
     * 多命中时选**哪几个**（FIELDS.md:146 的 `indexNum`）。
     *
     * ## 返回值是"多个"，不是一个
     *
     * 协议原实现（m18129）对 `"1,3,4"` 是**把这几个下标的结果都 push 进数组**返回
     * （FIELDS.md:151-154），由调用方逐个处理。我们原来是 `candidates.randomOrNull()`：
     * 随机挑一个 —— 于是"关 3 个弹窗"的动作每轮只关 1 个，剩下 2 个永远漏，
     * 而脚本看起来"跑完了"。这是漏点里最难查的一类。
     *
     * | 写法 | 语义 | 返回 |
     * |---|---|---|
     * | 空 / 缺省 | 交给调用方（`locate` 用相似度最高） | `[0]` |
     * | `"2"` | 第 2 个（1 基） | `[1]` |
     * | `"-1"` | 倒数第一个 | `[count-1]` |
     * | `"0"` | 随机 | 随机 1 个 |
     * | `"1-3"` | 区间内随机（**一个**，不是三个） | 随机 1 个 |
     * | `"1,3,4"` | 这几个**都处理** | `[0,2,3]` |
     */
    fun pickIndices(indexNum: String?, hitCount: Int): List<Int> {
        if (hitCount <= 0) return emptyList()
        val spec = indexNum?.trim().orEmpty()
        // 缺省：`locate` 自己会用"相似度最高"，这里给 0 只是为了保持"至少有一个"的旧语义。
        if (spec.isEmpty()) return listOf(0)

        // 1 基 / 负数 → 0 基。0 表示"随机"，交给调用方单独处理（返回 null）。
        fun toIndex(token: Int): Int? = when {
            token == 0 -> null
            token > 0 -> token - 1
            else -> hitCount + token
        }

        // ---- 逗号列表："1,3,4" → 这几个都点
        //
        // ★ 越界**丢弃**而不是夹住：多点语义下把"第 7 个"夹成"第 3 个"，
        //   等于同一个点被点两次 —— 比少点一次更难查（用户看到的是"点重了"）。
        if (spec.contains(',')) {
            // LinkedHashSet：保序 + 去重。用户写的顺序就是点击顺序，不能排序。
            val out = LinkedHashSet<Int>()
            for (raw in spec.split(',')) {
                val token = raw.trim().toIntOrNull() ?: continue
                if (token == 0) {
                    out.add((0 until hitCount).random())
                    continue
                }
                toIndex(token)?.takeIf { it in 0 until hitCount }?.let { out.add(it) }
            }
            return out.toList()
        }

        // ---- 区间："1-3" → 范围内随机一个（协议 §0.4，不是"三个都点"）
        if (spec.contains('-') && !spec.startsWith("-")) {
            val parts = spec.split('-', limit = 2)
            val lo = parts.getOrNull(0)?.trim()?.toIntOrNull()
            val hi = parts.getOrNull(1)?.trim()?.toIntOrNull()
            if (lo != null && hi != null) {
                val a = (lo - 1).coerceAtLeast(0)
                val b = (hi - 1).coerceAtMost(hitCount - 1)
                if (a <= b) return listOf((a..b).random())
            }
            return listOf(0)
        }

        // ---- 单值
        val n = spec.toIntOrNull() ?: return listOf(0)
        if (n == 0) return listOf((0 until hitCount).random())
        // ★ 单值越界**夹住**（保持旧行为，与逗号列表相反）：用户只写了一个数，
        //   夹住至少还会点一下；丢弃会变成"这条不执行"，而用户从画面上分不清
        //   是笔误还是脚本根本没跑到这里。
        return listOf((toIndex(n) ?: 0).coerceIn(0, hitCount - 1))
    }

    /**
     * 单个下标（旧接口）。
     *
     * 多点写法（`"1,3,4"`）下只取第一个 —— 仅为还没改成多点的调用方保持旧行为。
     * 新代码请用 [pickIndices]。
     */
    fun pickIndex(indexNum: String?, hitCount: Int): Int =
        pickIndices(indexNum, hitCount).firstOrNull() ?: 0

    /**
     * 颜色位置要判定的**单点**（屏幕像素）；null 表示"没有单点，按 ROI 扫描"。
     *
     * ## 为什么需要它
     *
     * 协议里 color 位置有两种"只看一个点"的写法（FIELDS.md:210-214）：
     *
     *  - `limitPosX` / `limitPosY`：界面叫「指定位置」，**填了就忽略 limitArea**；
     *  - `x` / `y`：取色点，就是用户吸色的那个像素。
     *
     * 而 `ZRealMatcher.findColor` 只看 `rect`（ROI）—— 于是"这一点上是不是这个颜色"
     * 被放大成"这一大片里有没有这个颜色"。判定静默变松：指定点早就不是那个颜色了，
     * ROI 里随便哪个角落留着旧颜色，条件照样成立。
     *
     * ★ 这一层只负责"算点"，判定在 matcher 里 —— 通过本函数解耦，
     *   两边各自改、不用同时改一个文件（并发改同一文件会互相覆盖）。
     *
     * @param width/height 判定用的画面尺寸（= 帧尺寸）
     * @param density dp 换算用（虚拟屏是 dpi/160 = 1.5，别传 App 的 displayMetrics.density）
     */
    fun colorProbePoint(
        pos: Z.PosData,
        width: Int,
        height: Int,
        density: Float
    ): Pair<Int, Int>? {
        // ①「指定位置」优先：两个都填了才算（只填一个多半是半途编辑，退回 ROI 更安全）
        val lx = pos.extra["limitPosX"]
        val ly = pos.extra["limitPosY"]
        if (lx != null && ly != null) {
            return clampPoint(
                ZValue.parse(lx.toString()).toPx(width, density),
                ZValue.parse(ly.toString()).toPx(height, density),
                width, height
            )
        }

        // ② 其次：取色点 x/y
        val px = pos.x
        val py = pos.y
        if (px != null && py != null) {
            return clampPoint(
                px.toPx(width, density),
                py.toPx(height, density),
                width, height
            )
        }

        // ③ 都没配 → 没有单点可言，调用方按 ROI 扫（原来的行为）
        return null
    }

    /** [colorProbePoint] 的 Screen 版本，方便已经有 Screen 的调用方。 */
    fun colorProbePoint(pos: Z.PosData, screen: Screen): Pair<Int, Int>? =
        colorProbePoint(pos, screen.width, screen.height, screen.density)

    private fun clampPoint(x: Float, y: Float, width: Int, height: Int): Pair<Int, Int> =
        x.toInt().coerceIn(0, (width - 1).coerceAtLeast(0)) to
            y.toInt().coerceIn(0, (height - 1).coerceAtLeast(0))

    /**
     * 把 `text` 解析成正则；null 表示"不是正则写法，按包含匹配"。
     *
     * ## 协议里的正则写法
     *
     * `text` 在变量模式下可以是一个正则对象（FIELDS.md:199）：
     *
     * ```json
     * {"type":"RegExp","source":"第\\d+关","flags":""}
     * ```
     *
     * 我们读进来时 `PosData.text` 是 `String`，这个对象会被 `optString` 摊成
     * 它自己的 JSON 文本 —— 所以运行时能在这里认出来（首字符 `{` + `type=="RegExp"`）。
     *
     * ★ 为什么放在这一层：正则构造（flags 映射、非法正则兜底）和"位置对象"同源，
     *   matcher 只要 `textRegex(pos) ?: 包含匹配`。与 [colorProbePoint] 同理，
     *   是为了两边解耦，免得两个人同时改 `ZRealMatcher` 互相覆盖。
     */
    fun textRegex(pos: Z.PosData): Regex? {
        val raw = pos.text?.trim().orEmpty()
        if (!raw.startsWith("{")) return null
        val obj = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: return null
        if (obj.optString("type") != "RegExp") return null
        val source = obj.optString("source")
        if (source.isEmpty()) return null

        val flags = obj.optString("flags")
        val options = mutableSetOf<RegexOption>()
        if ('i' in flags) options.add(RegexOption.IGNORE_CASE)
        if ('m' in flags) options.add(RegexOption.MULTILINE)
        if ('s' in flags) options.add(RegexOption.DOT_MATCHES_ALL)
        // 非法正则（用户在自动精灵里写错了）→ 当"不是正则"，退回包含匹配；
        // 不能让一条写错的正则把整条动作炸掉。
        return runCatching { Regex(source, options) }.getOrNull()
    }
}
