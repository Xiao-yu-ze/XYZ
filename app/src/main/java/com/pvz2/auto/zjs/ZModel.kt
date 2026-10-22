package com.pvz2.auto.zjs

import org.json.JSONArray
import org.json.JSONObject

/**
 * 自动精灵的脚本模型。
 *
 * 字段清单来自 `_probe/zdjl-re/FIELDS.md`（从 `app.asar` 逐模块还原的反编译结果），
 * 不是照 MaaFramework 猜的 —— 两套协议完全不同。
 *
 * ## 铁律：未知字段必须原样保留
 *
 * 每个对象都带一个 [extra]，装下我们不认识的 key，序列化时写回去。
 *
 * 为什么这不是"锦上添花"：本程序只执行安卓上有意义的那部分动作，
 * 但用户导入的脚本来自 自动精灵，里面有 Windows 专用的、加密的、
 * 未来版本新增的字段。**丢掉它们等于把用户的脚本改坏了**，
 * 而且用户是在导出之后、在 自动精灵 里跑的时候才发现 --- 那时已经晚了。
 *
 * 所以这里的策略是：认识的字段解析成强类型，不认识的整个塞进 extra 带走。
 */
object Z {

    // ------------------------------------------------------------ 时间单位

    /** `delayUnit` / `repeatDelayUnit` / `timeIntervalUnit`。**默认 1 = 秒**，不是毫秒。 */
    enum class TimeUnit(val wire: Int, val millisPer: Long) {
        MILLISECONDS(0, 1L),
        SECONDS(1, 1_000L),
        MINUTES(2, 60_000L),
        HOURS(3, 3_600_000L);

        companion object {
            fun of(wire: Int): TimeUnit = entries.firstOrNull { it.wire == wire } ?: SECONDS
        }
    }

    // ------------------------------------------------------------ 位置

    /** `posData.type` 的 6 种取值。 */
    enum class PosType(val wire: String) {
        LOCATION("location"), IMAGE("image"), TEXT("text"),
        COLOR("color"), NODE("node"), AI_LOC("aiLoc");

        companion object {
            fun of(wire: String): PosType? = entries.firstOrNull { it.wire == wire }
        }
    }

    /** 模板图。PNG 的 base64 内嵌在脚本里，同时记下截图时的屏幕参数。 */
    data class ImageData(
        val data: String,
        val width: Int? = null,
        val height: Int? = null,
        val left: Int? = null,
        val top: Int? = null,
        val screenWidth: Int? = null,
        val screenHeight: Int? = null,
        val screenDpi: Float? = null,
        val extra: MutableMap<String, Any?> = LinkedHashMap()
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("data", data)
            width?.let { put("imageWidth", it) }
            height?.let { put("imageHeight", it) }
            left?.let { put("imageLeft", it) }
            top?.let { put("imageTop", it) }
            screenWidth?.let { put("screenWidth", it) }
            screenHeight?.let { put("screenHeight", it) }
            screenDpi?.let { put("screenDpi", it) }
            extra.forEach { (k, v) -> put(k, v) }
        }

        companion object {
            fun parse(o: JSONObject): ImageData? {
                val data = o.optString("data").takeIf { it.isNotEmpty() } ?: return null
                val handled = setOf(
                    "data", "imageWidth", "imageHeight", "imageLeft", "imageTop",
                    "screenWidth", "screenHeight", "screenDpi"
                )
                return ImageData(
                    data = data,
                    width = o.optIntOrNull("imageWidth"),
                    height = o.optIntOrNull("imageHeight"),
                    left = o.optIntOrNull("imageLeft"),
                    top = o.optIntOrNull("imageTop"),
                    screenWidth = o.optIntOrNull("screenWidth"),
                    screenHeight = o.optIntOrNull("screenHeight"),
                    screenDpi = o.optDoubleOrNull("screenDpi")?.toFloat(),
                    extra = o.collectUnhandled(handled)
                )
            }
        }
    }

    /**
     * 位置。用一个类 + [type] 承载 6 种形态，而不是 6 个子类 ——
     * 它们的公共字段（xOffset/yOffset/indexNum）占多数，子类会把
     * 「切换 type 时保留其它字段」变得很麻烦，而编辑器正需要这个。
     */
    data class PosData(
        val type: PosType,
        val x: ZValue? = null,
        val y: ZValue? = null,
        val imageData: ImageData? = null,
        val text: String? = null,
        val color: String? = null,
        val limitArea: ZArea? = null,
        val minSimilarPercent: Int? = null,
        val similarPercent: Int? = null,
        val indexNum: String? = null,
        val xOffset: ZValue? = null,
        val yOffset: ZValue? = null,
        val extra: MutableMap<String, Any?> = LinkedHashMap()
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("type", type.wire)
            x?.let { put("x", it.raw) }
            y?.let { put("y", it.raw) }
            imageData?.let { put("imageData", it.toJson()) }
            text?.let { put("text", it) }
            color?.let { put("color", it) }
            limitArea?.let { put("limitArea", it.toString()) }
            minSimilarPercent?.let { put("minSimilarPercent", it) }
            similarPercent?.let { put("similarPercent", it) }
            indexNum?.let { put("indexNum", it) }
            xOffset?.let { put("xOffset", it.raw) }
            yOffset?.let { put("yOffset", it.raw) }
            extra.forEach { (k, v) -> put(k, v) }
        }

        companion object {
            private val HANDLED = setOf(
                "type", "x", "y", "imageData", "text", "color", "limitArea",
                "minSimilarPercent", "similarPercent", "indexNum", "xOffset", "yOffset"
            )

            fun parse(o: JSONObject): PosData? {
                val type = PosType.of(o.optString("type")) ?: return null
                return PosData(
                    type = type,
                    x = o.opt("x")?.let { ZValue.parse(it.toString()) },
                    y = o.opt("y")?.let { ZValue.parse(it.toString()) },
                    imageData = o.optJSONObject("imageData")?.let { ImageData.parse(it) },
                    text = o.optString("text").takeIf { it.isNotEmpty() },
                    color = o.optString("color").takeIf { it.isNotEmpty() },
                    limitArea = ZArea.parse(o.optString("limitArea")),
                    minSimilarPercent = o.optIntOrNull("minSimilarPercent"),
                    similarPercent = o.optIntOrNull("similarPercent"),
                    indexNum = o.optString("indexNum").takeIf { it.isNotEmpty() },
                    xOffset = o.opt("xOffset")?.let { ZValue.parse(it.toString()) },
                    yOffset = o.opt("yOffset")?.let { ZValue.parse(it.toString()) },
                    extra = o.collectUnhandled(HANDLED)
                )
            }
        }
    }

    // ------------------------------------------------------------ 动作类型

    /**
     * 26 种动作。
     *
     * 线格式里 `type` 只有 21 个不同字符串：20 种直接用中文名，
     * 6 个「控制执行」族统一写 `type:"控制执行"` + `controlRunType`。
     *
     * [executable] 标记安卓上是否有执行器 —— 没有的照样能解析、能编辑、能保存，
     * 只是运行时会被明确拒绝，而不是静默跳过。
     */
    enum class ActionType(val wire: String, val controlRunType: String? = null, val executable: Boolean = false) {
        CLICK("点击", executable = true),
        CLICK_IMAGE("点击图片", executable = true),
        CLICK_TEXT("点击文字", executable = true),
        CLICK_COLOR("点击颜色", executable = true),
        CLICK_NODE("点击节点", executable = true),
        AI_CLICK("AI点击"),
        SWIPE("滑动", executable = true),
        GESTURE("单指手势"),
        MULTI_GESTURE("多指手势"),
        KEY("按键", executable = true),
        INPUT_TEXT("输入内容", executable = true),
        RECOGNIZE_SCREEN("识别屏幕"),
        OPEN_APP("打开应用", executable = true),
        OPEN_LINK("打开链接", executable = true),
        RUN_SCRIPT("执行脚本"),
        RUN_MULTI("运行多个动作"),
        CONTROL_RUN_AIM("控制执行", "runAimAction", executable = true),
        CONTROL_JUMP("控制执行", "jumpTo", executable = true),
        CONTROL_SET_JUMP_ID("控制执行", "setJumpId", executable = true),
        CONTROL_PAUSE("控制执行", "pause", executable = true),
        CONTROL_STOP("控制执行", "stop", executable = true),
        CONTROL_WAIT("控制执行", "waitDelay", executable = true),
        PROMPT("系统提示", executable = true),
        SET_VARS("设置变量"),
        RUN_JS("运行JS代码"),
        ENCRYPTED("加密动作");

        companion object {
            fun ofWire(type: String, controlRunType: String?): ActionType? {
                if (type == "控制执行") {
                    return entries.firstOrNull { it.controlRunType != null && it.controlRunType == controlRunType }
                        ?: CONTROL_WAIT
                }
                return entries.firstOrNull { it.controlRunType == null && it.wire == type }
            }
        }
    }

    /** 每个动作都有的 14 个字段。 */
    data class ActionCommon(
        val desc: String? = null,
        val jumpId: String? = null,
        val duration: String? = null,
        val delay: String? = null,
        val delayUnit: TimeUnit = TimeUnit.SECONDS,
        val repeatCount: Int? = null,
        val repeatDelayValue: String? = null,
        val repeatDelayUnit: TimeUnit = TimeUnit.SECONDS,
        val fastRepeatMode: Boolean = false,
        val disabled: Boolean = false,
        val condition: Condition? = null,
        /**
         * 字段级变量覆盖：key = 被覆盖的字段名，value = 该字段的变量定义。
         *
         * ## 这个机制很容易漏，漏了会丢数据
         *
         * 自动精灵允许把任意字段改成"运行时求值"。落盘时，
         * **被变量化的原字段会被删除**（避免污染），只留 `__vars`。
         *
         * 所以序列化时反过来也要做同样的处理：`__vars` 里出现了哪些字段，
         * 输出时就要把这些字段从顶层删掉。否则用户导出后，那个字段会同时
         * 存在"固定值"和"变量"两份，行为变得不确定。
         *
         * 原实现（反编译得到的 toStringifyJSON）：
         * ```js
         * this.json.__vars && Object.keys(this.json.__vars).forEach(t => {
         *   null != this.json.__vars[t] && delete e[t]
         * })
         * ```
         */
        val vars: Map<String, org.json.JSONObject> = emptyMap()
    )

    /** 所有能被 `__vars` 覆盖的字段名。序列化时要跳过它们。 */
    internal val ACTION_FIELD_NAMES = setOf(
        "desc", "jumpId", "duration", "delay", "repeatCount",
        "repeatDelayValue", "fastRepeatMode", "mouseButton", "posData",
        "startPos", "endPos", "keyCode", "inputText"
    )

    /**
     * 一个动作。
     *
     * `posData` / `startPos` / `endPos` 是位置类动作的参数，
     * 其余动作各自的独有字段全部落在 [extra] 里 —— 这样加新动作时
     * 不需要改这个类，只要在执行器里读 extra 即可。
     */
    data class Action(
        val type: ActionType,
        val common: ActionCommon = ActionCommon(),
        val posData: PosData? = null,
        val startPos: PosData? = null,
        val endPos: PosData? = null,
        val extra: MutableMap<String, Any?> = LinkedHashMap()
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("type", type.wire)
            type.controlRunType?.let { put("controlRunType", it) }
            common.desc?.let { put("desc", it) }
            common.jumpId?.let { put("jumpId", it) }
            common.duration?.let { put("duration", it) }
            common.delay?.let { put("delay", it) }
            put("delayUnit", common.delayUnit.wire)
            common.repeatCount?.let { put("repeatCount", it) }
            common.repeatDelayValue?.let { put("repeatDelayValue", it) }
            put("repeatDelayUnit", common.repeatDelayUnit.wire)
            if (common.fastRepeatMode) put("fastRepeatMode", true)
            if (common.disabled) put("disabled", true)
            common.condition?.let { put("condition", it.toJson()) }
            posData?.let { put("posData", it.toJson()) }
            startPos?.let { put("startPos", it.toJson()) }
            endPos?.let { put("endPos", it.toJson()) }
            extra.forEach { (k, v) -> put(k, v) }

            // ★ __vars 必须在最后处理，而且要把被变量化的字段从顶层删掉。
            //
            //   自动精灵的规则：某个字段改用变量后，原字段不落盘 ——
            //   只留 __vars 里那一份。两边都写的话，导出后那个字段会同时
            //   存在"固定值"和"变量"，行为不确定，用户还看不出来。
            if (common.vars.isNotEmpty()) {
                put("__vars", JSONObject().apply {
                    common.vars.forEach { (k, v) -> put(k, v) }
                })
                common.vars.keys.forEach { field -> if (has(field)) remove(field) }
            }
        }

        companion object {
            private val HANDLED = setOf(
                "type", "controlRunType", "desc", "jumpId", "duration", "delay", "delayUnit",
                "repeatCount", "repeatDelayValue", "repeatDelayUnit", "fastRepeatMode",
                "disabled", "condition", "posData", "startPos", "endPos", "__vars"
            )

            fun parse(o: JSONObject): Action? {
                val type = ActionType.ofWire(o.optString("type"), o.optString("controlRunType")) ?: return null
                // __vars：字段名 -> 变量定义。值可能是 null（表示"已移除"），跳过。
                val vars = LinkedHashMap<String, JSONObject>()
                o.optJSONObject("__vars")?.let { m ->
                    for (k in m.keys()) {
                        m.optJSONObject(k)?.let { vars[k] = it }
                    }
                }
                val common = ActionCommon(
                    desc = o.optString("desc").takeIf { it.isNotEmpty() },
                    jumpId = o.optString("jumpId").takeIf { it.isNotEmpty() },
                    duration = o.optString("duration").takeIf { it.isNotEmpty() },
                    delay = o.optString("delay").takeIf { it.isNotEmpty() },
                    delayUnit = TimeUnit.of(o.optInt("delayUnit", 1)),
                    repeatCount = o.optIntOrNull("repeatCount"),
                    repeatDelayValue = o.optString("repeatDelayValue").takeIf { it.isNotEmpty() },
                    repeatDelayUnit = TimeUnit.of(o.optInt("repeatDelayUnit", 1)),
                    fastRepeatMode = o.optBoolean("fastRepeatMode", false),
                    disabled = o.optBoolean("disabled", false),
                    condition = o.optJSONObject("condition")?.let { Condition.parse(it) },
                    vars = vars
                )
                return Action(
                    type = type,
                    common = common,
                    posData = o.optJSONObject("posData")?.let { PosData.parse(it) },
                    startPos = o.optJSONObject("startPos")?.let { PosData.parse(it) },
                    endPos = o.optJSONObject("endPos")?.let { PosData.parse(it) },
                    extra = o.collectUnhandled(HANDLED)
                )
            }
        }
    }

    // ------------------------------------------------------------ 条件

    enum class ConditionType(val wire: String) {
        IMAGE("image"), TEXT("textFount"), COLOR("colorFound"), NODE("nodeFound"),
        AI_CLOUD("aiCloud"), LIMIT_RUN_TIMES("limitRunTimes"), SCRIPT_RUN_STATE("scriptRunState"),
        RANDOM("random"), TIME_AFTER("timeAfter"), TIME_INTERVAL("timeInterval"),
        JS_EXPRESSION("jsExpression"), CONDITION_SET("conditionSet"), AROUND("AroundCondition");

        companion object {
            fun of(wire: String): ConditionType? = entries.firstOrNull { it.wire == wire }
        }
    }

    /**
     * 条件。独有字段同样落在 [extra]，理由同 [Action]。
     */
    data class Condition(
        val type: ConditionType,
        val runWhenFalse: Boolean = false,
        val disabled: Boolean = false,
        val repeatWhenFalse: Boolean = false,
        val repeatWhenFalseLimitTimes: Int? = 0,
        val repeatWhenFalseRepeatDelay: Int? = 1000,
        val checkBeforeDelay: Boolean = false,
        val desc: String? = null,
        val posData: PosData? = null,
        val extra: MutableMap<String, Any?> = LinkedHashMap()
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("type", type.wire)
            if (runWhenFalse) put("runWhenFalse", true)
            if (disabled) put("disabled", true)
            if (repeatWhenFalse) put("repeatWhenFalse", true)
            repeatWhenFalseLimitTimes?.let { put("repeatWhenFalseLimitTimes", it) }
            repeatWhenFalseRepeatDelay?.let { put("repeatWhenFalseRepeatDelay", it) }
            if (checkBeforeDelay) put("checkBeforeDelay", true)
            desc?.let { put("desc", it) }
            posData?.let {
                // 条件里的位置对象按协议直接内嵌，键名随类型不同
                when (type) {
                    ConditionType.IMAGE, ConditionType.COLOR -> put("image", it.toJson())
                    else -> put("text", it.toJson())
                }
            }
            extra.forEach { (k, v) -> put(k, v) }
        }

        companion object {
            private val HANDLED = setOf(
                "type", "runWhenFalse", "disabled", "repeatWhenFalse",
                "repeatWhenFalseLimitTimes", "repeatWhenFalseRepeatDelay",
                "checkBeforeDelay", "desc", "image", "text", "colorData", "node"
            )

            fun parse(o: JSONObject): Condition? {
                val type = ConditionType.of(o.optString("type")) ?: return null
                val posObj = o.optJSONObject("image") ?: o.optJSONObject("text")
                    ?: o.optJSONObject("colorData") ?: o.optJSONObject("node")
                return Condition(
                    type = type,
                    runWhenFalse = o.optBoolean("runWhenFalse", false),
                    disabled = o.optBoolean("disabled", false),
                    repeatWhenFalse = o.optBoolean("repeatWhenFalse", false),
                    repeatWhenFalseLimitTimes = o.optIntOrNull("repeatWhenFalseLimitTimes"),
                    repeatWhenFalseRepeatDelay = o.optIntOrNull("repeatWhenFalseRepeatDelay"),
                    checkBeforeDelay = o.optBoolean("checkBeforeDelay", false),
                    desc = o.optString("desc").takeIf { it.isNotEmpty() },
                    posData = posObj?.let { PosData.parse(it) },
                    extra = o.collectUnhandled(HANDLED)
                )
            }
        }
    }

    // ------------------------------------------------------------ 脚本

    /** 脚本级配置（文件第一行的 `baseInfo`）。 */
    data class BaseInfo(
        val repeatCount: Int? = null,
        val delay: String? = null,
        val delayUnit: TimeUnit = TimeUnit.SECONDS,
        val pauseOnFail: Boolean = true,
        val screenWidth: Int? = null,
        val screenHeight: Int? = null,
        val screenDpi: Float? = null,
        val description: String? = null,
        val extra: MutableMap<String, Any?> = LinkedHashMap()
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            repeatCount?.let { put("repeatCount", it) }
            delay?.let { put("delay", it) }
            put("delayUnit", delayUnit.wire)
            put("pauseOnFail", pauseOnFail)
            screenWidth?.let { put("screenWidth", it) }
            screenHeight?.let { put("screenHeight", it) }
            screenDpi?.let { put("screenDpi", it) }
            description?.let { put("description", it) }
            extra.forEach { (k, v) -> put(k, v) }
        }

        companion object {
            private val HANDLED = setOf(
                "repeatCount", "delay", "delayUnit", "pauseOnFail",
                "screenWidth", "screenHeight", "screenDpi", "description"
            )

            fun parse(o: JSONObject): BaseInfo = BaseInfo(
                repeatCount = o.optIntOrNull("repeatCount"),
                delay = o.optString("delay").takeIf { it.isNotEmpty() },
                delayUnit = TimeUnit.of(o.optInt("delayUnit", 1)),
                pauseOnFail = o.optBoolean("pauseOnFail", true),
                screenWidth = o.optIntOrNull("screenWidth"),
                screenHeight = o.optIntOrNull("screenHeight"),
                screenDpi = o.optDoubleOrNull("screenDpi")?.toFloat(),
                description = o.optString("description").takeIf { it.isNotEmpty() },
                extra = o.collectUnhandled(HANDLED)
            )
        }
    }

    /** 整个脚本：一个 baseInfo + 一串动作。 */
    data class Script(
        val baseInfo: BaseInfo = BaseInfo(),
        val actions: List<Action> = emptyList(),
        val extra: MutableMap<String, Any?> = LinkedHashMap()
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("baseInfo", baseInfo.toJson())
            put("actions", JSONArray().apply { actions.forEach { put(it.toJson()) } })
            extra.forEach { (k, v) -> put(k, v) }
        }

        companion object {
            private val HANDLED = setOf("baseInfo", "actions")

            fun parse(o: JSONObject): Script {
                val arr = o.optJSONArray("actions")
                val actions = ArrayList<Action>(arr?.length() ?: 0)
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { Action.parse(it) }?.let { actions.add(it) }
                    }
                }
                return Script(
                    baseInfo = o.optJSONObject("baseInfo")?.let { BaseInfo.parse(it) } ?: BaseInfo(),
                    actions = actions,
                    extra = o.collectUnhandled(HANDLED)
                )
            }
        }
    }
}

// ---------------------------------------------------------------- JSON 小工具

/** 只收下**没被处理过**的键，用于原样保留未知字段。 */
internal fun JSONObject.collectUnhandled(handled: Set<String>): MutableMap<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    for (key in keys()) {
        if (key in handled) continue
        out[key] = opt(key)
    }
    return out
}

internal fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

internal fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key) else null
