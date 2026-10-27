package com.pvz2.auto.script

/**
 * 脚本的数据模型。
 *
 * 用 C 的话说：这个文件就是一堆 `struct` 定义，没有任何逻辑。
 * 真正的「读文件填 struct」在 ScriptJson.kt，「消费 struct 干活」在后面的执行器。
 *
 * 结构对照脚本格式：
 *   脚本 = 线性动作列表（可跳转）
 *          每个动作挂 0~N 个条件、4 个监听动作
 *
 * Kotlin 语法提示（你熟 C，这些是差异点）：
 *  · `data class` 自动生成 equals/hashCode/toString/copy —— 等价于 C 里手写比较和打印
 *  · `val` 是只读（类似 const 字段），`var` 是可写
 *  · `String?` 表示「可能是 null」。Kotlin 在类型层面强制你处理 null，
 *    不会像 C 那样空指针悄悄崩 —— 但代价是每处都要决定怎么处理
 *  · `when` 是增强版 switch，可以按类型/取值范围分支（下面 ActionType 的解析就用到）
 */

// ---------------------------------------------------------------------------
// 动作类型
// ---------------------------------------------------------------------------

/**
 * 动作全集。P0 只放这 7 个 —— 少了写不出脚本，多了写不完。
 *
 * 加新动作的完整步骤（以后你自己加）：
 *  1. 这里加一个枚举值
 *  2. ScriptJson 里加名字映射（`wire` 字段已经做了，一般不用改代码）
 *  3. 执行器里加一个 `when` 分支真正去执行
 *
 * `wire` 是 json 里写的字符串。用中文是为了脚本文件对人可读 ——
 * 用户手改 json 时看到 `"type": "种植物"` 比 `"type": "PLANT"` 直观。
 */
enum class ActionType(val wire: String) {
    // 名称要和你定的动作库一一对应
    种植物("种植物"),
    喂能量豆("喂能量豆"),
    铲植物("铲植物"),
    点击("点击"),
    滑动("滑动"),
    跳转("跳转"),
    组合动作("组合动作"),
    ;

    companion object {
        /** 从 json 的字符串找枚举。找不到返回 null，由调用方报错（并列出全部合法值）。 */
        fun fromWire(s: String): ActionType? = entries.firstOrNull { it.wire == s }

        /** 报错信息里用，列出所有合法值。 */
        fun allWire(): String = entries.joinToString("、") { it.wire }
    }
}

// ---------------------------------------------------------------------------
// 条件
// ---------------------------------------------------------------------------

/** 条件类型。P0 两种：识别某个目标、JS 表达式为真。 */
enum class ConditionType(val wire: String) {
    /**
     * 识别。`recognize` 字段填 RecognizeTarget 的 id。
     * 这是你说的「运行条件就选识别结算」—— 结算画面本身就是 targets.json 里的一条。
     */
    识别("识别"),

    /** JS 表达式成立。变量能力走这条。 */
    表达式("表达式"),
    ;

    companion object {
        fun fromWire(s: String): ConditionType? = entries.firstOrNull { it.wire == s }
        fun allWire(): String = entries.joinToString("、") { it.wire }
    }
}

/**
 * 一个条件。挂在动作上，全部满足动作才运行。
 *
 * `invert` 对应「条件反相」——勾上之后「不满足才算满足」。
 * 这一个开关能省掉一半的条件类型：不用为「没出现结算」单独造一种条件。
 */
data class Condition(
    val type: ConditionType,
    /** type=识别 时是 RecognizeTarget.id；type=表达式 时是 JS 源码。 */
    val value: String,
    /** 反相：条件不成立才算满足。 */
    val invert: Boolean = false,
)

// ---------------------------------------------------------------------------
// 动作
// ---------------------------------------------------------------------------

/**
 * 一个动作。
 *
 * `params` 用 `Map<String, Any?>` 而不是给每种动作建一个类，理由：
 * 动作类型会一直加（你说"缺的后面加"），每加一个就建一个类、
 * 就要改 json 解析和执行器，太累。用 map + 下面几个取值辅助函数，
 * 加动作只需要在执行器里读自己关心的键。
 *
 * 代价是没有编译期检查（键名写错要到运行时才发现）。所以下面提供了
 * [string]/[int]/[bool]/[intList] 几个函数，读不到时给你明确的报错信息。
 */
data class Action(
    val type: ActionType,
    /** 列表里显示的说明，不影响运行。 */
    val desc: String = "",
    /** 禁用：运行时直接跳过这一条。 */
    val enabled: Boolean = true,
    /** 动作自己的参数。各类型关心哪些键见执行器。 */
    val params: Map<String, Any?> = emptyMap(),

    /** 执行**前**等待，毫秒，区间随机。null = 不等待。 */
    val preDelayMs: IntRange? = null,
    /** 执行**后**等待，毫秒，区间随机。 */
    val postDelayMs: IntRange? = null,

    /** 重复次数。1 = 只执行一次。 */
    val repeat: Int = 1,
    /** 重复之间的间隔，毫秒。 */
    val repeatIntervalMs: IntRange? = null,

    /** 跳转标识。别的动作用 `跳转 :label` 跳到这里。 */
    val label: String? = null,

    /** 运行条件。全部满足才运行。空 = 无条件运行。 */
    val conditions: List<Condition> = emptyList(),

    /** 子动作。只有 [ActionType.组合动作] 用得到 —— 这就是你说的"放组合里面"。 */
    val children: List<Action> = emptyList(),

    // 四个监听动作，对应「动作开始运行前 / 成功后 / 失败后 / 结束后」。
    // 它们是在**这个动作**之后额外跑的一段动作，不是条件。
    val onBefore: List<Action> = emptyList(),
    val onSuccess: List<Action> = emptyList(),
    val onFail: List<Action> = emptyList(),
    val onAfter: List<Action> = emptyList(),
) {

    // --- params 取值辅助。缺键时抛带上下文的异常，而不是静默给默认值 ---

    /** 取字符串。缺失时如果允许默认值就返回默认值，否则报错并指出是哪个动作。 */
    fun string(key: String, default: String? = null): String {
        val v = params[key]
        if (v == null) {
            if (default != null) return default
            error("动作「${desc.ifEmpty { type.wire }}」缺少参数 $key")
        }
        return v.toString()
    }

    /** 取整数。json 里数字解析成 Double，这里转回 Int。 */
    fun int(key: String, default: Int? = null): Int {
        val v = params[key]
        if (v == null) {
            if (default != null) return default
            error("动作「${desc.ifEmpty { type.wire }}」缺少参数 $key")
        }
        return when (v) {
            is Number -> v.toInt()
            is String -> v.trim().toIntOrNull()
                ?: error("动作「${desc.ifEmpty { type.wire }}」参数 $key 不是整数：$v")
            else -> error("动作「${desc.ifEmpty { type.wire }}」参数 $key 类型不对：$v")
        }
    }

    /** 取布尔。 */
    fun bool(key: String, default: Boolean = false): Boolean {
        val v = params[key] ?: return default
        return when (v) {
            is Boolean -> v
            is String -> v.equals("true", true) || v == "1"
            else -> error("动作「${desc.ifEmpty { type.wire }}」参数 $key 类型不对：$v")
        }
    }

    /** 取整数数组，用于行列网格坐标 `[行, 列]` 或 `[[行,列],...]` 这种。 */
    fun intList(key: String): List<Int> {
        val v = params[key] ?: error("动作「${desc.ifEmpty { type.wire }}」缺少参数 $key")
        val list = v as? List<*> ?: error("动作「${desc.ifEmpty { type.wire }}」参数 $key 不是数组：$v")
        return list.map {
            when (it) {
                is Number -> it.toInt()
                is String -> it.trim().toIntOrNull()
                    ?: error("动作「${desc.ifEmpty { type.wire }}」参数 $key 里有非整数：$it")
                else -> error("动作「${desc.ifEmpty { type.wire }}」参数 $key 里有非法值：$it")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 变量
// ---------------------------------------------------------------------------

/** 变量类型。P0 只做三种基本类型，够表达"打了几局""这次成功没有"。 */
enum class VarType(val wire: String) {
    数值("number"),
    布尔("bool"),
    文本("string"),
    ;

    companion object {
        fun fromWire(s: String): VarType? = entries.firstOrNull { it.wire == s }
        fun allWire(): String = entries.joinToString("/") { it.wire }
    }
}

/** 变量作用域，对应「当前脚本 / 全局」。 */
enum class VarScope(val wire: String) {
    当前脚本("script"),
    全局("global"),
    ;

    companion object {
        fun fromWire(s: String): VarScope? = entries.firstOrNull { it.wire == s }
    }
}

/** 变量声明。`init` 用 Any? 装三种类型之一。 */
data class Variable(
    val name: String,
    val type: VarType,
    val init: Any? = null,
    val scope: VarScope = VarScope.当前脚本,
    val desc: String = "",
)

// ---------------------------------------------------------------------------
// 脚本
// ---------------------------------------------------------------------------

/**
 * 一整份脚本 —— 就是导出/导入的那个 json 文件。
 *
 * `format`/`version` 在读写层校验，不放进数据类：它们是文件格式的元信息，
 * 不属于脚本内容，运行脚本时用不到。
 */
data class Script(
    val name: String = "未命名脚本",
    val desc: String = "",
    val author: String = "",

    /** 整份脚本跑完之后是否从头再来 —— 对应「脚本每次循环后」。 */
    val loopEnabled: Boolean = false,
    /** 每轮循环之间的间隔，毫秒。 */
    val loopIntervalMs: IntRange? = null,

    val variables: List<Variable> = emptyList(),
    val actions: List<Action> = emptyList(),
) {
    /** 调试用：把整棵树打印成可读文本，验证解析结果时看这个。 */
    fun dump(indent: String = ""): String = buildString {
        // ★ 这里必须写 ${indent} 而不是 $indent。
        //   Kotlin 的标识符允许中文字符，所以 "$indent脚本" 会被解析成一个叫
        //   `indent脚本` 的变量（编译期报 Unresolved reference），而不是
        //   「变量 indent 拼上文字 脚本」。凡是变量后面紧跟中文，一律加大括号。
        appendLine("${indent}脚本「$name」" +
            (if (desc.isNotEmpty()) " — $desc" else "") +
            "  动作 ${actions.size} 条" +
            (if (variables.isNotEmpty()) "  变量 ${variables.size} 个" else ""))
        if (loopEnabled) appendLine("$indent  循环: 开, 间隔 ${loopIntervalMs?.let { "${it.first}~${it.last}ms" } ?: "无"}")
        for (v in variables) {
            appendLine("$indent  变量 ${v.name}: ${v.type.wire} = ${v.init}  (${v.scope.wire})")
        }
        for ((i, a) in actions.withIndex()) {
            appendLine(a.dump("$indent  ", i + 1))
        }
    }.trimEnd()

    companion object {
        /** 文件的魔数。导入时先比这个，防止导入别的软件的文件。 */
        const val FORMAT = "pvz2auto-script"
        const val VERSION = 1
    }
}

/** [Action] 的树形打印，缩进递归。 */
fun Action.dump(indent: String, index: Int): String = buildString {
    val tag = if (enabled) "" else " [禁用]"
    val labelTag = label?.let { " {$it}" } ?: ""
    appendLine("$indent$index. ${type.wire}$labelTag$tag" +
        (if (desc.isNotEmpty()) " — $desc" else "") +
        (if (repeat > 1) "  ×$repeat" else ""))
    if (conditions.isNotEmpty()) {
        appendLine("$indent     条件: " + conditions.joinToString("; ") {
            (if (it.invert) "非(" else "") + it.type.wire + ":" + it.value + (if (it.invert) ")" else "")
        })
    }
    if (preDelayMs != null) appendLine("$indent     前延时 ${preDelayMs.first}~${preDelayMs.last}ms")
    if (postDelayMs != null) appendLine("$indent     后延时 ${postDelayMs.first}~${postDelayMs.last}ms")
    if (params.isNotEmpty()) appendLine("$indent     参数 $params")
    for ((i, c) in children.withIndex()) appendLine(c.dump("$indent      ", i + 1))
    for ((name, list) in listOf(
        "运行前" to onBefore, "成功后" to onSuccess,
        "失败后" to onFail, "结束后" to onAfter,
    )) {
        if (list.isEmpty()) continue
        appendLine("$indent     监听[$name]:")
        for ((i, c) in list.withIndex()) appendLine(c.dump("$indent       ", i + 1))
    }
}.trimEnd()
