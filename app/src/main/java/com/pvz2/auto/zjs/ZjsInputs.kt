package com.pvz2.auto.zjs

/**
 * 「设置变量」里标了 `showInput` 的那些变量 —— 点运行时要先问用户。
 *
 * ## 照实现来的
 *
 * `VarValue` 有一组 `showInput*` 字段（FIELDS.md §8.2）：
 *
 * ```
 * showInput            「配置手动输入弹窗」—— 打勾就是"从键盘输入"
 * showInputLabel       弹窗里显示的文字
 * mustInput            必填（默认 true）
 * rememberInputValue   记住上次输入（默认 false）
 * ```
 *
 * 运行时的流程（反编译产物里能直接读到）：
 *  - 点运行 → 每个 showInput 变量生成一个输入框，
 *    预填"上次记住的值"，没有就填脚本里配置的 `valueExp`；
 *  - 用户确认后，按输入生成该变量的值；
 *  - **输入为空且不是必填 → 变量被删除**（`zdjl.VAR_DELETE_MARK`）。
 *
 * 这里只负责"把需要问的变量找出来"，弹窗和取值在界面层做。
 */
object ZjsInputs {

    /** 一个需要用户输入的变量。 */
    data class Item(
        val name: String,
        /** 弹窗里显示的文字（showInputLabel，缺省用变量名） */
        val label: String,
        /** 必填。协议默认 true —— 也就是说默认不给空。 */
        val mustInput: Boolean,
        val remember: Boolean,
        /** 预填值：脚本里配的那个值（纯字面量时才有意义） */
        val defaultValue: String,
        /** 变量说明（varDesc），弹窗里当副标题 */
        val desc: String = "",
        /**
         * 下拉选项（`value.inputOptions`）。
         *
         * 非空 → 运行前弹窗这一行变成**单选下拉**，用户不用手打；
         * 空 → 保持原来的文本框（兼容旧脚本）。
         */
        val options: List<String> = emptyList()
    )

    /** 从整份脚本里收集（含「运行多个动作」的子动作）。 */
    fun collect(script: Z.Script): List<Item> {
        val out = LinkedHashMap<String, Item>()
        fun walk(list: List<Z.Action>) {
            for (a in list) {
                if (a.type == Z.ActionType.SET_VARS) collectFromAction(a, out)
                if (a.scriptSet.isNotEmpty()) walk(a.scriptSet)
            }
        }
        walk(script.actions)
        return out.values.toList()
    }

    /** 读 `value.inputOptions`：支持 JSON 数组，也支持逗号分隔字符串。 */
    private fun optionsOf(v: org.json.JSONObject): List<String> {
        val arr = v.optJSONArray("inputOptions")
        if (arr != null) {
            return (0 until arr.length()).mapNotNull {
                arr.optString(it).trim().takeIf { s -> s.isNotEmpty() }
            }
        }
        val raw = v.optString("inputOptions").trim()
        if (raw.isEmpty()) return emptyList()
        return raw.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun collectFromAction(action: Z.Action, out: MutableMap<String, Item>) {
        val raw = action.extra["vars"] ?: return
        val arr = runCatching {
            when (raw) {
                is org.json.JSONArray -> raw
                is String -> org.json.JSONArray(raw)
                else -> org.json.JSONArray(raw.toString())
            }
        }.getOrNull() ?: return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            if (name.isEmpty()) continue
            val v = o.optJSONObject("value") ?: continue
            if (!v.optBoolean("showInput", false)) continue
            val label = v.optString("showInputLabel").trim().ifBlank { name }
            out[name] = Item(
                name = name,
                label = label,
                // 协议：mustInput 缺省 = true
                mustInput = if (v.has("mustInput")) v.optBoolean("mustInput") else true,
                remember = v.optBoolean("rememberInputValue", false),
                defaultValue = literalOf(v.optString("valueExp")),
                desc = o.optString("varDesc").trim(),
                options = optionsOf(v)
            )
        }
    }

    /** `"abc"` → abc；`42` → 42。求不出来的（表达式）给空串。 */
    private fun literalOf(exp: String): String {
        val t = exp.trim()
        if (t.isEmpty()) return ""
        if (t.length >= 2 && (t.first() == '"' && t.last() == '"' || t.first() == '\'' && t.last() == '\'')) {
            return t.substring(1, t.length - 1)
        }
        return t.toDoubleOrNull()?.let { if (it == it.toLong().toDouble()) it.toLong().toString() else t } ?: ""
    }
}
