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
        /**
         * 是不是「变量模式」那套键名（`base64 / width / height / left / top`）。
         *
         * FIELDS.md:193：同一个 ImageData 有两种等价写法，变量模式是
         * `{base64, width, height, left, top, screenWidth, screenHeight, scale}`。
         *
         * ## 原坑
         *
         * 旧实现只认 `data` 键 → 变量形态 `parse` 返回 null；而 `imageData`
         * 又被 [PosData] 记成"已处理"，不进 extra，于是整块模板
         * **读写两头都丢**：这条图片位置变成"没有模板数据"，存盘后彻底消失。
         *
         * 记下用的是哪套键名，是为了**按原样写回** —— 两种写法语义等价，
         * 但把变量形态改写成 `data/imageWidth` 会和脚本里 VarValue 的
         * 绑定形状对不上，属于无谓地"帮他改脚本"。
         */
        val varMode: Boolean = false,
        /**
         * 文件里有、但按当前这套键名**写不回去**的键。
         *
         * 两种来路：`data` 与 `base64` 同时出现（模板只可能有一份），
         * 或者两套尺寸键名混用（`imageWidth` 与 `width` 都在）。
         *
         * 记下来是因为这类输入以前是**静默吞掉一个键**：界面上看不出任何异常，
         * 要到自动精灵那边发现模板/尺寸不对。[com.pvz2.auto.zjs.ZScriptIO.validate]
         * 会把它报成问题条目（ZModel 侧不引 android.util.Log，保持模型可单测）。
         */
        val droppedKeys: List<String> = emptyList(),
        val extra: MutableMap<String, Any?> = LinkedHashMap()
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            if (varMode) {
                // 变量模式原样用 base64/width/height/left/top
                put("base64", data)
                width?.let { put("width", it) }
                height?.let { put("height", it) }
                left?.let { put("left", it) }
                top?.let { put("top", it) }
            } else {
                put("data", data)
                width?.let { put("imageWidth", it) }
                height?.let { put("imageHeight", it) }
                left?.let { put("imageLeft", it) }
                top?.let { put("imageTop", it) }
            }
            screenWidth?.let { put("screenWidth", it) }
            screenHeight?.let { put("screenHeight", it) }
            screenDpi?.let { put("screenDpi", it) }
            // extra 里装着变量模式的 `scale`（以及将来新增的键），原样带出去
            extra.forEach { (k, v) -> put(k, v) }
        }

        companion object {
            fun parse(o: JSONObject): ImageData? {
                // FIELDS.md:184 标准形态用 data；193 变量形态用 base64 —— 两个都认。
                val fromData = o.optString("data").takeIf { it.isNotEmpty() }
                val fromBase64 = o.optString("base64").takeIf { it.isNotEmpty() }
                val data = fromData ?: fromBase64 ?: return null
                val varMode = fromData == null
                // ★ 两种键名方言只能写回一套，另一套必然被吞（P2-d）。
                //   以前这里是完全静默的，所以先在模型里记下来给 validate 报。
                val dropped = ArrayList<String>(2)
                if (fromData != null && fromBase64 != null) dropped += "base64"
                val mirrorKeys = if (varMode) {
                    listOf("imageWidth", "imageHeight", "imageLeft", "imageTop")
                } else {
                    listOf("width", "height", "left", "top")
                }
                for (k in mirrorKeys) if (o.has(k) && !o.isNull(k)) dropped += k
                val handled = setOf(
                    "data", "base64",
                    "imageWidth", "imageHeight", "imageLeft", "imageTop",
                    "width", "height", "left", "top",
                    "screenWidth", "screenHeight", "screenDpi"
                )
                return ImageData(
                    data = data,
                    width = o.optIntOrNull("imageWidth") ?: o.optIntOrNull("width"),
                    height = o.optIntOrNull("imageHeight") ?: o.optIntOrNull("height"),
                    left = o.optIntOrNull("imageLeft") ?: o.optIntOrNull("left"),
                    top = o.optIntOrNull("imageTop") ?: o.optIntOrNull("top"),
                    screenWidth = o.optIntOrNull("screenWidth"),
                    screenHeight = o.optIntOrNull("screenHeight"),
                    screenDpi = o.optDoubleOrNull("screenDpi")?.toFloat(),
                    varMode = varMode,
                    droppedKeys = dropped,
                    // `scale` 不在 handled 里 → 自动落进 extra（FIELDS.md:193）
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
        /**
         * 要查找的文字。
         *
         * 对**正则对象**形态（`"text":{"type":"RegExp","source":"第\\d+关","flags":""}`），
         * 这里装的是那个对象的 **JSON 文本**（`optString` 的产物）——
         * [com.pvz2.auto.zjs.ZPosResolver.textRegex] 与
         * [com.pvz2.auto.zjs.ZRealMatcher] 的 `parseTextRegex` 正是按
         * "首字符 `{` + `type=="RegExp"`" 认出来的。**不要改成只放 source**，
         * 那会让正则退化成字面量匹配。
         */
        val text: String? = null,
        /**
         * `text` 的对象形态原文（正则对象）。
         *
         * ## 原坑
         *
         * FIELDS.md:203 允许 `text` 是正则对象。旧实现 `put("text", text)` 按
         * **字符串**写回，于是导出的文件里正则对象变成了它自己的 JSON 文本
         * （`"{\"type\":\"RegExp\",...}"`）—— 自动精灵不再当正则解析，
         * 这条"出现文字"就永远匹配不到。运行时我们还能认出来（见 [text]），
         * 但**文件已经被改坏了**，用户存一次就丢一次。
         *
         * 现在把对象原样留着，落盘时按对象写回（见 [textForJson]）。
         */
        val textRaw: JSONObject? = null,
        val color: String? = null,
        val limitArea: ZArea? = null,
        val minSimilarPercent: Int? = null,
        val similarPercent: Int? = null,
        val indexNum: String? = null,
        val xOffset: ZValue? = null,
        val yOffset: ZValue? = null,
        val extra: MutableMap<String, Any?> = LinkedHashMap()
    ) {
        /*
         * 颜色的逐通道上下界（Maa 的 lower/upper）。
         *
         * Maa 的 ColorMatch 就是这对上下界，而且**是非对称的**
         * （例如 R 237~255 但 G 202~229）。压成"中心色 + 容差"会改变判定范围，
         * 实测后果：植物种下后卡槽进入冷却、颜色已经变了，闸门却仍然通过。
         */
        val colorLower: List<Int>?
            get() = rgbList(extra["colorLower"])
        val colorUpper: List<Int>?
            get() = rgbList(extra["colorUpper"])

        /**
         * `extra` 里的 RGB 数组可能是 List、JSONArray 或逗号串。
         *
         * ★ 踩过的坑：原来只写 `as? List<*>`，而脚本解析出来的是
         *   org.json.JSONArray（不是 Kotlin List），于是**永远返回 null** ——
         *   Maa 的"颜色范围"判定从来没生效过，一直退化成"单色 + 相似度"。
         *   症状：范围写对了也认不出，用户只会去调相似度。
         */
        private fun rgbList(v: Any?): List<Int>? {
            val raw: List<Any?> = when (v) {
                is List<*> -> v
                is org.json.JSONArray -> (0 until v.length()).map { v.opt(it) }
                is String -> v.split(",", " ").filter { it.isNotBlank() }
                else -> return null
            }
            val out = raw.mapNotNull {
                (it as? Number)?.toInt() ?: (it as? String)?.trim()?.toIntOrNull()
            }
            return if (out.size >= 3) out else null
        }

        /**
         * `text` 落盘用哪个值：对象形态还是字符串。
         *
         * ## 为什么要比较
         *
         * 编辑器只改 [text]（String），不认识 [textRaw]。如果无脑优先写
         * `textRaw`，用户把"第\d+关"改成"第5关"后会**静默不生效**；
         * 反过来无脑写 `text`，正则对象就被摊成字符串（原坑）。
         *
         * 判据：`text` 还是 "那个对象的 JSON 文本"（= parse 时 optString 的产物）
         * → 没人动过 → 原样写对象；变了 → 以用户的新值为准。
         */
        private fun textForJson(): Any? {
            val obj = textRaw ?: return text
            /*
             * 被清空（text == null）→ 连正则对象一起清掉。
             *
             * 否则用户在编辑器里把文字删掉，界面上是空的，
             * 存盘时旧的正则对象又被原样写回 —— 表现为「删不掉」。
             * （parse 里 textRaw 非空时 text 必非空，所以这里只可能是用户清空）
             */
            if (text == null) return null
            return if (text != obj.toString()) text else obj
        }

        fun toJson(): JSONObject = JSONObject().apply {
            put("type", type.wire)
            x?.let { put("x", it.raw) }
            y?.let { put("y", it.raw) }
            imageData?.let { put("imageData", it.toJson()) }
            textForJson()?.let { put("text", it) }
            color?.let { put("color", it) }
            limitArea?.let { put("limitArea", it.toString()) }
            minSimilarPercent?.let { put("minSimilarPercent", it) }
            similarPercent?.let { put("similarPercent", it) }
            indexNum?.let { put("indexNum", it) }
            xOffset?.let { put("xOffset", it.raw) }
            yOffset?.let { put("yOffset", it.raw) }
            /*
             * ★ 先清掉 extra 里的同名旧值再写。
             *
             *   畸形 limitArea（比如只有 {"left":"10%"}）解析失败时，原文被塞进
             *   extra 兜底；用户随后在界面上把范围改对，只更新了强类型字段，
             *   extra 里的旧值还在 —— 而 extra 最后写，会把新值盖回去。
             *   表现是「改好了、保存了、再打开还是旧的」，最难查的一类。
             */
            // 解析成功（或被用户改过）时清掉兜底的旧原文；
            // 解析失败时**必须保留**，否则畸形 limitArea 会被静默丢掉。
            if (limitArea != null) extra.remove("limitArea")
            extra.forEach { (k, v) -> put(k, v) }
        }

        companion object {
            private val HANDLED = setOf(
                "type", "x", "y", "imageData", "text", "color", "limitArea",
                "minSimilarPercent", "similarPercent", "indexNum", "xOffset", "yOffset"
            )

            /**
             * 取一个「坐标/长度」字面量。
             *
             * ## 原坑
             *
             * 旧写法 `o.opt("x")?.let { ZValue.parse(it.toString()) }`：
             * 显式 `"x": null` 时 `opt` 返回 [JSONObject.NULL]（**不是** Kotlin null），
             * `toString()` 得到字符串 `"null"` → 解析不出 → 变成 `"0%"`。
             * 于是"没设置坐标"被静默改写成立"点左上角"，还会落盘成 "0%"。
             *
             * 现在：键不存在、或显式 null，都返回 null（=没设置），
             * 落盘时该键不写出 —— 与协议里"缺键即默认"一致。
             */
            private fun optValue(o: JSONObject, key: String): ZValue? {
                if (!o.has(key)) return null
                val raw = o.opt(key) ?: return null
                if (raw === JSONObject.NULL) return null
                return ZValue.parse(raw.toString())
            }

            fun parse(o: JSONObject): PosData? {
                val type = PosType.of(o.optString("type")) ?: return null

                // ★ limitArea 的两种写法都要认（FIELDS.md:49-51）：
                //     "33.04% 63.38% 38.41% 85.41%"           ← 字符串（文件里最常见）
                //     {"left":"10%","top":100,...}             ← 对象
                //   旧实现只有 optString 一条路：对对象拿到的是 JSON 文本，
                //   解析必然 null；而 limitArea 又在 HANDLED 里不进 extra，
                //   于是这块 ROI **读写两头都丢**（搜索范围静默变成全屏）。
                val area = ZArea.parseObject(o.optJSONObject("limitArea"))
                    ?: ZArea.parse(o.optString("limitArea"))
                val extra = o.collectUnhandled(HANDLED)
                // 两种写法都解析不了 → 原样留在 extra，别静默丢
                if (area == null && o.has("limitArea") && !o.isNull("limitArea")) {
                    extra["limitArea"] = o.opt("limitArea")
                }

                return PosData(
                    type = type,
                    x = optValue(o, "x"),
                    y = optValue(o, "y"),
                    imageData = o.optJSONObject("imageData")?.let { ImageData.parse(it) },
                    // 正则对象形态：text 保留"对象的 JSON 文本"（运行时靠它认正则），
                    // 对象本身另存一份，落盘时原样写回（见 textRaw / textForJson）。
                    text = o.optString("text").takeIf { it.isNotEmpty() },
                    textRaw = o.optJSONObject("text"),
                    color = o.optString("color").takeIf { it.isNotEmpty() },
                    limitArea = area,
                    minSimilarPercent = o.optIntOrNull("minSimilarPercent"),
                    similarPercent = o.optIntOrNull("similarPercent"),
                    indexNum = o.optString("indexNum").takeIf { it.isNotEmpty() },
                    xOffset = optValue(o, "xOffset"),
                    yOffset = optValue(o, "yOffset"),
                    extra = extra
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
    /**
 * 「控制执行」六个子类型的中文名。
 *
 * 协议里它们共用 `type:"控制执行"`，靠 `controlRunType` 区分
 * （jumpTo / waitDelay / …）。界面上原来直接把英文 wire 拼在中文后面
 * —— 「控制执行·jumpTo」这种，用户看不懂，而且中文界面里孤零零一段英文。
 * 这里给出中文名，**落盘仍然写英文**（协议不许改）。
 */
fun controlRunTypeLabel(value: String?): String? = when (value) {
    null -> null
    "runAimAction" -> "运行目标动作"
    "jumpTo" -> "跳转到"
    "setJumpId" -> "设置跳转标识"
    "pause" -> "暂停"
    "stop" -> "停止"
    "waitDelay" -> "等待"
    else -> value
}

enum class ActionType(val wire: String, val controlRunType: String? = null, val executable: Boolean = false) {
        CLICK("点击", executable = true),
        CLICK_IMAGE("点击图片", executable = true),
        CLICK_TEXT("点击文字", executable = true),
        CLICK_COLOR("点击颜色", executable = true),
        SWIPE("滑动", executable = true),
        /*
     * 单指手势现在能跑了：轨迹点在 `gestureActions` 里，是一串普通动作，
     * 执行器按顺序逐点执行（runActionList → runMulti 那套循环）。
     */
    GESTURE("单指手势", executable = true),
        /**
         * 多指手势。
         *
         * ## wire 必须是 `"多指手势"`（原坑）
         *
         * 我们曾把它改名成「多指滑动」并落盘。但**协议里只有「多指手势」**
         * （FIELDS.md:77 / SPEC.md:79，实现 m35408），自动精灵的反序列化
         * `P8()` 遇到未知 `type` 会直接 `throw new Error("未知动作类型")`
         * （SPEC.md:118）—— 不是跳过那一条，而是**整份脚本打不开**。
         *
         * 也就是说：只要用户的脚本里有这个动作，我们存一次，他就再也
         * 无法在自动精灵里打开了。所以 wire 必须回到协议原名。
         *
         * 我们自己的 `multiSwipe`（每根手指的起终点）只是**附加私有字段**，
         * 不顶替协议的 `gestureActions`（见 [Action.gestureActions]）。
         */
        MULTI_SWIPE("多指手势", executable = true),
        KEY("按键", executable = true),
        INPUT_TEXT("输入内容", executable = true),
        /* 识别屏幕：本地 OCR 读出整屏文字；clipboard 模式本机未接通道。 */
    RECOGNIZE_SCREEN("识别屏幕", executable = true),
        /* 执行脚本：filePath 指向另一个脚本文件，由 AutomationService 接的加载通道读出来跑。 */
    RUN_SCRIPT("执行脚本", executable = true),
        /*
     * 「运行多个动作」现在真的能跑了（执行器有 runMulti 分支），
     * 所以标成 executable —— 否则界面上会挂「安卓上暂不支持」，
     * 而监听槽位也会拒绝把它当动作执行。
     */
    RUN_MULTI("运行多个动作", executable = true),
        CONTROL_JUMP("控制执行", "jumpTo", executable = true),
        /*
         * ★ setJumpId / pause 执行器里**没有分支**（ZExecutor 的 when 零引用），
         *   原来却标成 executable=true —— 界面不显示"暂不支持"，用户以为能用；
         *   运行时被 when 的 else 当"还没实现"跳过：
         *     · setJumpId 被跳过 → 后面的 `跳转到 :那个标识` 找不到目标 → **整条任务终止**；
         *     · pause 被跳过 → 该停的地方**继续点**，比停下来更危险。
         *   标回 false：界面如实显示"暂不支持"，保存前校验也会提示。
         */
        /* 设置跳转标识=纯标签（协议里无独有字段），执行器已有分支（什么都不做）。 */
    CONTROL_SET_JUMP_ID("控制执行", "setJumpId", executable = true),
        /* 暂停：执行器现在真的会停下来等「继续」（ScriptRunState.paused）。 */
    CONTROL_PAUSE("控制执行", "pause", executable = true),
        CONTROL_STOP("控制执行", "stop", executable = true),
        CONTROL_WAIT("控制执行", "waitDelay", executable = true),
        PROMPT("系统提示", executable = true),
        /*
     * 设置变量现在能跑字面量赋值：vars: [{name, value}] 里 value 是
     * number/string 时写入运行时变量表，`${名字}` 会在查找文字/输入内容里代换。
     * 表达式、随机、取文本等其余 20 种 VarValue 仍求不出来（会明确报出来）。
     */
    SET_VARS("设置变量", executable = true),
        /* 运行JS代码：Rhino 解释执行，变量与运行时变量表互通。 */
    RUN_JS("运行JS代码", executable = true),

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
        WAIT_CANDIDATES("等待候选", executable = true),

    /*
     * ---------------- 我们自己的「游戏动作」----------------
     *
     * 协议里没有这些类型：自动精灵那 22 种是通用图像脚本原语，
     * 而 PVZ2 玩家脑子里想的是"把 3 号卡槽的植物种到第 2 行第 5 列"。
     * 用坐标画布表达这件事门槛太高（要自己量 45 个格子点），
     * 所以这里包一层：参数只有「卡槽 / 行 / 列」，执行时查 [PvzCoords]。
     *
     * 落盘写法是 `"type":"种植物"` + `pvzSlot` / `pvzRow` / `pvzCol`，
     * 自动精灵读不懂这几种（它那里没有），所以导出给别人的脚本要避免用。
     */
    PVZ_PLANT("种植物", executable = true),
    PVZ_FEED("喂豆", executable = true),
    PVZ_SHOVEL("铲除", executable = true),
    PVZ_TAP_CELL("点击格子", executable = true),
    PVZ_ARTIFACT("使用神器", executable = true),
    /* 点波 / 点加速：坐标固定，不需要选格子。原 MaaPVZ 的「下一波」「加速」。 */
    PVZ_WAVE("点下一波", executable = true),
    PVZ_SPEED("点加速", executable = true),
    /* 切换编队：先认后滑、6 次反向、循环，直到认出目标编队并点它。 */
    PVZ_SWITCH_DECK("切换编队", executable = true),
    /* 选补给：一个动作内部跑完整条补给链（查看→分类→兜底→确定×2~3）。 */
    PVZ_SUPPLY("选补给", executable = true),
    /* 开始战斗：认出「开始战斗」并点它（YS 的入口）。 */
    PVZ_START_BATTLE("开始战斗", executable = true),
    /* 进关判定：认出卡槽区绿点 = 已进关，顺手点加速。 */
    PVZ_ENTER_CHECK("进关判定", executable = true),
    /* 识别结算：认出「继续挑战」并点它 = 这一局结束。 */
    PVZ_SETTLE("识别结算", executable = true),
    /* 切换形态：在「固定 X + 卡槽 Y」那个点连点 N 下（球果切形态就是点两下）。 */
    PVZ_SWITCH_FORM("切换形态", executable = true);

        companion object {
            /**
             * 旧名字 → 当前枚举。
             *
             * ## 为什么必须留着（方向反过来了）
             *
             * 我们**曾经**把协议名「多指手势」改写成「多指滑动」并落过盘，
             * 所以用户手上可能有我们写出去的 `"type":"多指滑动"`。
             * 权威 wire 现在是协议原名「多指手势」（见 [MULTI_SWIPE]），
             * 这里保留反方向的别名，让我们自己写的旧文件还能读进来。
             *
             * 读进来后落盘统一写成 `"多指手势"` —— 即"读一次存一次就升级"。
             */
            private val LEGACY_ALIASES = mapOf(
                "多指滑动" to MULTI_SWIPE
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
         * 协议里的 `gestureActions`（FIELDS.md:459 / 470）：手势轨迹，
         * 元素是**完整动作**（通常是「点击」/「滑动」），单指手势与多指手势都用它。
         *
         * ## 原坑
         *
         * 旧实现完全没解析这个字段：它落进 extra 能保住结构，但模型里
         * 看不见、界面上改不了；而我们把「多指手势」改名成「多指滑动」之后，
         * 协议字段就只剩我们自己那个 `multiSwipe` —— 等于把协议的语义
         * 换成了私有语义。现在协议字段照协议解析，`multiSwipe` 只是附加。
         */
        val gestureActions: List<Action> = emptyList(),
        /** `gestureActions` 里解析不出来的原始项，原样回写，避免静默丢动作。 */
        val gestureRaw: List<JSONObject> = emptyList(),
        /**
         * 「运行多个动作」的子动作（FIELDS.md:527-536，实现 m32135）。
         *
         * ## 为什么必须建成强类型
         *
         * 真实脚本的主体往往就是一整个 `scriptSet`（SPEC.md:811-813 的样例，
         * 连续三行都是它；SPEC.md:824 的工程文件里也是）。以前 `scriptSet`
         * 不在 HANDLED 里 → 整包落进 extra：子动作**不进模型**，
         * 界面是空壳、执行器读不到，于是这条动作被整条跳过。
         *
         * ## 顺序就是执行顺序
         *
         * `runMode:normal`（默认）是**按数组顺序**跑的，`random` 才打乱
         * （SPEC.md:1074-1076）。所以这里必须保序 —— 任何排序/去重都会
         * 改变脚本行为，而且用户看不出来。
         */
        val scriptSet: List<Action> = emptyList(),
        /**
         * `scriptSet` 里解析不出来的原始项（未来版本的新动作类型）。
         *
         * 必须原样写回 `scriptSet` 数组：它是脚本主体，少一个子动作
         * 就可能让整段流程跑偏，而界面上不会有任何提示。
         *
         * 已知限制：这里不记原下标，写回时排在解析成功的项**之后**
         * （与 [gestureRaw] 同一约定）。要严格保序得再加一张下标表。
         */
        val scriptSetRaw: List<JSONObject> = emptyList(),
        /**
         * 未知 `controlRunType` 的原始字符串。
         *
         * ## 原坑
         *
         * `type=="控制执行"` 但 `controlRunType` 不在已知 6 种里时（新版本协议
         * 加了新控制动作），[ActionType.ofWire] 只能兜底成
         * [ActionType.CONTROL_WAIT] —— 否则整条动作解析失败被丢掉。
         * 但**回写必须写回原字符串**：以前写的是 `waitDelay`，
         * 用户文件里一个 "循环执行" 之类的新控制动作就被我们改成了"等待"。
         *
         * 运行期仍按"等待"处理（本机不认识的指令不动，代价只是这一步不生效），
         * 但文件里的语义原样保留，用户拿去自动精灵跑还是对的。
         */
        val rawControlRunType: String? = null,
        /**
         * 文件里写的是 `{"type":"控制执行"}`，**没有** `controlRunType`。
         *
         * 这种输入 `ofWire` 只能兜底成「等待」，而落盘时又会写成
         * `controlRunType:"waitDelay"` —— 等于我们替用户编了一个子类型。
         * 记下这一位，让 [com.pvz2.auto.zjs.ZScriptIO.validate] 能明确报出来
         * （以前是彻底静默：既不提示，文件还被改了）。
         */
        val controlRunTypeMissing: Boolean = false,
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
         * 认不出的监听槽位（未来版本可能加第 8 个）。
         *
         * ## 原坑
         *
         * `parseCallbacks` 里是 `CallbackSlot.of(key) ?: continue` —— 直接丢，
         * 而注释写的是"跳过之后它会落进 extra 被保留"。事实是
         * `scriptCallbacks` 整个键在 HANDLED 里，根本不会进 extra，
         * 于是那个槽位的动作**静默消失**（在自动精灵里它是要跑的）。
         * 现在原样收进来，回写时和已知槽位一起写进同一个 `scriptCallbacks`。
         */
        val extraCallbacks: Map<String, JSONObject> = emptyMap(),
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
            // 未知 controlRunType 必须原样写回（见 rawControlRunType 的注释）。
            // 写 type.controlRunType 会把用户的新控制动作改成 "waitDelay"。
            (rawControlRunType ?: type.controlRunType)?.let { put("controlRunType", it) }
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

            // 协议的轨迹数组（FIELDS.md:459/470）。以前完全没写回。
            if (gestureActions.isNotEmpty() || gestureRaw.isNotEmpty()) {
                put("gestureActions", org.json.JSONArray().apply {
                    gestureActions.forEach { put(it.toJson()) }
                    // 解析不出来的原始项按原样补回，不静默删除
                    gestureRaw.forEach { put(it) }
                })
            }

            // 「运行多个动作」的子动作（FIELDS.md:530）。
            // 数组就是执行顺序，且解析不了的要原样补回，不能少项。
            if (scriptSet.isNotEmpty() || scriptSetRaw.isNotEmpty()) {
                put("scriptSet", org.json.JSONArray().apply {
                    scriptSet.forEach { put(it.toJson()) }
                    scriptSetRaw.forEach { put(it) }
                })
            }

            extra.forEach { (k, v) -> put(k, v) }

            // scriptCallbacks：空 map 不写（FIELDS.md:303 `hasValue()` 的语义）。
            // 认不出的槽位（extraCallbacks）也要一起写回去 —— 以前是直接丢的。
            if (callbacks.isNotEmpty() || extraCallbacks.isNotEmpty()) {
                put("scriptCallbacks", JSONObject().apply {
                    callbacks.forEach { (slot, act) -> put(slot.wire, act.toJson()) }
                    extraCallbacks.forEach { (k, v) -> put(k, v) }
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
                "scriptCallbacks",
                // 协议的轨迹数组（多指/单指手势）。模型里是强类型，不能再当未知字段。
                "gestureActions",
                // 「运行多个动作」的子动作数组。同样是强类型：留在 extra 里
                // 就等于子动作不进模型 —— 那是这条动作会被整条跳过的直接原因。
                "scriptSet"
            )

            /**
             * 递归解析动作数组（`gestureActions` 与 `scriptSet` 共用同一套）。
             *
             * [rawOut] 收「解析不出来的原始 JSON」：这两个键现在都在 HANDLED 里，
             * 不再走 extra 兜底。如果这里直接丢掉，遇到未来版本的新动作类型，
             * 就会从「原样保留」退化成「静默删除」——用户存一次就永久丢动作。
             *
             * 非对象的元素（字符串/数字）无法原样保留，直接跳过：那种元素
             * 本身就是坏数据，自动精灵也解析不了。
             */
            private fun parseActionArray(
                arr: JSONArray?,
                rawOut: MutableList<JSONObject>? = null
            ): List<Action> {
                if (arr == null) return emptyList()
                val out = ArrayList<Action>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val a = Action.parse(o)
                    if (a != null) out.add(a) else rawOut?.add(o)
                }
                return out
            }

            /** 认不出的监听槽位。已知槽位由 [parseCallbacks] 处理，这里只补未知的。 */
            private fun parseUnknownCallbacks(o: JSONObject?): Map<String, JSONObject> {
                if (o == null) return emptyMap()
                val out = LinkedHashMap<String, JSONObject>()
                for (key in o.keys()) {
                    if (CallbackSlot.of(key) != null) continue
                    o.optJSONObject(key)?.let { out[key] = it }
                }
                return out
            }

            fun parse(o: JSONObject): Action? {
                // 解析不出来的子动作原文（未来版本的新类型），回写时原样补回
                val gestureRawOut = ArrayList<JSONObject>()
                val scriptSetRawOut = ArrayList<JSONObject>()
                // 原始 wire type / controlRunType 都要留下来：
                //  - 未知 controlRunType 会被 ofWire 兜底成"等待"，但回写必须写回原串；
                //  - 「控制执行」**缺** controlRunType 时同样兜底成"等待"，
                //    这一种以前是彻底静默的（既不提示、还落盘成 waitDelay），
                //    所以单独记一笔给 validate 报出来。
                val rawType = o.optString("type")
                val rawCrt = o.optString("controlRunType").takeIf { it.isNotEmpty() }
                val controlRunTypeMissing = rawType == "控制执行" && rawCrt == null
                val type = ActionType.ofWire(rawType, rawCrt) ?: return null
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
                    // 协议的轨迹数组：以前根本没读，等于把「多指手势」的语义丢了
                    gestureActions = parseActionArray(o.optJSONArray("gestureActions"), gestureRawOut),
                    gestureRaw = gestureRawOut,
                    // 「运行多个动作」的子动作：走同一套递归解析。
                    // 解析不了的进 scriptSetRaw（原样回写），不静默丢。
                    scriptSet = parseActionArray(o.optJSONArray("scriptSet"), scriptSetRawOut),
                    scriptSetRaw = scriptSetRawOut,
                    // 只有「控制执行」才谈得上"未知子类型"。判据收窄到 wire type，
                    // 否则「点击」带一个多余的 controlRunType 也会被记成
                    // "未知控制类型"，validate 会给出误导性报错（P2-c）。
                    rawControlRunType = rawCrt?.takeIf {
                        rawType == "控制执行" && it != type.controlRunType
                    },
                    controlRunTypeMissing = controlRunTypeMissing,
                    fingers = parseFingers(o.optJSONArray("multiSwipe")),
                    candidates = parseCandidates(o.optJSONArray("candidates")),
                    waitTimeoutMs = o.optIntOrNull("waitTimeoutMs"),
                    waitIntervalMs = o.optIntOrNull("waitIntervalMs"),
                    callbacks = parseCallbacks(o.optJSONObject("scriptCallbacks")),
                    extraCallbacks = parseUnknownCallbacks(o.optJSONObject("scriptCallbacks")),
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
             *
             * 认不出的槽位名这里跳过，但**不是丢掉** —— [parseUnknownCallbacks]
             * 会把它们收进 [Action.extraCallbacks] 原样回写。
             * （旧注释说"跳过之后会落进 extra 被保留"，那是错的：
             * `scriptCallbacks` 整个键在 HANDLED 里，压根不进 extra。）
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
        IMAGE("image"), TEXT("textFount"), COLOR("colorFound"),
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
                    else -> null
                }
                val posObj = primary?.let { o.optJSONObject(it) }
                    ?: o.optJSONObject("image")
                    ?: o.optJSONObject("text")
                    ?: o.optJSONObject("colorData")
                    ?: o.optJSONObject("node")
                    /*
                     * 兼容 `posData`：动作里的位置就叫 posData，手写脚本时很容易照着写进条件。
                     * 以前这种写法被塞进 extra，条件报「识别类条件没有配位置」—— 明明写了却不认。
                     */
                    ?: o.optJSONObject("posData")
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
        /**
         * NDJSON 里那些 `{"type":"linkedFile","name":...,"content":...}` 行的**原始文本**。
         *
         * ## 原坑
         *
         * [com.pvz2.auto.zjs.ZjsFileFormat.parse] 一直把它们收进
         * `ParseResult.linkedFiles`，但 `stringify()` **从不写回** ——
         * 用户打开再保存一次，内嵌子脚本（`执行脚本` 指向的那些）就全没了，
         * 而界面上什么都看不出来。
         *
         * 存原始字符串而不是 `JSONObject`：org.json 的键序不稳定，
         * 重新序列化就不再是"原样"了；而这些行要逐字节写回去（SPEC.md:709-712）。
         */
        val linkedFiles: List<String> = emptyList(),
        val extra: MutableMap<String, Any?> = LinkedHashMap()
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("baseInfo", baseInfo.toJson())
            put("actions", JSONArray().apply { actions.forEach { put(it.toJson()) } })
            extra.forEach { (k, v) -> put(k, v) }
            // 注意：linkedFiles 是 NDJSON 的独立行，单对象形态里没有位置放它们。
            // 真正的落盘走 ZjsFileFormat.stringify（它会把它们插在 baseInfo 之后）。
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
