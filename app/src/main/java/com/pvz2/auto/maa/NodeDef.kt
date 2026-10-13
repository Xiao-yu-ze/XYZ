package com.pvz2.auto.maa

import android.graphics.Rect
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
    val rateLimit: Long = 1_000L,
    /** 识别到动作之间的等待，毫秒。官方默认 200。 */
    val preDelay: Long = 200L,
    /** 动作到下一轮识别之间的等待，毫秒。官方默认 200。 */
    val postDelay: Long = 200L,
    /** 是否参与识别。false 时被 next 跳过（等价于从列表里移除）。 */
    val enabled: Boolean = true,
    /** 该节点最多被命中多少次。官方默认 UINT_MAX。 */
    val maxHit: Long = 4_294_967_295L,
    /** 状态栏提示文字，原样透出到 UI。 */
    val focus: String? = null
)

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
        val root = JSONObject(json)
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

    private fun parseNode(name: String, obj: JSONObject): NodeDef {
        return NodeDef(
            name = name,
            recognition = parseRecognition(obj),
            action = parseAction(obj),
            next = parseNameList(obj.opt("next")),
            onError = parseNameList(obj.opt("on_error")),
            timeout = obj.optLong("timeout", 20_000L),
            rateLimit = obj.optLong("rate_limit", 1_000L),
            preDelay = obj.optLong("pre_delay", 200L),
            postDelay = obj.optLong("post_delay", 200L),
            enabled = obj.optBoolean("enabled", true),
            maxHit = obj.optLong("max_hit", 4_294_967_295L),
            focus = if (obj.isNull("focus")) null else obj.optString("focus").takeIf { it.isNotEmpty() }
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
                // expected 支持字符串或数组
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
                // 不支持的类型退化成 DirectHit 会静默改变行为，宁可当未命中处理。
                Recognition.DirectHit
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
     * 剥掉 `[JumpBack]` / `[Anchor]xxx` 前缀。
     *
     * 这两种语义都不实现（无尽挑战没用到），但名称必须还原成真实节点名，
     * 否则查表会失败。行为上退化成普通后继 —— 对当前流程是等价的。
     */
    private fun stripPrefix(raw: String): String {
        var name = raw.trim()
        if (name.startsWith("[")) {
            val close = name.indexOf(']')
            if (close > 0) name = name.substring(close + 1).trim()
        }
        return name
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

/** 命中框在 Mat 上的居中裁剪工具，供自定义动作预处理使用。 */
internal fun Mat.cropOrNull(rect: Rect): Mat? {
    if (rect.width() <= 0 || rect.height() <= 0) return null
    if (rect.right > width() || rect.bottom > height() || rect.left < 0 || rect.top < 0) return null
    return submat(org.opencv.core.Rect(rect.left, rect.top, rect.width(), rect.height()))
}
