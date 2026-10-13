package com.pvz2.auto.maa

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * 命名坐标表，对应 MaaPVZ 的 `agent/assets/resource/coords.json`。
 *
 * 81 个命名点，坐标全部是 **1280x720 基准**。这套数值和我们的
 * [com.pvz2.auto.core.ScreenAdapter] 基准完全一致，所以本层不需要做任何缩放 ——
 * 输出的是"逻辑坐标"，由上层统一换算到当前设备。
 *
 * 分辨率适配说明：
 *   当前实现是 720p 直连（scale=1.0）。后续如果接入"按设备分辨率推导坐标"的算法，
 *   只需替换 [point] 的内部实现，上层调用方无需改动。
 */
class CoordTable {

    private val points = HashMap<String, IntArray>()

    val size: Int get() = points.size

    val names: Set<String> get() = points.keys

    /** 从 assets 读取坐标表。解析失败时抛异常，避免后面用空表静默跑错。 */
    fun load(context: Context, assetPath: String = DEFAULT_ASSET) {
        points.clear()
        val text = context.assets.open(assetPath).bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        val keys = root.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val arr = root.optJSONArray(name) ?: continue
            if (arr.length() < 2) continue
            points[name] = intArrayOf(arr.getInt(0), arr.getInt(1))
        }
        Log.i(TAG, "已加载 $size 个坐标点")
    }

    /**
     * 取命名点。
     *
     * @throws IllegalArgumentException 名称不存在。刻意不返回 null ——
     *   坐标写错属于规则表配置错误，静默跳过会让脚本"看起来在跑但什么都不做"，
     *   这类问题极难排查，不如直接抛出并在批处理开始前统一校验。
     */
    fun point(name: String): IntArray =
        points[name] ?: throw IllegalArgumentException("坐标表中不存在：$name")

    fun x(name: String): Int = point(name)[0]

    fun y(name: String): Int = point(name)[1]

    fun contains(name: String): Boolean = points.containsKey(name)

    /**
     * 草坪格子坐标。9 列 x 5 行。
     *
     * 优先用坐标表里的显式值（`种植物_初始化_格子{c}_{r}`），
     * 缺失时按实测出的等距网格公式回退：
     *
     *     格子(c,r) = ( 469 + (c-1)*91,  152 + (r-1)*108 )
     *
     * 这个公式是对坐标表反解并验证过的（网格步长严格恒定 91/108）。
     * 之所以要回退而不是只查表：坐标表里有几个点明显是手工量偏的
     * （例如 格子5_1 记为 (856,120)，公式值 (833,152) 才对），
     * 公式在网格内部比手工采样更可靠。
     */
    fun grid(col: Int, row: Int): IntArray {
        require(col in 1..GRID_COLS) { "列号越界：$col（应为 1..$GRID_COLS）" }
        require(row in 1..GRID_ROWS) { "行号越界：$row（应为 1..$GRID_ROWS）" }

        val key = "种植物_初始化_格子${col}_${row}"
        points[key]?.let { return it }

        return intArrayOf(
            GRID_ORIGIN_X + (col - 1) * GRID_STEP_X,
            GRID_ORIGIN_Y + (row - 1) * GRID_STEP_Y
        )
    }

    fun gridX(col: Int, row: Int): Int = grid(col, row)[0]

    fun gridY(col: Int, row: Int): Int = grid(col, row)[1]

    /**
     * 批量校验一批名称是否都存在，并一次性报告全部缺失项。
     * 放在批量动作开始前调用，好过执行到一半才发现某个坐标点拼错了。
     */
    fun requireAll(names: Collection<String>): List<String> =
        names.filterNot { points.containsKey(it) }

    companion object {
        private const val TAG = "CoordTable"
        const val DEFAULT_ASSET = "coords.json"

        const val GRID_COLS = 9
        const val GRID_ROWS = 5

        // 以下常数由 coords.json 反解得出，见 grid() 的说明。
        const val GRID_ORIGIN_X = 469
        const val GRID_ORIGIN_Y = 152
        const val GRID_STEP_X = 91
        const val GRID_STEP_Y = 108
    }
}
