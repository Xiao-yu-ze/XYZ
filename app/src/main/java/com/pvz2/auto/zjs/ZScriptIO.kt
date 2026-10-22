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

            // ★ 走真实的 .zjs 格式（NDJSON + CRLF），不是单个 JSON 对象。
            //
            //   以前这里 `JSONObject(text)` —— 拿真的 .zjs 喂进来直接抛
            //   JSONException，用户看到"读不出 xxx.zjs"，而真实原因是我们
            //   只认自己编的容器格式。
            val result = ZjsFileFormat.parse(text)
            if (result.error != null) return Loaded(error = result.error, path = path)

            val script = result.script!!
            if (result.badLines.isNotEmpty()) {
                Log.w(TAG, "$path 有 ${result.badLines.size} 行解析失败：${result.badLines}")
            }
            if (result.linkedFiles.isNotEmpty()) {
                Log.i(TAG, "$path 含 ${result.linkedFiles.size} 个内嵌子脚本（暂不解析，已保留）")
            }
            Log.i(TAG, "已读取 $path：${script.actions.size} 个动作，${result.badLines.size} 行失败")
            Loaded(script = script, path = path)
        } catch (t: Throwable) {
            Log.e(TAG, "读取失败 $path", t)
            Loaded(error = "${t.javaClass.simpleName}: ${t.message}", path = path)
        }
    }

    /**
     * 写回脚本，格式为真实的 `.zjs`（NDJSON + CRLF + baseInfo 注入字段）。
     *
     * ## 以前为什么不对
     *
     * 原来是 `script.toJson().toString(2)` —— 一个缩进过的**单个** JSON 对象。
     * 那个文件自动精灵**打不开**：它期望 NDJSON，第 1 行 baseInfo、
     * 之后每行一个动作，且 baseInfo 里要有 minVerName/count 等字段。
     *
     * 当时没暴露是因为 save() 从没被调用过（编辑只在内存里）。
     * 一旦接上保存按钮，用户拿到的就是坏文件。
     *
     * @param screenWidth / screenHeight / screenDpi 保存时的屏幕参数，
     *        自动精灵靠它判断坐标是否需要换算
     */
    fun save(
        script: Z.Script,
        path: String,
        screenWidth: Int = 0,
        screenHeight: Int = 0,
        screenDpi: Float = 0f
    ): Boolean = try {
        val file = File(path)
        file.parentFile?.mkdirs()
        // ★ UTF-8 无 BOM。writeText 默认就是 UTF-8 且不带 BOM ——
        //   不能手工加 BOM，自动精灵会读不出来。
        file.writeText(ZjsFileFormat.stringify(script, screenWidth, screenHeight, screenDpi))
        Log.i(TAG, "已保存 $path：${script.actions.size} 个动作（${file.length()} 字节）")
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
