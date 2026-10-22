package com.pvz2.auto.zjs

import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * 自动精灵脚本的读写。
 *
 * ## 为什么单独一个类
 *
 * 解析逻辑在 [Z]，这里只管"文件 ↔ 模型"。分开的好处是解析可以单测
 * （不需要 Android 的文件系统），而读写路径上的错误可以集中处理。
 *
 * ## 写回时必须保留未知字段
 *
 * [Z.Script.toJson] 会把 `extra` 里我们没认识的字段原样写回去。这不是可选项 ——
 * 用户导入的脚本来自 自动精灵，带 Windows 专用字段、加密字段、新版本字段。
 * 丢掉它们等于把用户的脚本改坏，而且要到导出之后、在别的程序里才发现。
 */
object ZScriptIO {

    private const val TAG = "ZScriptIO"

    /** 读取结果。失败时 [error] 非空，调用方据此给用户看原因。 */
    data class Loaded(
        val script: Z.Script? = null,
        val error: String? = null,
        val path: String = ""
    )

    /**
     * 读一个脚本文件。
     *
     * 单个动作解析失败**不会**让整个脚本失败 —— 跳过那一条并记日志。
     * 理由：一个坏动作不该让用户看不到其余 200 个正常节点，
     * 但也不能静默，否则用户会以为脚本本来就少。
     */
    fun load(path: String): Loaded {
        return try {
            val file = File(path)
            if (!file.isFile) return Loaded(error = "文件不存在：$path", path = path)
            val text = file.readText()
            if (text.isBlank()) return Loaded(error = "文件是空的", path = path)

            val json = JSONObject(text)
            val script = Z.Script.parse(json)
            Log.i(TAG, "已读取 $path：${script.actions.size} 个动作")
            Loaded(script = script, path = path)
        } catch (t: Throwable) {
            Log.e(TAG, "读取失败 $path", t)
            Loaded(error = "${t.javaClass.simpleName}: ${t.message}", path = path)
        }
    }

    /**
     * 写回脚本。
     *
     * 用 `toString(2)` 缩进 —— 自动精灵自己也这么存，方便用户 diff
     * 和手工检查。压成一行省几 KB 不值得。
     */
    fun save(script: Z.Script, path: String): Boolean = try {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(script.toJson().toString(2))
        Log.i(TAG, "已保存 $path：${script.actions.size} 个动作")
        true
    } catch (t: Throwable) {
        Log.e(TAG, "保存失败 $path", t)
        false
    }

    /**
     * 检查脚本，返回给用户看的问题列表。
     *
     * 只报告**能确定**的问题（比如缺坐标），不猜语义。
     */
    fun validate(script: Z.Script): List<String> {
        val issues = ArrayList<String>()
        if (script.actions.isEmpty()) issues.add("脚本里没有动作")

        script.actions.forEachIndexed { i, action ->
            val where = "第 ${i + 1} 个（${actionLabel(action)}）"
            when (action.type) {
                Z.ActionType.CLICK, Z.ActionType.CLICK_IMAGE,
                Z.ActionType.CLICK_TEXT, Z.ActionType.CLICK_COLOR,
                Z.ActionType.CLICK_NODE -> {
                    if (action.posData == null) issues.add("$where 缺位置")
                }

                Z.ActionType.SWIPE -> {
                    if (action.startPos == null) issues.add("$where 缺起点")
                    if (action.endPos == null) issues.add("$where 缺终点")
                }

                else -> Unit
            }
            if (!action.type.executable) {
                issues.add("$where 安卓上暂不支持")
            }
        }
        return issues
    }

    /** 动作的简短标签，用于列表和问题提示。 */
    fun actionLabel(action: Z.Action): String {
        action.common.desc?.takeIf { it.isNotBlank() }?.let { return it }
        val t = action.type
        return t.controlRunType?.let { "${t.wire}·$it" } ?: t.wire
    }
}
