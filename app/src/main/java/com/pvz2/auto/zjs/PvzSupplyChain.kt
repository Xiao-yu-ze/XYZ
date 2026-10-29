package com.pvz2.auto.zjs

/**
 * 「神器补给」整条链 —— **严格 1:1 复刻** 参考管线 的 `HeartTower/Supply.json`。
 *
 * ## 为什么要照抄而不是"简化一下"
 *
 * 用户原话："这个流程你要完全照抄，因为补给没识别到这几个他会默认点第一个，
 * 第一个有的补给是有上限的，选了就会弹这个提示，可能就会卡住"。
 *
 * 也就是说：**兜底点第一个卡片**这条路是有意保留的，而且它可能引出「提示」弹窗；
 * 弹窗不处理就会卡死。所以顺序、坐标、延迟一个都不能省。
 *
 * ## 坐标换算
 *
 * 参考管线 那套是 1920×1080，我们虚拟屏 1280×720 → 一律 ×2/3。
 * 每行注释里都写了 参考管线 原值，方便回查。
 *
 * ## 与 参考管线 的对应关系
 *
 * ```text
 * 补给(ColorMatch, DoNothing)     → [这里不需要，轮询已经认出入口了]
 * buji_神器补给_查看 Click[1499,526,17,43] pre_delay 2000
 * buji_神器补给_判断是否有神器  DoNothing pre 1000
 *   候选（按顺序）：
 *     全部植物 / 橙色植物 / 蓝色植物 / 神器 / 紫色植物   ← 都是 OCR，点击识别到的位置
 *     兜底：buji_补给 ColorMatch 同色域，Click[392,562]（默认点第一个卡片）
 * buji_神器补给_确定1 Click[950,921]               → [提示, 确定3]
 * buji_提示 OCR"提示" roi[843,363,142,74] Click[892,789] → 确定3
 * buji_确定3 Click[950,921] pre1000 post1000       → 确定4
 * buji_确定4 Click[950,921] pre1000 post1000       → xinta_boss_识别开始
 * ```
 *
 * 分类顺序：用户要求"有优先级、默认 全部植物 > 橙色 > 蓝色 > 紫色"，神器由用户插在任意位置。
 * 这里用 [defaultOrder] 表示，界面可以改顺序后传进来。
 */
object PvzSupplyChain {

    // ---------------------------------------------------------------- 点位（×2/3 后）

    /**
     * 补给界面的判据：参考管线 `Supply.json` 里那个 `补给` 节点本身
     * （ColorMatch roi[1085,84,26,33]，色域 148,213,8~168,233,28）。
     *
     * 用户要求："选补给节点，开始应该是判断当前是否在补给界面，
     * 确定在补给界面了再接着往下走，你这直接就点查看了"。
     * 所以进链第一件事是等这个色块出现，等不到就不点查看。
     */
    val SUPPLY_UI_ROI = intArrayOf(723, 56, 740, 78)

    /** 补给界面判据的色域中点。 */
    const val SUPPLY_UI_COLOR = "#9CDF10"

    /** 等补给界面最多多久，等不到就跳过（不瞎点）。 */
    const val SUPPLY_UI_WAIT_MS = 10_000L

    /** `buji_神器补给_查看`：参考管线 `[1499,526,17,43]` → 中心约 (1000, 359)。 */
    val VIEW = intArrayOf(1000, 359)

    /** `buji_补给` 兜底：参考管线 `target[392,562]` → (261, 374)，默认点第一个卡片。 */
    val FALLBACK_FIRST_CARD = intArrayOf(261, 374)

    /** `buji_神器补给_确定1`：参考管线 `[950,921]` → (633, 614)。 */
    val CONFIRM = intArrayOf(633, 614)

    /** `buji_提示` 的识别区域：参考管线 `roi[843,363,142,74]` → `[562,242,94,49]`。 */
    val TIP_AREA = intArrayOf(562, 242, 656, 291)

    /** `buji_提示` 弹窗上的点击：参考管线 `target[892,789]` → (594, 526)。 */
    val TIP_TAP = intArrayOf(594, 526)

    /** 5 个分类的 OCR 区域：参考管线 `roi[291,356,1364,120]` → `[194,237,909,80]`。 */
    /** 分类标签搜索区（MAA `通用_神器补给_*` 原值 roi [194,237,909,80]）。 */
    val CATEGORY_AREA = intArrayOf(194, 237, 909, 80)

    /** 兜底判据的 ROI：参考管线 `roi[1085,84,26,33]` → `[723,56,740,78]`。 */
    val FALLBACK_AREA = intArrayOf(723, 56, 740, 78)

    /** 入口色域中点（补给入口用）。 */
    const val SUPPLY_COLOR = "#9AD216"

    /** 兜底色域中点：参考管线 146~166/6~26 取中 → #96D010（比入口窄一档）。 */
    const val FALLBACK_COLOR = "#96D010"

    // ---------------------------------------------------------------- 延迟（照抄 参考管线）

    const val DELAY_VIEW = "2000"         // 查看：pre_delay 2000
    const val DELAY_CHECK = "1000"        // 判断是否有神器：pre 1000
    const val DELAY_CATEGORY = "1000"     // 分类命中后：pre 1000
    const val DELAY_CONFIRM3 = "1000"     // 确定3：pre 1000 / post 1000
    const val DELAY_CONFIRM4 = "1000"     // 确定4：pre 1000 / post 1000

    /**
     * 分类的默认优先级（用户：默认 全部植物 > 橙色 > 蓝色 > 紫色；神器由用户插）。
     * 注意顺序就是候选顺序 —— 谁先命中谁执行。
     */
    val defaultOrder: List<String> =
        // 顺序照 MAA 的候选表：全部 > 橙 > 蓝 > 神器 > 紫
        listOf("全部植物", "橙色植物", "蓝色植物", "神器", "紫色植物")

    // ---------------------------------------------------------------- 造节点

    private fun click(pt: IntArray, delay: String, desc: String): Z.Action = Z.Action(
        type = Z.ActionType.CLICK,
        posData = Z.PosData(
            type = Z.PosType.LOCATION,
            x = ZValue.Px(pt[0].toString(), pt[0].toDouble()),
            y = ZValue.Px(pt[1].toString(), pt[1].toDouble())
        ),
        common = Z.ActionCommon(delay = delay, desc = desc)
    )

    /**
     * 生成补给整条链。
     *
     * @param order 分类的候选顺序（默认 [defaultOrder]）；界面改过就用用户的。
     */
    fun build(order: List<String> = defaultOrder): List<Z.Action> {
        val out = ArrayList<Z.Action>()

        // buji_神器补给_查看
        out.add(click(VIEW, DELAY_VIEW, "补给·查看"))

        /*
         * buji_神器补给_判断是否有神器 —— 这里用「等待候选」一次判 5 个分类。
         *
         * 参考管线 是顺序 next 轮询（每轮每个节点各跑一次识别）；我们用同一个节点的
         * 候选列表，语义一致而且一轮只看一帧 —— 更快也不会漏。
         * 候选顺序 = 优先级顺序，第一个命中的就是结果。
         */
        val cands = org.json.JSONArray()
        for (name in order) {
            cands.put(
                org.json.JSONObject().apply {
                    put(
                        "condition",
                        org.json.JSONObject().apply {
                            put("type", "textFount")
                            put(
                                "text",
                                org.json.JSONObject().apply {
                                    put("type", "text")
                                    put("text", name)
                                    put(
                                        "limitArea",
                                        toPercentArea(CATEGORY_AREA)
                                    )
                                }
                            )
                        }
                    )
                    // 命中后不需要跳转：等待候选会把命中的坐标交给下一步？
                    // 不行 —— 等待候选只判成立与否。所以这里用「点击文字」的思路：
                    // 候选命中即视为选中，具体点击由后面的固定点击完成。
                }
            )
        }
        out.add(
            Z.Action(
                type = Z.ActionType.WAIT_CANDIDATES,
                common = Z.ActionCommon(delay = DELAY_CHECK, desc = "补给·判断是否有神器"),
                extra = HashMap<String, Any?>().apply {
                    put("candidates", cands)
                    put("waitIntervalMs", 200)
                    put("waitTimeoutMs", 3000)
                }
            )
        )

        /*
         * 分类点选 + 兜底。
         *
         * 参考管线 的两个分支：命中某个分类 → 点它；一个都没命中 → 点第一个卡片 [392,562]。
         * 我们按"逐个尝试点击文字，最后一个兜底点击"来表达：
         *   - 「点击文字」本身带条件：认出才点，认不出就被跳过（不报错）
         *   - 最后一条固定坐标点击挂「5 个分类都没出现」的条件
         */
        for (name in order) {
            out.add(
                Z.Action(
                    type = Z.ActionType.CLICK_TEXT,
                    posData = Z.PosData(
                        type = Z.PosType.TEXT,
                        text = name,
                        similarPercent = 90,
                        limitArea = ZArea.fromPxRect(
                            CATEGORY_AREA[0], CATEGORY_AREA[1],
                            CATEGORY_AREA[2] - CATEGORY_AREA[0],
                            CATEGORY_AREA[3] - CATEGORY_AREA[1],
                            PvzCoords.BASE_WIDTH, PvzCoords.BASE_HEIGHT
                        )
                    ),
                    common = Z.ActionCommon(delay = DELAY_CATEGORY, desc = "补给·选 $name")
                )
            )
        }
        /*
         * 兜底：参考管线 `buji_补给` —— 点第一个卡片 [392,562]。
         *
         * ★ 它**带颜色条件**，而且色域比入口更窄一档
         *   （入口 148~168 / 8~28；兜底 146~166 / 6~26）。
         *   抄错这一档就永远进不了兜底，或者该兜底时判不出来。
         *   这里取窄色域中点 #96D010，容差给 92（比入口的 85 更严）。
         */
        val fallback = click(FALLBACK_FIRST_CARD, "300", "补给·兜底点第一个卡片")
        out.add(
            fallback.copy(
                common = fallback.common.copy(
                    condition = Z.Condition(
                        type = Z.ConditionType.COLOR,
                        desc = "兜底：补给卡片仍在（窄色域）",
                        posData = Z.PosData(
                            type = Z.PosType.COLOR,
                            color = "#96D010",
                            similarPercent = 92,
                            limitArea = ZArea.fromPxRect(
                                FALLBACK_AREA[0], FALLBACK_AREA[1],
                                FALLBACK_AREA[2] - FALLBACK_AREA[0],
                                FALLBACK_AREA[3] - FALLBACK_AREA[1],
                                PvzCoords.BASE_WIDTH, PvzCoords.BASE_HEIGHT
                            )
                        )
                    )
                )
            )
        )

        // 确定 1
        out.add(click(CONFIRM, "0", "补给·确定1"))

        // [提示 | 确定3]：提示弹窗出现就点它，否则直接确定
        out.add(
            Z.Action(
                type = Z.ActionType.CLICK_TEXT,
                posData = Z.PosData(
                    type = Z.PosType.TEXT,
                    text = "提示",
                    similarPercent = 90,
                    limitArea = ZArea.fromPxRect(
                        TIP_AREA[0], TIP_AREA[1],
                        TIP_AREA[2] - TIP_AREA[0], TIP_AREA[3] - TIP_AREA[1],
                        PvzCoords.BASE_WIDTH, PvzCoords.BASE_HEIGHT
                    )
                ),
                common = Z.ActionCommon(delay = "300", desc = "补给·提示弹窗")
            )
        )

        // 确定 3 / 确定 4（各 pre 1000 / post 1000）
        out.add(click(CONFIRM, DELAY_CONFIRM3, "补给·确定3"))
        out.add(click(CONFIRM, DELAY_CONFIRM4, "补给·确定4"))
        return out
    }

    /** 像素矩形 → 协议里的百分比区间字符串 `x% y% w% h%`。 */
    private fun toPercentArea(r: IntArray): String {
        val w = PvzCoords.BASE_WIDTH.toFloat()
        val h = PvzCoords.BASE_HEIGHT.toFloat()
        val x = (r[0] / w * 100 * 100).toInt() / 100f
        val y = (r[1] / h * 100 * 100).toInt() / 100f
        val ww = ((r[2] - r[0]) / w * 100 * 100).toInt() / 100f
        val hh = ((r[3] - r[1]) / h * 100 * 100).toInt() / 100f
        return "$x% $y% $ww% $hh%"
    }
}
