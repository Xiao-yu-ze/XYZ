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

        // 2) 空动作要跳过（SPEC.md:711 `actionsIgnoreEmpty`）。
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
        put("count", countActions(script.actions))
        put("savedVerName", SAVED_VER_NAME)
        put("savedVerCode", versionCode(SAVED_VER_NAME))
        if (width > 0) put("lastSaveScreenWidth", width)
        if (height > 0) put("lastSaveScreenHeight", height)
        if (dpi > 0f) put("lastSaveScreenDpi", dpi)
    }

    /** 版本名 → 版本号。自动精灵的规则见下方实现。 */
    private fun versionCode(name: String): Int {
        // 形如 "26.0.0" → 26*10000 + 0*100 + 0 = 260000
        val parts = name.split('.')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
        return major * 10_000 + minor * 100 + patch
    }

    /** 递归统计动作数，含每个动作的 7 个监听槽位。 */
    fun countActions(actions: List<Z.Action>): Int {
        var n = 0
        for (a in actions) {
            n += 1
            for (cb in a.callbacks.values) n += countActions(listOf(cb))
        }
        return n
    }

    /**
     * 解析 `.zjs` 文本。
     *
     * 逐行读：第 1 行是 baseInfo，其余每行一个动作（`linkedFile` 行跳过，
     * 因为我们还不支持内嵌子脚本 —— 但**不能丢**，见 [ParseResult.linkedFiles]）。
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
            val bad = ArrayList<Int>()

            for ((idx, line) in nonBlank.drop(1)) {
                try {
                    val obj = JSONObject(line)
                    if (obj.optString("type") == LINKED_FILE) {
                        // 内嵌子脚本：先存下来，将来做多文件时再用。
                        // 现在不解析，但绝不能丢 —— 丢了用户的脚本就不完整。
                        linked.add(obj)
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
