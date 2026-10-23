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
        MULTI_SWIPE("多指滑动", executable = true),
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
        ENCRYPTED("加密动作"),

        /**
         * 等候选出现 —— **我们自己的动作类型，协议里没有**。
         *
         * ## 为什么必须是一等公民
         *
         * Maa 的 `next` 是一条候选链：每轮从当前节点出发按顺序逐个识别，
         * 第一个成功的被进入；都不成功就继续等，直到超时。
         *
         * 用「条件 + 跳转」拼能凑出近似的分支，但凑不出**等待**：
         * 条件不满足就跳过，没有任何地方表示"我在这里再等一会儿"。
         * 从 Maa 转过来的脚本就会一路冲过去，而不是等画面出现 ——
         * 这正是「Maa 能做到的我们也得能做到」差的那一块。
         *
         * 语义：
         * ```
         * 循环 {
         *   按顺序试每个候选：条件成立（或没有条件）→ 跳到它的 jumpTo
         *   睡 intervalMs
         * } 直到超过 timeoutMs（0 = 一直等）
         * 超时仍没命中 → 往下走（等价于 Maa 里 timeout 到点）
         * ```
         */
        WAIT_CANDIDATES("等待候选", executable = true);

        companion object {
            /**
             * 旧名字 → 新名字。
             *
             * ## 为什么必须留着
             *
             * 「多指手势」改成「多指滑动」是我们自己的重命名，但**用户的旧脚本里
             * 写的是旧名字**。不认旧名字的话，那条动作在读取时会被静默丢弃 ——
             * 用户打开脚本发现少了一条，而界面上什么提示都没有。
             *
             * 落盘时写成新名字（[ActionType.wire]），所以旧脚本读一次存一次
             * 就自动升级了。
             */
            private val LEGACY_ALIASES = mapOf(
                "多指手势" to MULTI_SWIPE
            )

            fun ofWire(type: String, controlRunType: String?): ActionType? {
                if (type == "控制执行") {
                    return entries.firstOrNull { it.controlRunType != null && it.controlRunType == controlRunType }
                        ?: CONTROL_WAIT
                }
                LEGACY_ALIASES[type]?.let { return it }
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

    /**
     * 监听槽位。7 个，顺序与 FIELDS.md:288-289 一致。
     *
     * 触发时机（FIELDS.md:292-300）：
     *  - 前三个只在动作**有 condition** 时才触发；
     *  - `afterExecSuc` 每次重复成功都触发；
     *  - `afterExecFail` 一旦设置，会忽略 `pauseOnFail` 与全局失败监听；
     *  - `afterExecFinish` 所有重复结束后触发一次，但**跳转跳出时不触发**。
     */
    enum class CallbackSlot(val wire: String, val label: String) {
        BEFORE_CONDITION("beforeCondition", "检查运行条件前"),
        AFTER_CONDITION_SUC("afterConditionSuc", "运行条件满足后"),
        AFTER_CONDITION_FAIL("afterConditionFail", "运行条件失败后"),
        BEFORE_START_EXEC("beforeStartExec", "动作开始运行前"),
        AFTER_EXEC_SUC("afterExecSuc", "动作运行成功后"),
        AFTER_EXEC_FAIL("afterExecFail", "动作运行失败后"),
        AFTER_EXEC_FINISH("afterExecFinish", "动作运行结束后");

        companion object {
            fun of(wire: String): CallbackSlot? = entries.firstOrNull { it.wire == wire }
        }
    }

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
        /**
         * 监听动作。7 个槽位，每个槽位是**一个完整的动作**（不是数组）。
         *
         * 依据 FIELDS.md:285-303，槽位名见 [CallbackSlot]。
         *
         * ## 为什么必须建模，不能当未知字段扔进 extra
         *
         * 扔进 extra 的话 JSON 结构不会坏（`JSONObject.toString()` 照样输出），
         * 但**用户在编辑器里看不到它** —— 一个挂了 7 段监听的动作展开后
         * 干干净净，用户会以为没挂监听，而那些监听在自动精灵里是会真的跑的。
         *
         * 空 map 视为"未设置"（FIELDS.md:303 `hasValue()`），落盘时不写。
         */
        val callbacks: Map<CallbackSlot, Action> = emptyMap(),
        /**
         * 多指滑动的每根手指。
         *
         * ## 这是本项目自己的节点，不是自动精灵的东西
         *
         * 协议里只有一个「多指手势」（`gestureActions` 装的是**完整动作数组**），
         * 那套结构没法表达"多根手指同时滑、共用同一组时长和曲线"。
         *
         * 这里改成一组起终点：
         *
         * ```
         * fingers: [ (start, end), (start, end), ... ]
         * ```
         *
         * 所有手指共用 `duration`（拖动总时长）和其余通用字段 ——
         * 多指手势的语义本来就是"同时"，每根手指各有时长是讲不通的。
         *
         * 落盘时写成 `multiSwipe: [{startPos, endPos}, ...]`。
         * 自动精灵读到会当成未知字段跳过（不崩），我们自己读得回来。
         */
        val fingers: List<Finger> = emptyList(),
        /**
         * 「等待候选」的候选列表（我们自己的节点，见 [ActionType.WAIT_CANDIDATES]）。
         *
         * 顺序就是优先级：Maa 的 `next` 是"按顺序试，第一个命中就进"，
         * 所以这里**不能排序、不能去重**。
         */
        val candidates: List<Candidate> = emptyList(),
        /** 「等待候选」的总超时（毫秒）。0 或缺省 = 一直等。 */
        val waitTimeoutMs: Int? = null,
        /** 「等待候选」每轮之间的间隔（毫秒），缺省 200。 */
        val waitIntervalMs: Int? = null,
        val extra: MutableMap<String, Any?> = LinkedHashMap()
    ) {
        /**
         * 多指滑动里的一根手指。
         *
         * 起点终点都用 [PosData]，和单指滑动一样 —— 这样取点、区域、
         * 图片匹配这些能力全都能直接用上，不用另写一套。
         */
        data class Finger(
            val startPos: PosData? = null,
            val endPos: PosData? = null
        ) {
            fun toJson(): JSONObject = JSONObject().apply {
                startPos?.let { put("startPos", it.toJson()) }
                endPos?.let { put("endPos", it.toJson()) }
            }

            companion object {
                fun parse(o: JSONObject): Finger = Finger(
                    startPos = o.optJSONObject("startPos")?.let { PosData.parse(it) },
                    endPos = o.optJSONObject("endPos")?.let { PosData.parse(it) }
                )
            }
        }

        /**
         * 「等待候选」里的一个候选。
         *
         * `condition == null` 表示无条件 —— Maa 里对应 `DirectHit` 的候选：
         * 试到它就一定命中，所以它天然适合放到列表末尾当兜底。
         */
        data class Candidate(
            val condition: Condition? = null,
            /** 条件成立后跳到哪个动作的 jumpId。null = 留在原地往下走。 */
            val jumpTo: String? = null
        ) {
            fun toJson(): JSONObject = JSONObject().apply {
                condition?.let { put("condition", it.toJson()) }
                jumpTo?.let { put("jumpTo", it) }
            }

            companion object {
                fun parse(o: JSONObject): Candidate = Candidate(
                    condition = o.optJSONObject("condition")?.let { Condition.parse(it) },
                    jumpTo = o.optString("jumpTo").takeIf { it.isNotEmpty() }
                )
            }
        }

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

            // 「等待候选」：候选顺序就是优先级，必须原样保留（不排序、不去重）
            if (candidates.isNotEmpty()) {
                put("candidates", org.json.JSONArray().apply {
                    candidates.forEach { put(it.toJson()) }
                })
            }
            waitTimeoutMs?.let { put("waitTimeoutMs", it) }
            waitIntervalMs?.let { put("waitIntervalMs", it) }

            // 多指滑动的手指列表。空列表不写 —— 空数组在协议里没有含义，
            // 而自动精灵读到未知字段会跳过，写一个空数组只会让文件变乱。
            if (fingers.isNotEmpty()) {
                put("multiSwipe", org.json.JSONArray().apply {
                    fingers.forEach { put(it.toJson()) }
                })
            }

            extra.forEach { (k, v) -> put(k, v) }

            // scriptCallbacks：空 map 不写（FIELDS.md:303 `hasValue()` 的语义）。
            if (callbacks.isNotEmpty()) {
                put("scriptCallbacks", JSONObject().apply {
                    callbacks.forEach { (slot, act) -> put(slot.wire, act.toJson()) }
                })
            }

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
                "disabled", "condition", "posData", "startPos", "endPos", "__vars", "multiSwipe",
                "candidates", "waitTimeoutMs", "waitIntervalMs",
                "scriptCallbacks"
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
                    fingers = parseFingers(o.optJSONArray("multiSwipe")),
                    candidates = parseCandidates(o.optJSONArray("candidates")),
                    waitTimeoutMs = o.optIntOrNull("waitTimeoutMs"),
                    waitIntervalMs = o.optIntOrNull("waitIntervalMs"),
                    callbacks = parseCallbacks(o.optJSONObject("scriptCallbacks")),
                    extra = o.collectUnhandled(HANDLED)
                )
            }

            /** 解析「等待候选」的候选列表。坏一个只丢一个，不影响整条动作。 */
            private fun parseCandidates(arr: org.json.JSONArray?): List<Candidate> {
                if (arr == null) return emptyList()
                val out = ArrayList<Candidate>(arr.length())
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { out.add(Candidate.parse(it)) }
                }
                return out
            }

            /**
             * 解析多指滑动的手指列表。
             *
             * 单根手指解析失败就跳过那一根，不影响其余 —— 一个坏的手指
             * 不该让整个动作读不出来（那样用户会看到"这条没了"）。
             */
            private fun parseFingers(arr: org.json.JSONArray?): List<Finger> {
                if (arr == null) return emptyList()
                val out = ArrayList<Finger>(arr.length())
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { out.add(Finger.parse(it)) }
                }
                return out
            }

            /**
             * 解析 7 个监听槽位（FIELDS.md:285-303）。
             *
             * 每个槽位是一个**完整动作**，不是数组。
             * 认不出的槽位名跳过 —— 将来版本可能加第 8 个，
             * 但跳过之后它就落进 extra 被保留，不会丢。
             */
            private fun parseCallbacks(o: JSONObject?): Map<CallbackSlot, Action> {
                if (o == null) return emptyMap()
                val out = LinkedHashMap<CallbackSlot, Action>()
                for (key in o.keys()) {
                    val slot = CallbackSlot.of(key) ?: continue
                    o.optJSONObject(key)?.let { Action.parse(it) }?.let { out[slot] = it }
                }
                return out
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
                // 条件里的位置对象按协议直接内嵌，**键名随条件类型变化**。
                //
                // 依据 FIELDS.md:266-269：
                //
                //   image      -> image
                //   textFount  -> text
                //   colorFound -> colorData     ← 不是 image
                //   nodeFound  -> node          ← 不是 text
                //
                // ## 写错不报错，但条件会静默失效
                //
                // 键名错了 JSON 结构依然合法，导出的文件也不报任何异常。
                // 但自动精灵按 type 去取对应的键，取不到就当作**条件恒为 false**
                // （SPEC.md:1031-1035：条件异常被吞掉、按不成立处理）。
                //
                // 用户看到的是"这条动作本来有颜色条件，导出后条件没了"，
                // 而界面上一切正常。所以下面用显式映射，不用 when-else 兜底 ——
                // 将来加新类型时漏写会被编译器提醒，而不是默认塞成 text。
                val key = when (type) {
                    ConditionType.IMAGE -> "image"
                    ConditionType.TEXT -> "text"
                    ConditionType.COLOR -> "colorData"
                    ConditionType.NODE -> "node"
                    // 其余条件类型不内嵌位置对象（FIELDS.md:270-278
                    // 都是别的形状：aiCloud 有 image1/2/3，conditionSet 有 conditions 数组……）
                    else -> null
                }
                key?.let { put(it, posData.toJson()) }
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

                // 按类型取对应的内嵌位置键（FIELDS.md:266-269）。
                //
                // 先按本类型应有的键取，取不到再依次试其余三个 ——
                // 兼顾两种情况：老文件键名不规范、以及别人手写的脚本。
                // 但**首选**永远是协议规定的那一个，避免把 image 条件
                // 误读成 text 条件。
                val primary = when (type) {
                    ConditionType.IMAGE -> "image"
                    ConditionType.TEXT -> "text"
                    ConditionType.COLOR -> "colorData"
                    ConditionType.NODE -> "node"
                    else -> null
                }
                val posObj = primary?.let { o.optJSONObject(it) }
                    ?: o.optJSONObject("image")
                    ?: o.optJSONObject("text")
                    ?: o.optJSONObject("colorData")
                    ?: o.optJSONObject("node")
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
