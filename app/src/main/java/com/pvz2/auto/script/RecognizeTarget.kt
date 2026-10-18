package com.pvz2.auto.script

import android.content.Context
import com.pvz2.auto.maa.Recognition
import org.json.JSONObject

/**
 * 内置识别目标库。
 *
 * ## 为什么要有这层
 *
 * 脚本里不该出现「模板路径 + 阈值 + ROI」这些细节 —— 写脚本的人只想知道
 * 「这关打完了没有」，不想知道结算画面在哪张 png 里、阈值填多少合适。
 *
 * 所以把「长什么样」和「怎么用」分开：
 *  - **本文件 + assets/recognize/targets.json** 负责「长什么样」
 *  - 脚本只写一个 `id`，这里负责翻译成 [Recognition]
 *
 * 好处是加识别目标不需要改 Kotlin：丢一张图、在 json 里加一条就行。
 *
 * ## 坐标约定
 *
 * ROI 是 **720p 逻辑坐标** 的 `[x, y, w, h]`，和 MaaPVZ 的数据、
 * 和 [com.pvz2.auto.maa.Recognizer] 的期望一致。
 * 之所以能固定用 720p，是因为走虚拟屏时画面就是 1280×720；
 * 走物理屏时 [com.pvz2.auto.core.ScreenAdapter] 会做换算。
 */

/** 识别方式。只做脚本实际用得上的两种，颜色/组合以后按需加。 */
enum class TargetType {
    /** 模板匹配。 */
    TEMPLATE,

    /** OCR 文字识别，`expect` 里是正则。 */
    OCR,
}

/**
 * 一条内置识别目标。
 *
 * `roi` 用 [List]<Int> 而不是 IntArray —— data class 的自动 equals/hashCode
 * 对数组是按引用比较的，两个内容相同的 IntArray 会被判为不相等。
 * 用 List 可以避开这个坑，也方便在日志里直接打印。
 */
data class RecognizeTarget(
    /** 脚本里引用的稳定 id，建议英文小写下划线。 */
    val id: String,
    /** 给人看的中文名。 */
    val name: String,
    val type: TargetType,
    /** 模板图路径，相对 `assets/`；OCR 类型时为 null。 */
    val image: String? = null,
    /** 搜索区域 `[x, y, w, h]`，720p 逻辑坐标；null = 全屏。 */
    val roi: List<Int>? = null,
    /** 命中阈值。模板匹配是相似度，OCR 是置信度。 */
    val threshold: Double = 0.8,
    /** OCR 期望文字，每项都是正则。 */
    val expect: List<String> = emptyList(),
) {
    /** 翻译成识别层认识的形式。 */
    fun toRecognition(): Recognition = when (type) {
        TargetType.TEMPLATE -> Recognition.Template(
            // image 在解析阶段已校验过非空，这里 listOfNotNull 只是防御
            templates = listOfNotNull(image),
            roi = roi?.toIntArray(),
            threshold = threshold,
        )

        TargetType.OCR -> Recognition.Ocr(
            expected = expect,
            roi = roi?.toIntArray(),
            threshold = threshold,
        )
    }
}

/**
 * `assets/recognize/targets.json` 的读取器。
 *
 * 解析策略是 **宁可炸也不静默跳过**：这个文件是给人手改的，一条写错就整份
 * 报错并且把「哪一条、哪个字段、错在哪」列清楚，比悄悄少一个目标好得多 ——
 * 静默跳过会表现为「脚本跑到那里就不动了」，极难查。
 */
class TargetCatalog private constructor(
    private val byId: Map<String, RecognizeTarget>,
) {
    /** 按 id 查。找不到返回 null，调用方负责报错。 */
    operator fun get(id: String): RecognizeTarget? = byId[id]

    /** 全部目标，按 json 里的顺序。 */
    fun all(): List<RecognizeTarget> = byId.values.toList()

    val size: Int get() = byId.size

    companion object {
        private const val ASSET_PATH = "recognize/targets.json"

        /** 从 assets 读。会做一次完整校验，格式不对直接抛 [IllegalStateException]。 */
        fun load(context: Context): TargetCatalog {
            val text = context.assets.open(ASSET_PATH)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            return parse(text)
        }

        /** 纯解析，不碰 Android，方便单测。 */
        fun parse(text: String): TargetCatalog {
            val root = JSONObject(text)
            val array = root.optJSONArray("targets")
                ?: error("$ASSET_PATH: 缺少 targets 数组")

            val problems = mutableListOf<String>()
            val result = LinkedHashMap<String, RecognizeTarget>()

            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i)
                if (o == null) {
                    problems += "第 $i 项不是对象"
                    continue
                }
                val where = "第 $i 项(id=${o.optString("id", "?")})"

                val id = o.optString("id", "").trim()
                if (id.isEmpty()) {
                    problems += "$where: id 不能为空"
                    continue
                }
                if (result.containsKey(id)) {
                    problems += "$where: id 重复"
                    continue
                }

                val typeRaw = o.optString("type", "").trim().uppercase()
                val type = TargetType.entries.firstOrNull { it.name == typeRaw }
                if (type == null) {
                    problems += "$where: type=$typeRaw 不认识（可选 " +
                        TargetType.entries.joinToString("/") + "）"
                    continue
                }

                val image = o.optString("image", "").trim().ifEmpty { null }
                if (type == TargetType.TEMPLATE && image == null) {
                    problems += "$where: TEMPLATE 类型必须填 image"
                    continue
                }

                val roi = o.optJSONArray("roi")?.let { a ->
                    if (a.length() != 4) {
                        problems += "$where: roi 必须是 [x, y, w, h] 四个数"
                        null
                    } else {
                        List(4) { a.optInt(it, Int.MIN_VALUE) }.also { list ->
                            if (list.any { it == Int.MIN_VALUE }) {
                                problems += "$where: roi 里有非整数"
                            }
                        }
                    }
                }
                if (problems.any { it.startsWith(where) }) continue

                val threshold = if (o.has("threshold")) {
                    o.optDouble("threshold", -1.0).also {
                        if (it !in 0.0..1.0) problems += "$where: threshold 必须在 0~1 之间"
                    }
                } else {
                    0.8
                }
                if (problems.any { it.startsWith(where) }) continue

                val expect = o.optJSONArray("expect")?.let { a ->
                    List(a.length()) { a.optString(it, "") }.filter { it.isNotEmpty() }
                } ?: emptyList()
                if (type == TargetType.OCR && expect.isEmpty()) {
                    problems += "$where: OCR 类型必须填 expect（正则数组）"
                    continue
                }

                result[id] = RecognizeTarget(
                    id = id,
                    name = o.optString("name", id),
                    type = type,
                    image = image,
                    roi = roi,
                    threshold = threshold,
                    expect = expect,
                )
            }

            if (problems.isNotEmpty()) {
                error("$ASSET_PATH 有 ${problems.size} 处问题：\n" +
                    problems.joinToString("\n") { "  · $it" })
            }
            return TargetCatalog(result)
        }
    }
}
