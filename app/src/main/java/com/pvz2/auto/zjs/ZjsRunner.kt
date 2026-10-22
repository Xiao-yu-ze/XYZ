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

    // ------------------------------------------------------------ 用户保存的脚本

    /** 用户保存的脚本目录。和 assets 里的示例分开，用户改不到只读资源。 */
    fun savedDir(context: Context): java.io.File =
        java.io.File(context.getExternalFilesDir(null), "zjs").apply { mkdirs() }

    /** 一个用户保存的脚本。 */
    data class Saved(
        val file: java.io.File,
        /** 不带扩展名的名字，界面上显示这个 —— ".zjs" 对我们自己没意义。 */
        val name: String,
        val actionCount: Int,
        val bytes: Long,
        val modified: Long
    )

    /**
     * 列出用户保存的所有脚本，最近改过的排前面。
     *
     * ## 为什么要读动作数而不是只列文件名
     *
     * 用户区分脚本靠的是"哪个是我刚做的那条"，而文件名往往很接近
     * （脚本1、脚本2…）。动作数和修改时间才是他真正能认出来的信息。
     *
     * 单个文件解析失败只跳过它自己 —— 一个坏脚本不该让整页空白，
     * 但要在日志里留下痕迹，否则用户只会看到"我存的脚本不见了"。
     */
    fun listSaved(context: Context): List<Saved> {
        val dir = savedDir(context)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".zjs") }.orEmpty()
        return files.mapNotNull { f ->
            runCatching {
                val text = f.readText()
                // ★ 必须用 ZjsFileFormat：.zjs 是 NDJSON（第一行 baseInfo，
                //   之后一行一个动作），拿 JSONObject 整段解析会直接失败。
                //   失败时如果被吞掉，列表里就会出现"0 个动作"的鬼条目。
                val parsed = ZjsFileFormat.parse(text)
                val script = parsed.script ?: error("解析失败：${parsed.error ?: "未知"}")
                Saved(
                    file = f,
                    name = f.name.removeSuffix(".zjs"),
                    actionCount = script.actions.size,
                    bytes = f.length(),
                    modified = f.lastModified()
                )
            }.onFailure { Log.w(TAG, "解析 ${f.name} 失败", it) }.getOrNull()
        }.sortedByDescending { it.modified }
    }

    /** 存一份脚本到已保存目录。 */
    fun saveSaved(file: java.io.File, script: Z.Script): Boolean =
        ZScriptIO.save(script, file.absolutePath)

    /** 直接读一个已保存的文件（不经过 assets）。 */
    fun loadSaved(file: java.io.File): Z.Script? = runCatching {
        // 同上：NDJSON，必须走 ZjsFileFormat
        val parsed = ZjsFileFormat.parse(file.readText())
        parsed.script ?: error(parsed.error ?: "解析失败")
    }.onFailure { Log.e(TAG, "读 ${file.name} 失败", it) }.getOrNull()
}
