package com.pvz2.auto.zjs

/**
 * PVZ2 局内的「槽位 / 格子」坐标表。
 *
 * ## 来源
 *
 * 抄自 MaaPVZ 的 `agent/assets/resource/coords.json`（1280×720 基准），
 * 那套点数是**实测标定**过的（用户确认可以直接用，不做校准页）：
 *  - 8 个卡槽点的 y 间距不规则（120 / 196 / 267 / 338 / 399 / 482 / 539 / 613），
 *    不是等距公式；
 *  - 45 个格子点大体是 `x = 469 + 91×(列-1)`、`y = 152 + 108×(行-1)`，
 *    但首行有 5 个手调过的异常点（格子5_1、7_1、8_1、9_1、9_2），
 *    所以**必须照表查，不能算**。
 *
 * ## 为什么单独一个文件
 *
 * 这些数字将来会随游戏版本/分辨率变。集中在一处，改的时候不用翻执行器；
 * 而且棋盘 UI 也要用它（画出格子上的点位）。
 */
object PvzCoords {

    /** 基准分辨率（我们虚拟屏就是 1280×720）。 */
    const val BASE_WIDTH = 1280
    const val BASE_HEIGHT = 720

    /** 1..8 号卡槽的点击点。 */
    private val SLOTS = arrayOf(
        intArrayOf(57, 120),
        intArrayOf(62, 196),
        intArrayOf(56, 267),
        intArrayOf(61, 338),
        intArrayOf(60, 399),
        intArrayOf(61, 482),
        intArrayOf(64, 539),
        intArrayOf(57, 613)
    )

    /** 能量豆（喂豆的起点）。 */
    val ENERGY_BEAN = intArrayOf(480, 679)

    /** 铲子（铲除的起点）。 */
    val SHOVEL = intArrayOf(1222, 674)

    /**
     * 神器预设：**不同神器用法不同**，所以带上"怎么用"。
     *
     * - [Use.SWIPE]：从起点拖到目标格子（魔豆、葫芦）
     * - [Use.TAP]  ：点一下就生效（相机复制/粘贴）
     *
     * 点位取自 coords.json 的 `神器_初始化_*` 与 `葫芦*体型`。
     */
    enum class Use { SWIPE, TAP }

    class Artifact(
        val key: String,
        val label: String,
        val use: Use,
        val point: IntArray
    )

    val ARTIFACTS: List<Artifact> = listOf(
        // 拖拽类：起点不同（葫芦按体型算距离，所以分三档）
        Artifact("bean", "魔豆（拖到格子）", Use.SWIPE, intArrayOf(749, 703)),
        Artifact("gourd_s", "葫芦·小（选中即用）", Use.TAP, intArrayOf(664, 573)),
        Artifact("gourd_m", "葫芦·中（选中即用）", Use.TAP, intArrayOf(748, 577)),
        Artifact("gourd_l", "葫芦·大（选中即用）", Use.TAP, intArrayOf(830, 577)),
        // 点按类：点固定位置
        Artifact("camera_copy", "相机·复制（点一下）", Use.TAP, intArrayOf(707, 570)),
        Artifact("camera_paste", "相机·粘贴（点一下）", Use.TAP, intArrayOf(794, 570))
    )

    fun artifact(key: String?): Artifact? = ARTIFACTS.firstOrNull { it.key == key }

    // ---------------------------------------------------------------- 切换编队

    /** 编队最多 6 个（用户确认）。 */
    const val DECK_COUNT = 6

    /**
     * 编队号那一竖条（用户实测 ROI `9.84% 16.53% 13.75% 97.64%`，换算成 1280×720）。
     * 行号识别就扫这一条；点编队也用同一块。
     */
    val DECK_AREA = intArrayOf(126, 119, 176, 703)

    /**
     * 「点击编队」按钮：MAA 用户脚本里的 `59.61%, 54.86%`。
     *
     * 切换编队必须先点它进到**编队选择界面**，否则就会在植物界面里瞎滑
     * （用户实测："你都没有点到编队界面里面，你在植物界面划"）。
     */
    val DECK_ENTRY = intArrayOf(763, 394)

    /** 「开始战斗」文字的识别区：`82.11% 90.69% 100.00% 98.47%` → `[1051,652,1280,708]`。 */
    val START_BATTLE_AREA = intArrayOf(1051, 652, 1280, 708)

    /** 编队列表的滑动：从下往上翻（用户脚本 `15.08%,86.67% → 15.31%,31.39%`）。 */
    val DECK_SWIPE_UP = intArrayOf(193, 624, 196, 226)

    /** 单次滑动时长（用户脚本 duration=1500）。 */
    const val DECK_SWIPE_MS = 1500

    /** 连续滑这么多次还没认出目标，就反向滑（用户："划个5、6次就往反方向划"）。 */
    const val DECK_REVERSE_AFTER = 6

        /** 点开神器图标后，等神器选择器弹出来的时间。 */
    const val ARTIFACT_PICK_GAP_MS = 350L

    /** 「下一波」按钮。 */
    val NEXT_WAVE = intArrayOf(1225, 219)

    /** 「加速」按钮。 */
    val SPEED = intArrayOf(1171, 35)

    /** 神器图标（拖拽类神器的起点）。 */
    val ARTIFACT = intArrayOf(749, 703)

    /**
     * 格子点：`CELLS[行-1][列-1]`。
     *
     * 行 1..5、列 1..9。首行那几个异常点是原表手调的，别"修正"成等距。
     */
    private val CELLS: Array<Array<IntArray>> = arrayOf(
        // 行 1
        arrayOf(
            intArrayOf(469, 152), intArrayOf(560, 152), intArrayOf(651, 152),
            intArrayOf(742, 152), intArrayOf(856, 120), intArrayOf(924, 152),
            intArrayOf(1013, 183), intArrayOf(1108, 182), intArrayOf(1193, 156)
        ),
        // 行 2
        arrayOf(
            intArrayOf(469, 260), intArrayOf(560, 260), intArrayOf(651, 260),
            intArrayOf(742, 260), intArrayOf(833, 260), intArrayOf(924, 260),
            intArrayOf(1015, 260), intArrayOf(1106, 260), intArrayOf(1188, 275)
        ),
        // 行 3
        arrayOf(
            intArrayOf(469, 368), intArrayOf(560, 368), intArrayOf(651, 368),
            intArrayOf(742, 368), intArrayOf(833, 368), intArrayOf(924, 368),
            intArrayOf(1015, 368), intArrayOf(1106, 368), intArrayOf(1197, 368)
        ),
        // 行 4
        arrayOf(
            intArrayOf(469, 476), intArrayOf(560, 476), intArrayOf(651, 476),
            intArrayOf(742, 476), intArrayOf(833, 476), intArrayOf(924, 476),
            intArrayOf(1015, 476), intArrayOf(1106, 476), intArrayOf(1197, 476)
        ),
        // 行 5
        arrayOf(
            intArrayOf(469, 584), intArrayOf(560, 584), intArrayOf(651, 584),
            intArrayOf(742, 584), intArrayOf(833, 584), intArrayOf(924, 584),
            intArrayOf(1015, 584), intArrayOf(1106, 584), intArrayOf(1197, 584)
        )
    )

    const val ROWS = 5
    const val COLS = 9

    /** 拖拽时长：原实现固定 80ms（太短游戏不认，太长会滑出格子）。 */
    const val SWIPE_MS = 80

    /** 卡槽点，[slot] 为 1..8；越界返回 null。 */
    fun slot(slot: Int): IntArray? = SLOTS.getOrNull(slot - 1)

    /** 格子点，行列都是 1-based；越界返回 null。 */
    fun cell(row: Int, col: Int): IntArray? =
        CELLS.getOrNull(row - 1)?.getOrNull(col - 1)

    /** 「3 行 4 列」这种给人看的写法。 */
    fun cellLabel(row: Int, col: Int): String = "第${row}行第${col}列"

    /**
     * 坐标是否落在基准分辨率内。
     *
     * 表是 1280×720 的；如果虚拟屏换了尺寸，点下去会偏 ——
     * 调用方应该据此告警，而不是闷着头点。
     */
    fun fitsBase(width: Int, height: Int): Boolean =
        width == BASE_WIDTH && height == BASE_HEIGHT
}
