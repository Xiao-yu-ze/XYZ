package com.pvz2.auto.script

import org.json.JSONArray
import org.json.JSONObject

/**
 * 脚本 json 的读写。**纯字符串 ↔ 对象转换，不碰文件和 Android**。
 *
 * 为什么刻意不碰文件：这样它可以在 PC 上跑（JVM 单测），不需要模拟器。
 * 真正读写文件的代码放在 UI/Service 层，那边才需要 Context。
 *
 * 解析策略和 TargetCatalog 一致：**收集所有问题一起报**，不静默跳过。
 * 手写的 json 一条写错，静默跳过会变成"脚本跑一半就不动了"，极难查。
 */
object ScriptJson {

    // =======================================================================
    // 读：文本 -> Script
    // =======================================================================

    fun parse(text: String): Script {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            error("脚本不是合法的 JSON：${e.message}")
        }

        // 先认格式，防止把别的软件的文件拖进来
        val format = root.optString("format", "")
        if (format != Script.FORMAT) {
            error("不是本程序的脚本文件：format=\"$format\"，应为 \"${Script.FORMAT}\"")
        }
        val version = root.optInt("version", -1)
        if (version != Script.VERSION) {
            error("脚本版本不支持：$version，本程序支持 ${Script.VERSION}")
        }

        val problems = mutableListOf<String>()

        val variables = root.optJSONArray("variables")?.let { parseVariables(it, problems) }
            ?: emptyList()

        val actionsArray = root.optJSONArray("actions")
            ?: error("脚本缺少 actions 数组")

        val actions = parseActions(actionsArray, "actions", problems)

        if (problems.isNotEmpty()) {
            error("脚本有 ${problems.size} 处问题：\n" + problems.joinToString("\n") { "  · $it" })
        }

        return Script(
            name = root.optString("name", "未命名脚本"),
            desc = root.optString("desc", ""),
            author = root.optString("author", ""),
            loopEnabled = root.optBoolean("loopEnabled", false),
            loopIntervalMs = parseDelay(root.opt("loopIntervalMs"), "loopIntervalMs", problems),
            variables = variables,
            actions = actions,
        )
    }

    private fun parseVariables(array: JSONArray, problems: MutableList<String>): List<Variable> {
        val out = ArrayList<Variable>(array.length())
        val seen = HashSet<String>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i)
            if (o == null) { problems += "variables[$i] 不是对象"; continue }

            val name = o.optString("name", "").trim()
            if (name.isEmpty()) { problems += "variables[$i]: name 不能为空"; continue }
            if (!seen.add(name)) { problems += "variables[$i]: 变量名 $name 重复"; continue }

            val typeRaw = o.optString("type", "").trim()
            val type = VarType.fromWire(typeRaw)
            if (type == null) {
                problems += "variables[$i]($name): type=\"$typeRaw\" 不认识，可选 ${VarType.allWire()}"
                continue
            }

            val scopeRaw = o.optString("scope", VarScope.当前脚本.wire)
            val scope = VarScope.fromWire(scopeRaw)
            if (scope == null) {
                problems += "variables[$i]($name): scope=\"$scopeRaw\" 不认识，可选 script/global"
                continue
            }

            // 初始化值要和声明类型对得上，否则后面算表达式会莫名其妙
            val initRaw = toKotlin(o.opt("init"))
            val init = when (type) {
                VarType.数值 -> (initRaw as? Number)?.toDouble()
                    ?: if (initRaw == null) 0.0 else null
                VarType.布尔 -> initRaw as? Boolean
                    ?: if (initRaw == null) false else null
                VarType.文本 -> initRaw?.toString()
            }
            if (init == null) {
                problems += "variables[$i]($name): init 和 type=${type.wire} 不匹配：$initRaw"
                continue
            }

            out += Variable(name, type, init, scope, o.optString("desc", ""))
        }
        return out
    }

    /**
     * 解析动作数组。`where` 用于报错定位，递归时会带上路径，
     * 例如 `actions[2].children[0]`，这样深层写错也能一眼找到。
     */
    private fun parseActions(
        array: JSONArray,
        where: String,
        problems: MutableList<String>,
    ): List<Action> {
        val out = ArrayList<Action>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i)
            if (o == null) { problems += "$where[$i] 不是对象"; continue }
            val act = parseAction(o, "$where[$i]", problems) ?: continue
            out += act
        }
        return out
    }

    private fun parseAction(
        o: JSONObject,
        where: String,
        problems: MutableList<String>,
    ): Action? {
        val typeRaw = o.optString("type", "").trim()
        val type = ActionType.fromWire(typeRaw)
        if (type == null) {
            problems += "$where: type=\"$typeRaw\" 不认识，可选 ${ActionType.allWire()}"
            return null
        }
        // 报错时带上动作描述，比只有下标好认
        val desc = o.optString("desc", "")
        val tag = "$where(${desc.ifEmpty { typeRaw }})"

        val before = problems.size

        val repeat = o.optInt("repeat", 1)
        if (repeat < 1) problems += "$tag: repeat 必须 >= 1"

        val conditions = o.optJSONArray("conditions")?.let { arr ->
            val list = ArrayList<Condition>(arr.length())
            for (j in 0 until arr.length()) {
                val c = arr.optJSONObject(j)
                if (c == null) { problems += "$tag.conditions[$j] 不是对象"; continue }
                val cTypeRaw = c.optString("type", "").trim()
                val cType = ConditionType.fromWire(cTypeRaw)
                if (cType == null) {
                    problems += "$tag.conditions[$j]: type=\"$cTypeRaw\" 不认识，可选 ${ConditionType.allWire()}"
                    continue
                }
                val value = c.optString("value", "").trim()
                if (value.isEmpty()) {
                    problems += "$tag.conditions[$j]: value 不能为空"
                    continue
                }
                list += Condition(cType, value, c.optBoolean("invert", false))
            }
            list
        } ?: emptyList()

        // 组合动作必须有子动作，否则这条动作没有任何意义
        val children = o.optJSONArray("children")?.let {
            parseActions(it, "$tag.children", problems)
        } ?: emptyList()
        if (type == ActionType.组合动作 && children.isEmpty()) {
            problems += "$tag: 组合动作必须至少有一个 children"
        }

        val onBefore = o.optJSONArray("onBefore")?.let { parseActions(it, "$tag.onBefore", problems) } ?: emptyList()
        val onSuccess = o.optJSONArray("onSuccess")?.let { parseActions(it, "$tag.onSuccess", problems) } ?: emptyList()
        val onFail = o.optJSONArray("onFail")?.let { parseActions(it, "$tag.onFail", problems) } ?: emptyList()
        val onAfter = o.optJSONArray("onAfter")?.let { parseActions(it, "$tag.onAfter", problems) } ?: emptyList()

        val preDelay = parseDelay(o.opt("preDelayMs"), "$tag.preDelayMs", problems)
        val postDelay = parseDelay(o.opt("postDelayMs"), "$tag.postDelayMs", problems)
        val repeatInterval = parseDelay(o.opt("repeatIntervalMs"), "$tag.repeatIntervalMs", problems)

        val params = when (val p = o.opt("params")) {
            null -> emptyMap()
            is JSONObject -> jsonObjectToMap(p)
            else -> {
                problems += "$tag: params 应该是对象，实际是 ${p::class.simpleName}"
                emptyMap()
            }
        }

        // 本层新增了问题就不返回这个动作（但已解析的子问题仍会被收集到）
        if (problems.size != before) return null

        return Action(
            type = type,
            desc = desc,
            enabled = o.optBoolean("enabled", true),
            params = params,
            preDelayMs = preDelay,
            postDelayMs = postDelay,
            repeat = repeat,
            repeatIntervalMs = repeatInterval,
            label = o.optString("label", "").ifEmpty { null },
            conditions = conditions,
            children = children,
            onBefore = onBefore,
            onSuccess = onSuccess,
            onFail = onFail,
            onAfter = onAfter,
        )
    }

    /**
     * 延时字段。允许两种写法：
     *   `300`        -> 固定 300ms
     *   `[200, 500]` -> 区间随机
     * 统一成 IntRange，执行器只需要处理一种形式。
     */
    private fun parseDelay(v: Any?, where: String, problems: MutableList<String>): IntRange? {
        if (v == null || v == JSONObject.NULL) return null
        if (v is Number) {
            val n = v.toInt()
            if (n < 0) { problems += "$where: 不能为负"; return null }
            return n..n
        }
        if (v is JSONArray) {
            if (v.length() != 2) { problems += "$where: 数组必须是 [最小, 最大]"; return null }
            val a = v.optInt(0, -1)
            val b = v.optInt(1, -1)
            if (a < 0 || b < 0) { problems += "$where: 必须是非负整数"; return null }
            if (a > b) { problems += "$where: 最小值 $a 大于最大值 $b"; return null }
            return a..b
        }
        problems += "$where: 应该是数字或 [最小,最大]，实际是 ${v::class.simpleName}"
        return null
    }

    // =======================================================================
    // 写：Script -> 文本
    // =======================================================================

    /** 导出成 json 文本。缩进 2，方便人看，也方便直接比对 diff。 */
    fun toJson(script: Script): String = toJsonObject(script).toString(2)

    private fun toJsonObject(s: Script): JSONObject = JSONObject().apply {
        put("format", Script.FORMAT)
        put("version", Script.VERSION)
        put("name", s.name)
        if (s.desc.isNotEmpty()) put("desc", s.desc)
        if (s.author.isNotEmpty()) put("author", s.author)
        put("loopEnabled", s.loopEnabled)
        s.loopIntervalMs?.let { put("loopIntervalMs", delayToJson(it)) }
        put("variables", JSONArray().apply {
            for (v in s.variables) {
                put(JSONObject().apply {
                    put("name", v.name)
                    put("type", v.type.wire)
                    put("init", v.init ?: JSONObject.NULL)
                    put("scope", v.scope.wire)
                    if (v.desc.isNotEmpty()) put("desc", v.desc)
                })
            }
        })
        put("actions", actionsToJson(s.actions))
    }

    private fun actionsToJson(list: List<Action>): JSONArray = JSONArray().apply {
        for (a in list) put(actionToJson(a))
    }

    private fun actionToJson(a: Action): JSONObject = JSONObject().apply {
        put("type", a.type.wire)
        if (a.desc.isNotEmpty()) put("desc", a.desc)
        // 只在非默认值时写出，导出的文件才不会到处是 enabled=true / repeat=1
        if (!a.enabled) put("enabled", false)
        a.preDelayMs?.let { put("preDelayMs", delayToJson(it)) }
        a.postDelayMs?.let { put("postDelayMs", delayToJson(it)) }
        if (a.repeat != 1) put("repeat", a.repeat)
        a.repeatIntervalMs?.let { put("repeatIntervalMs", delayToJson(it)) }
        a.label?.let { put("label", it) }
        if (a.params.isNotEmpty()) put("params", kotlinToJson(a.params))
        if (a.conditions.isNotEmpty()) {
            put("conditions", JSONArray().apply {
                for (c in a.conditions) put(JSONObject().apply {
                    put("type", c.type.wire)
                    put("value", c.value)
                    if (c.invert) put("invert", true)
                })
            })
        }
        if (a.children.isNotEmpty()) put("children", actionsToJson(a.children))
        if (a.onBefore.isNotEmpty()) put("onBefore", actionsToJson(a.onBefore))
        if (a.onSuccess.isNotEmpty()) put("onSuccess", actionsToJson(a.onSuccess))
        if (a.onFail.isNotEmpty()) put("onFail", actionsToJson(a.onFail))
        if (a.onAfter.isNotEmpty()) put("onAfter", actionsToJson(a.onAfter))
    }

    /** 固定值写成一个数字，区间写成 `[min,max]`。 */
    private fun delayToJson(r: IntRange): Any =
        if (r.first == r.last) r.first else JSONArray().put(r.first).put(r.last)

    // =======================================================================
    // 通用值转换：org.json 的类型 -> Kotlin 原生类型
    // =======================================================================
    //
    // 为什么要转：org.json 里 JSONObject.NULL 是个特殊对象，不是 Kotlin 的 null；
    // 数字统一是 Integer/Double。不转的话后面 when 分支处理起来到处是陷阱。

    private fun toKotlin(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is JSONArray -> List(v.length()) { toKotlin(v.opt(it)) }
        is JSONObject -> jsonObjectToMap(v)
        is Number -> if (v.toDouble() == v.toInt().toDouble()) v.toInt() else v.toDouble()
        else -> v
    }

    /**
     * JSONObject -> Map。
     *
     * 单独抽出来是因为：写成 `toKotlin(o) as Map<String, Any?>` 会触发
     * "Unchecked cast" 警告 —— 编译器无法从 Any? 推断出泛型参数。
     * 明确构造一个 Map 就没有这个问题，也顺便让类型一眼可见。
     */
    private fun jsonObjectToMap(o: JSONObject): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>(o.length())
        for (k in o.keys()) m[k] = toKotlin(o.opt(k))
        return m
    }

    private fun kotlinToJson(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().apply {
            for ((k, value) in v) put(k.toString(), kotlinToJson(value))
        }
        is List<*> -> JSONArray().apply { for (item in v) put(kotlinToJson(item)) }
        else -> v
    }
}
