package com.pvz2.auto.zjs

import android.content.Context
import android.util.Log

/**
 * 从 assets 里读自动精灵格式的脚本。
 *
 * ## 为什么和 MaaFramework 的脚本分开
 *
 * 两套格式完全不通：MaaFramework 一个节点一个 JSON 文件、靠 `next` 连边；
 * 自动精灵一个脚本一个 JSON、是一串线性动作。混在一起读写会让两边的
 * 解析器都变得难懂，而且用户的脚本是哪种格式，界面上一眼就该看出来。
 *
 * 所以：`assets/pipeline/` 是 MaaFramework，`assets/zjs/` 是自动精灵。
 */
object ZjsRunner {

    private const val TAG = "ZjsRunner"

    /** 一个可加载的脚本。 */
    data class Entry(
        val fileName: String,
        val actionCount: Int,
        val description: String
    )

    /**
     * 列出 assets/zjs 下所有脚本。
     *
     * 单个文件解析失败只跳过它自己 —— 一个坏脚本不该让整页空白。
     */
    fun listScripts(context: Context): List<Entry> {
        val assets = context.assets
        val names = runCatching { assets.list("zjs")?.toList().orEmpty() }
            .onFailure { Log.e(TAG, "列目录失败", it) }
            .getOrDefault(emptyList())

        return names.filter { it.endsWith(".json") }.mapNotNull { name ->
            runCatching {
                val text = assets.open("zjs/$name").bufferedReader().use { it.readText() }
                val script = Z.Script.parse(org.json.JSONObject(text))
                Entry(
                    fileName = name,
                    actionCount = script.actions.size,
                    description = script.baseInfo.description.orEmpty()
                )
            }.onFailure { Log.w(TAG, "解析 $name 失败", it) }.getOrNull()
        }
    }

    /** 读一个脚本的完整内容。失败返回 null 并把原因打日志。 */
    fun loadScript(context: Context, fileName: String): Z.Script? = runCatching {
        val text = context.assets.open("zjs/$fileName").bufferedReader().use { it.readText() }
        Z.Script.parse(org.json.JSONObject(text))
    }.onFailure { Log.e(TAG, "读 $fileName 失败", it) }.getOrNull()
}
