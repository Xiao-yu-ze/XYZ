package com.pvz2.auto.zjs

/**
 * 预置「状态识别」—— 从 MAA pipeline 抄来的判据。
 *
 * ## 坐标换算（很重要）
 *
 * 那三个 pipeline（心之塔 / 香蕉塔 / YS）的 ROI 是 **1920×1080** 基准
 * （`[1590,970,301,88]` 这种明显超出 1280），而我们的虚拟屏是 **1280×720**。
 * 所以所有 ROI 一律乘 **2/3**，写死在这个文件里，别在调用处再算。
 *
 * ## 为什么只封装这三个
 *
 * 用户指定先做：**结算·继续挑战 / 开始战斗入口 / 补给·BOSS 入口**。
 * 这三个是"进关前 + 出关后"的判断，跨玩法通用；进关之后的识别不封装。
 */
object PvzRecognizers {

    /** MAA 那套 ROI 的基准宽度（我们的是 1280，比例 2/3）。 */
    private const val SRC_W = 1920
    private const val SRC_H = 1080

    /** 把一个 1920×1080 的 ROI 换算成我们的 1280×720。 */
    fun scaleRoi(x: Int, y: Int, w: Int, h: Int): IntArray {
        val fx = PvzCoords.BASE_WIDTH.toFloat() / SRC_W
        val fy = PvzCoords.BASE_HEIGHT.toFloat() / SRC_H
        return intArrayOf(
            (x * fx).toInt(),
            (y * fy).toInt(),
            (w * fx).toInt().coerceAtLeast(1),
            (h * fy).toInt().coerceAtLeast(1)
        )
    }

    /** 一个预置识别：名字 + 造一个条件。 */
    class Preset(
        val key: String,
        val label: String,
        val hint: String,
        val build: () -> Z.Condition
    )

    /**
     * 结算界面：OCR 认出「继续挑战」= 这一局打完了。
     *
     * 原判据：`OCR expected:"继续挑战" ROI[1074,931,268,75]`。
     */
    fun settleContinue(): Z.Condition = textCondition(
        text = "继续挑战",
        roi = scaleRoi(1074, 931, 268, 75),
        similar = 80
    )

    /**
     * 开始战斗入口：按钮上写着「开始战斗」。
     *
     * 原判据是模板图 `xinta/开始战斗.png`（ROI[1590,970,301,88]）。
     * 我们先用**文字**认，认不出再换成模板图 —— 文字版对分辨率更耐受。
     */
    fun startBattle(): Z.Condition = textCondition(
        text = "开始战斗",
        roi = scaleRoi(1590, 970, 301, 88),
        similar = 70
    )

    /**
     * 补给 / BOSS 入口：一块固定颜色的小图标。
     *
     * 原判据：`ColorMatch ROI[1085,84,26,33]`，色域
     * `[146,213,6]~[166,233,26]`，取中间值 #9AD216。
     * 颜色匹配比较的是占比，所以不用 ROI 也行；这里保留 ROI 缩小扫描面积。
     */
    fun supplyEntry(): Z.Condition = Z.Condition(
        type = Z.ConditionType.COLOR,
        desc = "补给/BOSS 入口",
        posData = Z.PosData(
            type = Z.PosType.COLOR,
            color = "#9AD216",
            similarPercent = 85
        ).let { pos ->
            val r = scaleRoi(1085, 84, 26, 33)
            pos.copy(
                limitArea = ZArea.fromPxRect(
                    r[0], r[1], r[2], r[3],
                    PvzCoords.BASE_WIDTH, PvzCoords.BASE_HEIGHT
                )
            )
        }
    )

    /** 三个预置（顺序即候选顺序：先判结算，再判入口）。 */
    val PRESETS: List<Preset> = listOf(
        Preset("settle", "结算·继续挑战", "认出「继续挑战」= 这一局结束", ::settleContinue),
        Preset("start", "开始战斗入口", "认出「开始战斗」= 可以点进关", ::startBattle),
        Preset("supply", "补给/BOSS 入口", "左上角那块颜色出现 = 进了补给/ boss 流程", ::supplyEntry)
    )

    private fun textCondition(text: String, roi: IntArray, similar: Int): Z.Condition =
        Z.Condition(
            type = Z.ConditionType.TEXT,
            desc = text,
            posData = Z.PosData(
                type = Z.PosType.TEXT,
                text = text,
                similarPercent = similar,
                limitArea = ZArea.fromPxRect(
                    roi[0], roi[1], roi[2], roi[3],
                    PvzCoords.BASE_WIDTH, PvzCoords.BASE_HEIGHT
                )
            )
        )
}
