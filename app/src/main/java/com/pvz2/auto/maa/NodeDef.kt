package com.pvz2.auto.maa

import android.graphics.Rect
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.Mat

/**
 * 一个流水线节点。对应 MaaFramework Pipeline 协议里的一条 JSON 记录。
 *
 * 只保留无尽挑战流程实际用到的字段。官方协议里还有 `[JumpBack]` / `anchor` /
 * 加权随机 / 异步组等，但 MaaPVZ 的无尽挑战一个都没用到（逐文件统计确认），
 * 所以不实现 —— 这是"不做通用引擎"的取舍。
 */
data class NodeDef(
    val name: String,
    val recognition: Recognition = Recognition.DirectHit,
    val action: Action = Action.DoNothing,
    /** 后继列表，按优先级排列，第一个命中的生效。 */
    val next: List<String> = emptyList(),
    val onError: List<String> = emptyList(),
    /** 识别重试窗口，毫秒。官方默认 20000。 */
    val timeout: Long = 20_000L,
    /** 两轮识别之间的最小间隔，毫秒。官方默认 1000。 */
    /**
     * 每轮识别的最低耗时（毫秒）。不足则补睡。
     *
     * ## 为什么默认改成 16 而不是 Maa 的 1000
     *
     * 1000ms 意味着**每秒只识别一次** —— 对"Boss 放技能要立即打断"这个
     * 需求来说等于没有识别。16ms 与虚拟屏的采集帧率（约 60fps）对齐，
     * 既不浪费 CPU，也不会漏掉一两帧内的变化。
     *
     * 需要更激进的节点可以在 pipeline JSON 里显式写 `rate_limit: 0`。
     */
    val rateLimit: Long = DEFAULT_RATE_LIMIT_MS,
    /** 识别到动作之间的等待，毫秒。官方默认 200。 */
    val preDelay: Long = 200L,
    /** 动作到下一轮识别之间的等待，毫秒。官方默认 200。 */
    val postDelay: Long = 200L,
    /** 是否参与识别。false 时被 next 跳过（等价于从列表里移除）。 */
    val enabled: Boolean = true,
    /** 该节点最多被命中多少次。官方默认 UINT_MAX。 */
    val maxHit: Long = 4_294_967_295L,
    /** 状态栏提示文字，原样透出到 UI。 */
    val focus: String? = null,

    /**
     * 动作之后等画面**稳定**多久才算数，毫秒。0 = 不等。
     *
     * ## 为什么需要它（自动精灵协议里的 `post_wait_freezes`）
     *
     * `post_delay` 是死等固定时长：写 200 就睡 200。但动作引起的画面变化
     * 多久结束并不固定 —— 一次滑动可能 150ms 就停了，也可能因为惯性滚 800ms。
     * 死等要么白等，要么在画面还在动的时候就去做下一次识别，于是识别到
     * 中间态、点错位置。
     *
     * 这个字段的语义是"等到连续 [postWaitFreezes] 毫秒没有变化为止"，
     * 也就是自适应等待。真实脚本里滑动类节点用它（3 处）。
     */
    val postWaitFreezes: Long = 0L,

    /**
     * 识别未命中后，本轮重试前额外等待的毫秒数。0 = 立即重试。
     *
     * 协议里的 `repeat_delay`。与 [rateLimit] 的区别：
     * `rate_limit` 是每轮识别的**最低耗时下限**（补睡到这个时长），
     * `repeat_delay` 是**失败之后**的额外间隔，用于低频轮询的节点
     * （比如"等一个很久才出现的东西"，没必要每 16ms 全屏找一遍）。
     */
    val repeatDelay: Long = 0L,

    /**
     * 同一个识别在 ROI 里命中多个目标时，选哪个。协议里的 `order_by`。
     *
     * 取值（不区分大小写）：`Horizontal` / `Vertical` / `Score` / `Area` / `Random`。
     * null = 用识别器自己给的最优（模板取最高分，OCR 取第一行）。
     *
     * 真实场景：一个 ROI 里同时有「返回」和「确定」两个按钮，
     * 按 Vertical 取最上面那个，才不会点到下面的。
     */
    val orderBy: String? = null
)

/**
 * 节点之间的边。
 *
 * 自动精灵用 `[JumpBack]名字` 表示一条**回边**：那个节点命中并执行完之后，
 * 要回到父节点重新判断，而不是继续往下走。
 *
 * 抽成单独对象是因为解析和执行两处都要认这个前缀 ——
 * 写在两个地方一定会漂移（一处修了另一处忘）。
 */
object NodeEdges {

    const val JUMP_BACK_PREFIX = "[JumpBack]"

    /** 去掉前缀；没有前缀原样返回。 */
    fun strip(name: String): String =
        if (name.startsWith(JUMP_BACK_PREFIX)) name.removePrefix(JUMP_BACK_PREFIX) else name

    /** 这条边是不是回边。 */
    fun isJumpBack(name: String): Boolean = name.startsWith(JUMP_BACK_PREFIX)
}

/** 节点动作。 */
sealed interface Action {

    data object DoNothing : Action

    /**
     * 点击。
     * @param target null = 用命中框中心；`[x,y]` = 绝对点；`[x,y,w,h]` = 绝对矩形取中心
     * @param targetOffset 在命中框上叠加的偏移 `[x,y,w,h]`
     */
    data class Click(
        val target: IntArray? = null,
        val targetOffset: IntArray? = null
    ) : Action

    /**
     * 滑动。
     * @param end 支持多个终点（官方 `end` 是数组）；这里取第一个，多终点在无尽挑战里没用
     */
    data class Swipe(
        val begin: IntArray,
        val end: IntArray,
        val durationMs: Long = 200L,
        val endHoldMs: Long = 0L,
        val target: IntArray? = null
    ) : Action

    /** 自定义动作。名称对应 MaaPVZ 注册的 Python 动作名。 */
    data class Custom(
        val name: String,
        /** 原始参数字符串或 JSON 对象，原样交给动作实现解析。 */
        val param: String?
    ) : Action

    /** 停止任务。 */
    data class StopTask(val success: Boolean = true) : Action
}

/**
 * 流水线解析器。
 *
 * 兼容 MaaFramework 的两种写法（v1 扁平 / v2 嵌套）：
 *   v1: "recognition": "OCR", "expected": [...], "roi": [...]
 *   v2: "recognition": { "type": "OCR", "param": { "expected": [...], "roi": [...] } }
 * MaaPVZ 两种都用了，所以都要认。
 */
object PipelineParser {

    fun parse(json: String): Map<String, NodeDef> {
        val root = JSONObject(stripJsonComments(json))
        val result = LinkedHashMap<String, NodeDef>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            // 以 $ 开头的根字段是元数据，不解析（官方约定）。
            if (name.startsWith("$")) continue
            val obj = root.optJSONObject(name) ?: continue
            result[name] = parseNode(name, obj)
        }
        return result
    }

    /** 合并多个 JSON 文件（无尽挑战被拆成 14 个文件，后者可覆盖前者）。 */
    fun parseAll(jsons: List<String>): Map<String, NodeDef> {
        val merged = LinkedHashMap<String, NodeDef>()
        for (json in jsons) merged.putAll(parse(json))
        return merged
    }

    /**
     * 剥掉 `//` 行注释和 `/* */` 块注释。
     *
     * **这是必需的，不是可选优化**：MaaPVZ 的 pipeline JSON 里大量使用
     * `//` 注释写设计说明（`0300Endless_fight.json` 有 40 多行），
     * Android 的 `org.json.JSONObject` 是严格解析器，遇到注释直接抛
     * `JSONException`，整个文件作废 → 节点全部消失 → 流程静默跑不起来。
     *
     * 实现要点：必须**跳过字符串字面量内部**，否则值里出现的 `//`
     * （比如 URL `http://...`）会被误当注释起点，把后面的内容全吃掉。
     */
    internal fun stripJsonComments(raw: String): String {
        val out = StringBuilder(raw.length)
        var i = 0
        var inString = false
        var escaped = false

        while (i < raw.length) {
            val c = raw[i]

            if (inString) {
                out.append(c)
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                i++
                continue
            }

            when {
                c == '"' -> {
                    inString = true
                    out.append(c)
                    i++
                }

                // 行注释：吃到换行（换行保留，避免破坏行号）
                c == '/' && i + 1 < raw.length && raw[i + 1] == '/' -> {
                    while (i < raw.length && raw[i] != '\n') i++
                }

                // 块注释
                c == '/' && i + 1 < raw.length && raw[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < raw.length && !(raw[i] == '*' && raw[i + 1] == '/')) i++
                    i = (i + 2).coerceAtMost(raw.length)
                }

                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun parseNode(name: String, obj: JSONObject): NodeDef {
        return NodeDef(
            name = name,
            recognition = parseRecognition(obj),
            action = parseAction(obj),
            next = parseNameList(obj.opt("next")),
            onError = parseNameList(obj.opt("on_error")),
            timeout = obj.optLong("timeout", 20_000L),
            rateLimit = obj.optLong("rate_limit", DEFAULT_RATE_LIMIT_MS),
            preDelay = obj.optLong("pre_delay", 200L),
            postDelay = obj.optLong("post_delay", 200L),
            enabled = obj.optBoolean("enabled", true),
            maxHit = obj.optLong("max_hit", 4_294_967_295L),
            focus = if (obj.isNull("focus")) null else obj.optString("focus").takeIf { it.isNotEmpty() },
            // post_wait_freezes 在协议里可以是数字（毫秒），也可以是对象
            // （带 time/threshold）。这里只取数字形式，对象形式按 0 处理 ——
            // 真实脚本里 3 处全是数字。
            postWaitFreezes = obj.optLong("post_wait_freezes", 0L),
            repeatDelay = obj.optLong("repeat_delay", 0L),
            orderBy = obj.optString("order_by").takeIf { it.isNotEmpty() }
        )
    }

    // ------------------------------------------------------------ 识别解析

    private fun parseRecognition(obj: JSONObject): Recognition {
        val raw = obj.opt("recognition")

        // v2 嵌套写法：类型在 type，参数在 param
        if (raw is JSONObject) {
            val type = raw.optString("type").ifEmpty { return Recognition.DirectHit }
            val param = raw.optJSONObject("param") ?: JSONObject()
            return buildRecognition(type, param, obj)
        }

        // v1 扁平写法：类型是字符串，参数在节点本身
        val type = (raw as? String)?.takeIf { it.isNotEmpty() } ?: return Recognition.DirectHit
        return buildRecognition(type, obj, obj)
    }

    private fun buildRecognition(type: String, params: JSONObject, fallback: JSONObject): Recognition {
        val roi = params.optIntArray("roi") ?: fallback.optIntArray("roi")
        return when (type) {
            "TemplateMatch" -> Recognition.Template(
                templates = params.optStringList("template"),
                roi = roi,
                // threshold 在模板匹配里是数组，取第一个
                threshold = params.optDoubleArray("threshold")?.firstOrNull() ?: 0.7,
                greenMask = params.optBoolean("green_mask", false),
                method = params.optInt("method", 5)
            )

            "OCR" -> Recognition.Ocr(
                // expected 支持字符串或数组；**空数组表示「有任意文字就算命中」**
                // （MaaPVZ 用它做「这个区域里有没有字」的判断，例如无尽_切换编队序号）。
                // optStringList 对空数组返回空列表，语义正好对应，不需要额外处理。
                expected = params.optStringList("expected"),
                roi = roi,
                threshold = params.optDouble("threshold", 0.3)
            )

            "ColorMatch" -> Recognition.Color(
                roi = roi,
                method = params.optInt("method", 4),
                lower = params.optIntArray("lower") ?: intArrayOf(0, 0, 0),
                upper = params.optIntArray("upper") ?: intArrayOf(255, 255, 255),
                count = params.optInt("count", 1)
            )

            // any_of 里的每一项可能是 v2（type+param）也可能是 v1（扁平），
            // parseRecognition 两者都认，所以直接递归即可。
            "Or" -> Recognition.AnyOf(
                params.optJSONArray("any_of")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        val item = arr.optJSONObject(i) ?: return@mapNotNull null
                        parseRecognition(item)
                    }
                }.orEmpty()
            )

            "DirectHit" -> Recognition.DirectHit

            else -> {
                // ⚠️ 不要退化成 DirectHit：DirectHit 等价于「永远命中」，
                // 一个我们不支持的识别类型会立刻被当成命中，然后执行动作、
                // 跳到一个完全错误的节点 —— 这比「不命中」危险得多。
                // 用永假的识别（阈值 2.0 在 TM_CCOEFF_NORMED 下不可能达到）
                // 表达「这个节点我们跑不了」，行为上等于从不命中，并留下日志。
                Log.w("PipelineParser", "不支持的识别类型 [$type]，该节点将被视为永不命中")
                Recognition.Template(templates = emptyList(), roi = roi)
            }
        }
    }

    // ------------------------------------------------------------ 动作解析

    private fun parseAction(obj: JSONObject): Action {
        val raw = obj.opt("action")

        if (raw is JSONObject) {
            val type = raw.optString("type").ifEmpty { return Action.DoNothing }
            val param = raw.optJSONObject("param") ?: JSONObject()
            return buildAction(type, param, obj)
        }

        val type = (raw as? String)?.takeIf { it.isNotEmpty() } ?: return Action.DoNothing
        return buildAction(type, obj, obj)
    }

    private fun buildAction(type: String, params: JSONObject, fallback: JSONObject): Action =
        when (type) {
            "Click" -> Action.Click(
                target = params.optIntArray("target"),
                targetOffset = params.optIntArray("target_offset")
            )

            "Swipe" -> {
                val begin = params.optIntArray("begin")
                val end = params.optIntArray("end")
                if (begin == null || end == null) {
                    Action.DoNothing
                } else {
                    Action.Swipe(
                        begin = begin,
                        end = end,
                        durationMs = params.optLongArray("duration")?.firstOrNull() ?: 200L,
                        endHoldMs = params.optLongArray("end_hold")?.firstOrNull() ?: 0L,
                        target = params.optIntArray("target")
                    )
                }
            }

            "Custom" -> Action.Custom(
                name = params.optString("custom_action"),
                // param 可能是对象 / JSON 字符串 / 原始 DSL 字符串 / 空
                param = params.opt("custom_action_param")?.let { v ->
                    if (v == JSONObject.NULL) null else if (v is String) v else v.toString()
                }
            )

            "StopTask" -> Action.StopTask()

            else -> Action.DoNothing
        }

    // ------------------------------------------------------------ 小工具

    /** next / on_error 支持字符串或数组；`[JumpBack]` 前缀在这里剥掉。 */
    private fun parseNameList(value: Any?): List<String> = when (value) {
        null -> emptyList()
        is String -> listOf(stripPrefix(value))
        is JSONArray -> (0 until value.length()).mapNotNull { i ->
            value.optString(i).takeIf { it.isNotEmpty() }?.let(::stripPrefix)
        }
        else -> emptyList()
    }

    /**
     * 剥掉 `[Anchor]xxx` 之类的前缀，**但保留 `[JumpBack]`**。
     *
     * ## 为什么 `[JumpBack]` 必须保留
     *
     * 这里原本把所有 `[...]` 前缀一律删掉，注释写着"这两种语义都不实现，
     * 退化成普通后继对当前流程是等价的"。**这句话是错的**：
     *
     * `[JumpBack]X` 的语义是"X 命中并执行完后回到父节点重新判断"，
     * 而普通后继是"X 执行完后前进到 X 的 next"。两者走的分支完全不同 ——
     * 退化成普通边之后，兜底节点跑完会继续往下走，跳过父节点原本在等的画面，
     * 于是链在错误的界面上继续跑。而且**日志里一点痕迹都没有**。
     *
     * 前缀在解析阶段丢掉，运行期就再也拿不回来，所以只能在这里保留，
     * 由 [NodeEdges] 统一判定。
     */
    private fun stripPrefix(raw: String): String {
        val name = raw.trim()
        if (!name.startsWith("[")) return name
        // 回边前缀是语义的一部分，原样保留。
        if (name.startsWith(NodeEdges.JUMP_BACK_PREFIX)) return name
        val close = name.indexOf(']')
        return if (close > 0) name.substring(close + 1).trim() else name
    }
}

// ---------------------------------------------------------------- JSON 扩展

internal fun JSONObject.optIntArray(key: String): IntArray? {
    val arr = optJSONArray(key) ?: return null
    if (arr.length() == 0) return null
    return IntArray(arr.length()) { arr.optInt(it) }
}

internal fun JSONObject.optDoubleArray(key: String): DoubleArray? {
    val raw = opt(key) ?: return null
    if (raw is Number) return doubleArrayOf(raw.toDouble())
    val arr = raw as? JSONArray ?: return null
    if (arr.length() == 0) return null
    return DoubleArray(arr.length()) { arr.optDouble(it) }
}

internal fun JSONObject.optLongArray(key: String): LongArray? {
    val raw = opt(key) ?: return null
    if (raw is Number) return longArrayOf(raw.toLong())
    val arr = raw as? JSONArray ?: return null
    if (arr.length() == 0) return null
    return LongArray(arr.length()) { arr.optLong(it) }
}

/** 支持字符串或数组两种写法。 */
internal fun JSONObject.optStringList(key: String): List<String> {
    val raw = opt(key) ?: return emptyList()
    if (raw is String) return if (raw.isEmpty()) emptyList() else listOf(raw)
    val arr = raw as? JSONArray ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        arr.optString(i).takeIf { it.isNotEmpty() }
    }
}

/** 供 [Recognition] 的 box 字段使用。 */
internal fun Rect.isEmptyBox(): Boolean = width() == 0 && height() == 0

/**
 * 每轮识别的最低耗时默认值（毫秒）。
 *
 * 与虚拟屏的采集帧率（约 60fps）对齐 —— 再小就是空转，
 * 再大就会漏掉一两帧内的画面变化。
 *
 * Maa 原版默认 1000ms，那是为"慢节奏点击"设计的；
 * 本项目要的是"看到就立刻打断"，所以取 16。
 */
const val DEFAULT_RATE_LIMIT_MS = 16L

/** 命中框在 Mat 上的居中裁剪工具，供自定义动作预处理使用。 */
internal fun Mat.cropOrNull(rect: Rect): Mat? {
    if (rect.width() <= 0 || rect.height() <= 0) return null
    if (rect.right > width() || rect.bottom > height() || rect.left < 0 || rect.top < 0) return null
    return submat(org.opencv.core.Rect(rect.left, rect.top, rect.width(), rect.height()))
}
