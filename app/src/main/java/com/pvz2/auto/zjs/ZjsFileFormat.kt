package com.pvz2.auto.zjs

import org.json.JSONObject

/**
 * `.zjs` 文件的**真实**读写格式。
 *
 * ## 格式不是普通 JSON，是 NDJSON
 *
 * 依据 SPEC.md:670-717（含 m62741 `class te` 的反编译源码）：
 *
 * ```
 * 第 1 行          { "repeatCount": 1, "delayUnit": 1, ..., "minVerName": "26.0.0" }
 * 中间若干行        { "type": "linkedFile", "name": "...", "content": "..." }   可选
 * 之后每行          { "type": "点击", ... }                                      一个动作
 * ```
 *
 * - 编码 **UTF-8 无 BOM**，换行 **CRLF**
 * - 没有文件头魔数，没有顶层 version 字段
 * - 图片（`imageData.data`）直接躺在动作 JSON 里，是 PNG 的 base64
 *
 * ## baseInfo 落盘时要注入 8 个字段
 *
 * SPEC.md:704-708 的 `toJSONString({...})` 会合并进去：
 *
 * | 字段 | 含义 |
 * |---|---|
 * | `minVerName` / `minVerCode` | 读这份脚本所需的最低版本 |
 * | `count` | 动作总数（含监听里的动作） |
 * | `savedVerName` / `savedVerCode` | 保存时的版本 |
 * | `lastSaveScreenWidth/Height/Dpi` | 保存时的屏幕参数 |
 *
 * ## 为什么这件事必须做对
 *
 * 写错的话，我们导出的文件**自动精灵打不开**。而用户是在导出之后、
 * 在另一个程序里才发现 —— 那时已经晚了。
 */
object ZjsFileFormat {

    /** 换行必须是 CRLF（SPEC.md:712 `.join("\r\n")`）。 */
    const val CRLF = "\r\n"

    /** 保存时写入的版本号。取我们对照的那个版本的格式。 */
    const val SAVED_VER_NAME = "26.0.0"

    /** 内嵌子脚本行的 type（SPEC.md:675）。 */
    private const val LINKED_FILE = "linkedFile"

    /**
     * 把一个脚本序列化成 `.zjs` 文本。
     *
     * @param script    要保存的脚本
     * @param screenWidth / screenHeight / screenDpi 保存时的屏幕参数，
     *        自动精灵靠它判断坐标是否需要换算。传 0 表示未知。
     */
    fun stringify(
        script: Z.Script,
        screenWidth: Int = 0,
        screenHeight: Int = 0,
        screenDpi: Float = 0f
    ): String {
        val lines = ArrayList<String>()

        // 1) baseInfo，注入版本与统计
        lines += buildBaseInfo(script, screenWidth, screenHeight, screenDpi).toString()

        // 2) 内嵌子脚本：必须在 baseInfo 之后、动作之前（SPEC.md:709-712），
        //    而且**逐字节原样**输出（见 Z.Script.linkedFiles 的说明）。
        //
        //    原坑：parse 把它们收进 ParseResult.linkedFiles，stringify 从不写回。
        //    用户打开一次、保存一次，`执行脚本` 指向的内嵌子脚本就全没了。
        for (line in script.linkedFiles) {
            if (line.isNotBlank()) lines += line
        }

        // 3) 空动作要跳过（SPEC.md:711 `actionsIgnoreEmpty`）。
        //    数组里可能有 null（解析失败的那条），落盘时不能变成 "null" 一行。
        script.actions.forEach { lines += it.toJson().toString() }

        return lines.joinToString(CRLF)
    }

    /**
     * baseInfo + 注入字段。
     *
     * `count` 是**递归**统计的：监听里的动作也算（SPEC.md:702
     * `e.getActionsCount()`）。数少了自动精灵可能判定文件损坏。
     *
     * ## 脚本级的未知字段并进这一行
     *
     * NDJSON 里只有第 1 行能承载"脚本级"信息（第 2 行起全是动作）。
     * 所以 `Z.Script.extra` 里我们不认识的键必须并进 baseInfo ——
     * 否则它们会**静默消失**，而用户的脚本里可能有 `fromStore`、
     * `encrypt` 这类我们没建模但自动精灵要用的字段。
     */
    private fun buildBaseInfo(
        script: Z.Script,
        width: Int,
        height: Int,
        dpi: Float
    ): JSONObject = script.baseInfo.toJson().apply {
        // 先并脚本级未知字段，再写注入字段 —— 注入的优先，
        // 免得用户的旧值把 minVerName 之类顶掉。
        script.extra.forEach { (k, v) -> if (k != "baseInfo" && k != "actions") put(k, v) }

        put("minVerName", SAVED_VER_NAME)
        put("minVerCode", versionCode(SAVED_VER_NAME))
        put("count", countActions(script))
        put("savedVerName", SAVED_VER_NAME)
        put("savedVerCode", versionCode(SAVED_VER_NAME))
        if (width > 0) put("lastSaveScreenWidth", width)
        if (height > 0) put("lastSaveScreenHeight", height)
        if (dpi > 0f) put("lastSaveScreenDpi", dpi)
    }

    /**
     * 版本名 → 版本号。
     *
     * 协议实测（SPEC.md:740 的真实工程文件）：
     *
     *     minVerName "2.26.0"  → minVerCode  2026000
     *     savedVerName "26.0.0" → savedVerCode 26000000
     *
     * 即 `major*1e6 + minor*1e3 + patch*10`。
     *
     * ## 原坑
     *
     * 旧公式是 `major*10000 + minor*100 + patch` —— **差 100 倍**：
     * "26.0.0" 算成 260000（0.26.0 量级）。名字写着要求 26.0.0、
     * 码却说是极低版本，文件里自相矛盾。看着能打开，但版本判断全错。
     *
     * `internal` 而不是 `private`：单测要直接钉这个公式。
     */
    internal fun versionCode(name: String): Int {
        val parts = name.split('.')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
        return major * 1_000_000 + minor * 1_000 + patch * 10
    }

    /**
     * 递归统计动作数（SPEC.md:702 `e.getActionsCount()` 是递归的）。
     *
     * ## 原坑
     *
     * 旧实现只递归 `scriptCallbacks`，漏了两处内嵌动作：
     *  - `baseInfo.globalCallbacks` 的 9 个全局槽位（FIELDS.md:305-317）
     *  - `action.scriptSet`（「运行多个动作」的子动作数组，FIELDS.md:530）
     * 数少了自动精灵可能判定文件损坏。
     *
     * ## 两条路并存，但同一个动作只走一条
     *
     * `scriptSet` / `gestureActions` / `scriptCallbacks` 现在是强类型字段 →
     * 按**模型**递归（[countAction]）；`globalCallbacks` 和解析不出来的
     * `*Raw` 条目只有 JSON → 按**JSON**递归（[countActionJson]）。
     * 千万不要对同一个动作既走模型又 `toJson()` 再遍历一遍，那会重复计数。
     */
    fun countActions(script: Z.Script): Int {
        var n = 0
        for (a in script.actions) n += countAction(a)
        // baseInfo 里的全局监听也是动作（它们不算在 actions 里）
        val gc = script.baseInfo.extra["globalCallbacks"]
        if (gc is JSONObject) {
            for (k in gc.keys()) gc.optJSONObject(k)?.let { n += countActionJson(it) }
        }
        return n
    }

    /** 只有动作列表时用（保留旧签名，行为与 [countActions] 的单动作部分一致）。 */
    fun countActions(actions: List<Z.Action>): Int {
        var n = 0
        for (a in actions) n += countAction(a)
        return n
    }

    /**
     * 一个动作自身 + 它内嵌的全部动作（按**模型**递归）。
     *
     * 四种内嵌：`scriptCallbacks`（7 槽）、`extraCallbacks`（认不出的槽位）、
     * `scriptSet`（运行多个动作的子动作）、`gestureActions`（手势轨迹）。
     * 解析不出来的 `*Raw` 只有 JSON，交给 [countActionJson]。
     */
    private fun countAction(a: Z.Action): Int {
        var n = 1
        for (cb in a.callbacks.values) n += countAction(cb)
        for (r in a.extraCallbacks.values) n += countActionJson(r)
        for (s in a.scriptSet) n += countAction(s)
        for (r in a.scriptSetRaw) n += countActionJson(r)
        for (g in a.gestureActions) n += countAction(g)
        for (r in a.gestureRaw) n += countActionJson(r)
        return n
    }

    /**
     * 只有原始 JSON 的动作（`globalCallbacks` 槽位、解析不了的子动作）走这条。
     *
     * 递归 `scriptCallbacks` / `scriptSet` / `gestureActions` 三种内嵌。
     */
    private fun countActionJson(o: JSONObject): Int {
        var n = 1
        o.optJSONObject("scriptCallbacks")?.let { cbs ->
            for (k in cbs.keys()) cbs.optJSONObject(k)?.let { n += countActionJson(it) }
        }
        o.optJSONArray("scriptSet")?.let { set ->
            for (i in 0 until set.length()) set.optJSONObject(i)?.let { n += countActionJson(it) }
        }
        o.optJSONArray("gestureActions")?.let { gs ->
            for (i in 0 until gs.length()) gs.optJSONObject(i)?.let { n += countActionJson(it) }
        }
        return n
    }

    /**
     * 解析 `.zjs` 文本。
     *
     * 逐行读：第 1 行是 baseInfo，其余每行一个动作。
     * `{"type":"linkedFile",...}` 行不解析内容，但**原文收进
     * [Z.Script.linkedFiles]**，保存时原样写回（以前只在
     * [ParseResult.linkedFiles] 里留了一份 JSONObject，stringify 却从不写回，
     * 于是存一次就丢）。
     *
     * 单行解析失败**不会**让整个文件失败：跳过那一行并记进 [ParseResult.badLines]。
     * 一个坏动作不该让用户看不到其余 200 个正常节点，但也不能静默 ——
     * 用户会以为脚本本来就少。
     */
    data class ParseResult(
        val script: Z.Script? = null,
        val linkedFiles: List<JSONObject> = emptyList(),
        val badLines: List<Int> = emptyList(),
        val error: String? = null
    )

    fun parse(text: String): ParseResult {
        if (text.isBlank()) return ParseResult(error = "文件是空的")

        // 两种换行都要认：老文件可能是 LF，我们自己写的是 CRLF
        val lines = text.split('\n').map { it.trimEnd('\r') }
        val nonBlank = lines.withIndex().filter { it.value.isNotBlank() }
        if (nonBlank.isEmpty()) return ParseResult(error = "文件里没有内容")

        return try {
            val baseInfoLine = nonBlank.first()
            val baseInfo = Z.BaseInfo.parse(JSONObject(baseInfoLine.value))

            val actions = ArrayList<Z.Action>()
            val linked = ArrayList<JSONObject>()
            // 内嵌子脚本行的**原文**。保存时要逐字节写回去（见 Z.Script.linkedFiles）。
            val linkedRaw = ArrayList<String>()
            val bad = ArrayList<Int>()

            for ((idx, line) in nonBlank.drop(1)) {
                try {
                    val obj = JSONObject(line)
                    if (obj.optString("type") == LINKED_FILE) {
                        // 内嵌子脚本：现在不解析内容，但绝不能丢 ——
                        // 丢了用户的脚本就不完整，而且 `执行脚本` 会指向不存在的文件。
                        linked.add(obj)
                        linkedRaw.add(line)
                        continue
                    }
                    val action = Z.Action.parse(obj)
                    if (action == null) bad.add(idx + 1) else actions.add(action)
                } catch (t: Throwable) {
                    bad.add(idx + 1)
                }
            }

            ParseResult(
                script = Z.Script(
                    baseInfo = baseInfo,
                    actions = actions,
                    linkedFiles = linkedRaw,
                    // baseInfo 行里我们不认识的键，提到脚本级 extra。
                    // 写回时它们会并回第 1 行 —— 这样往返不丢字段。
                    extra = baseInfoLineExtra(baseInfoLine.value)
                ),
                linkedFiles = linked,
                badLines = bad
            )
        } catch (t: Throwable) {
            ParseResult(error = "第 1 行不是合法的 baseInfo：${t.message}")
        }
    }

    /** baseInfo 里未被 [Z.BaseInfo] 建模的键。 */
    private fun baseInfoLineExtra(line: String): MutableMap<String, Any?> = try {
        val obj = JSONObject(line)
        val known = setOf(
            "repeatCount", "delay", "delayUnit", "pauseOnFail", "globalCallbacks",
            "screenWidth", "screenHeight", "screenDpi", "gestureMatrix", "description",
            "encrypt", "encryptUserId", "encryptLimitUseTime", "fromStore", "fromShare",
            "lastSaveScreenWidth", "lastSaveScreenHeight", "lastSaveScreenDpi",
            // 我们自己注入的，不算"未知"
            "minVerName", "minVerCode", "count", "savedVerName", "savedVerCode"
        )
        val out = LinkedHashMap<String, Any?>()
        for (k in obj.keys()) {
            if (k !in known) out[k] = obj.opt(k)
        }
        out
    } catch (t: Throwable) {
        LinkedHashMap()
    }
}
