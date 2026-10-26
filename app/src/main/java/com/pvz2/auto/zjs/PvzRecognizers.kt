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

    /*
     * ============ 用户已确认的参数（2026-10-26）============
     *
     * 1. 每个操作节点 delay=100ms；识别间隔/超时也是 100ms —— 进关判定必须快，
     *    boss 关喂豆打断靠它抢时间（用户："延迟越快越好，50~200ms 之间"）。
     * 2. 进关判定：**普通关与 boss 关相同** ROI [700,1004,40,40]，
     *    色域 [148,213,8]~[168,233,28]。
     * 3. 开始战斗：三个玩法都用 `xinta/开始战斗.png`；**认不出再退到 OCR**
     *    （我们现在先 OCR，等模板图拷进来再改成"模板优先 + OCR 兜底"）。
     * 4. 结算：**只有「继续挑战」一种**，boss 关也一样。
     * 5. 补给分类：**不要硬编码坐标** —— 用「点击文字」直接点识别到的那个词
     *    （全部植物 / 橙色植物 / 蓝色植物 / 紫色植物 / 神器）。
     * 6. 编队切换：右下角出现「开始战斗」时才能切；6 个编队按钮坐标**待用户给**。
     * 7. boss 关 = 关卡 % 5 == 0，是补给之后进去的那一关；选卡同普通关，
     *    只是关内链不同 → 每个阶段两套链（普通 / boss）。
     *
     * 待用户补：boss 关进关 ROI 是否同普通关（已答相同）、编队按钮坐标、
     * 开始战斗模板图文件、（可选）补给界面的其它按钮。
     */

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

    /**
     * 「开始战斗」的**模板优先 + OCR 兜底**条件。
     *
     * 用户要求：MAA 用的是模板图 `xinta/开始战斗.png`，认不出再退 OCR。
     * 我们把 PNG 读成 base64 内嵌进条件（协议里图片位置就是内嵌 base64，
     * 运行时由 ZRealMatcher 落成临时文件再匹配），然后用 conditionSet 的 OR 组合：
     * 模板命中就用模板（对艺术字/缩放更稳），否则用文字。
     *
     * 返回 null = 读不到图（只留 OCR 那条，不至于整个条件失效）。
     */
    fun startBattleAny(context: android.content.Context): Z.Condition {
        val textCond = startBattle()
        val img = startBattleTemplate(context) ?: return textCond
        return Z.Condition(
            type = Z.ConditionType.CONDITION_SET,
            desc = "开始战斗（模板优先 / OCR 兜底）",
            extra = HashMap<String, Any?>().apply {
                put("matchMode", "any")
                put(
                    "conditions",
                    org.json.JSONArray().put(img.toJson()).put(textCond.toJson())
                )
            }
        )
    }

    /** 读 assets 里的「开始战斗.png」→ 内嵌 base64 的图片条件。 */
    fun startBattleTemplate(context: android.content.Context): Z.Condition? = runCatching {
        val bmp = context.assets.open("Endless/开始战斗.png").use {
            android.graphics.BitmapFactory.decodeStream(it)
        } ?: return null
        val bos = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos)
        val b64 = android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
        val r = scaleRoi(1590, 970, 301, 88)
        Z.Condition(
            type = Z.ConditionType.IMAGE,
            desc = "开始战斗（模板）",
            posData = Z.PosData(
                type = Z.PosType.IMAGE,
                imageData = Z.ImageData(
                    data = b64,
                    width = bmp.width,
                    height = bmp.height,
                    screenWidth = PvzCoords.BASE_WIDTH,
                    screenHeight = PvzCoords.BASE_HEIGHT
                ),
                limitArea = ZArea.fromPxRect(
                    r[0], r[1], r[2], r[3],
                    PvzCoords.BASE_WIDTH, PvzCoords.BASE_HEIGHT
                )
            )
        )
    }.getOrNull()

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
