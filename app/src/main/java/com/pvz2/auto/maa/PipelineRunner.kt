package com.pvz2.auto.maa

import android.content.Context
import android.util.Log
import com.pvz2.auto.core.BotState
import com.pvz2.auto.core.ScreenCapturer
import com.pvz2.auto.core.ScriptRunState
import com.pvz2.auto.core.TouchInjector

/**
 * 脚本运行器：把 assets 里的 pipeline JSON 载入并执行。
 *
 * ## 它在整个系统里的位置
 *
 * ```
 * 界面（选脚本、点运行）
 *   └→ AutomationService（前台服务保活 + 持有注入器/采集器）
 *        └→ PipelineRunner（解析 + 选择入口）
 *             └→ NodeGraph（识别 → 动作 的主循环）
 * ```
 *
 * 在这之前 [PipelineRunner] 只在自身声明处出现过一次 —— **整条链是死代码**，
 * 任何脚本都跑不起来。现在是它第一次有真实入口。
 *
 * ## 干跑
 *
 * [dryRun] = true 时只识别不点击，把「本该点哪里」打进日志。
 * 第一次跑一条新链时用它：模板、ROI、坐标任何一处错了都能看出来，
 * 而不会在游戏里乱点。
 */
class PipelineRunner(
    private val context: Context,
    private val capturer: ScreenCapturer,
    private val injector: TouchInjector,
    private val recognizer: Recognizer,
    /** true = 只识别不点击。首次真机验证请保持 true。 */
    var dryRun: Boolean = true
) {

    /**
     * 跑一个脚本目录。
     *
     * @param scriptDir assets 下 `pipeline/` 的子目录名，例如 `Endless_ref`
     * @param entry 入口节点名；null 表示用 [guessEntry] 自动挑
     */
    suspend fun run(scriptDir: String, entry: String? = null) {
        val nodes = loadNodes(context, scriptDir)
        if (nodes.isEmpty()) {
            ScriptRunState.finish("脚本载入失败：0 个节点")
            BotState.setStatus("pipeline 加载失败：0 个节点")
            return
        }

        val start = entry?.takeIf { it.isNotBlank() } ?: guessEntry(nodes)
        if (start == null) {
            ScriptRunState.finish("找不到可执行的入口节点")
            return
        }
        // 手填的入口必须真的存在。不校验的话，打错一个字也会跑到底然后
        // 报"流程结束"，看起来像正常跑完了。
        if (start !in nodes) {
            ScriptRunState.finish("入口节点不存在：$start")
            return
        }

        Log.i(TAG, "载入 ${nodes.size} 个节点，入口 [$start]，dryRun=$dryRun")
        ScriptRunState.begin(scriptDir, start)
        ScriptRunState.update { it.copy(message = "已载入 ${nodes.size} 个节点") }

        val graph = NodeGraph(
            capturer = capturer,
            injector = injector,
            recognizer = recognizer,
            nodes = nodes,
            dryRun = dryRun
        )
        try {
            graph.run(start)
            ScriptRunState.finish(graph.stopReason ?: "流程结束")
        } catch (c: kotlinx.coroutines.CancellationException) {
            // ★ 必须单独接住并重新抛出。
            //
            //   CancellationException 也是 Throwable 的子类，被下面那个
            //   catch (Throwable) 吞掉的话，用户点"停止"会看到
            //   "出错：Job was cancelled" —— 明明是他自己停的，却报成故障。
            //   而且吞掉它会破坏结构化并发：父作用域收不到取消信号。
            ScriptRunState.finish("已停止")
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "脚本运行出错", t)
            ScriptRunState.finish("出错：${t.message}")
        } finally {
            graph.release()
        }
    }

    fun listScripts(): List<ScriptInfo> = listScripts(context)

    /** 一个可用脚本的概要。 */
    data class ScriptInfo(val dir: String, val nodeCount: Int, val entry: String)

    companion object {
        private const val TAG = "PipelineRunner"

        /** `assets/pipeline/` 下每个子目录是一个脚本。 */
        private const val ASSETS_ROOT = "pipeline"

        /**
         * 列出所有可用脚本 —— **不需要构造 PipelineRunner**。
         *
         * 界面只是要一个清单，为这个去建采集器/注入器/识别器没有意义
         * （识别器还要解 800 张模板）。
         */
        fun listScripts(context: Context): List<ScriptInfo> {
            val dirs = runCatching {
                context.assets.list(ASSETS_ROOT).orEmpty()
                    .filter { name ->
                        context.assets.list("$ASSETS_ROOT/$name").orEmpty()
                            .any { it.endsWith(".json", ignoreCase = true) }
                    }
                    .sorted()
            }.getOrDefault(emptyList())

            return dirs.map { dir ->
                val nodes = loadNodes(context, dir)
                ScriptInfo(dir, nodes.size, guessEntry(nodes).orEmpty())
            }
        }

        /**
         * 载入一个脚本目录下**所有** JSON（含子目录）并合并。
         *
         * ## 为什么要递归
         *
         * 自动精灵的脚本按功能分子目录存放：主链在顶层，局内战斗、选植物等
         * 各自一个子目录。只读顶层会丢掉整整一大半节点 ——
         * `Endless_ref` 顶层 66 个、全量 101 个，少的 35 个全在子目录里。
         *
         * 丢节点的后果很隐蔽：[resolveNext] 会把引用不到的节点静默过滤掉，
         * 于是走到那条分支时 next 变成空、流程"正常结束"。
         * 用户看到的是"跑完了"，实际上链在中间断了。
         *
         * 合并顺序 = 路径字典序，**后者覆盖前者**（与 MaaFramework 一致，
         * 也是 MaaPVZ 给文件加 `01_` `02_` 数字前缀的原因）。
         */
        fun loadNodes(context: Context, scriptDir: String): Map<String, NodeDef> {
            val base = "$ASSETS_ROOT/$scriptDir"
            val paths = listJsonPaths(context, base)
            if (paths.isEmpty()) {
                Log.e(TAG, "assets/$base 下没有 pipeline JSON")
                return emptyMap()
            }

            val texts = ArrayList<String>(paths.size)
            for (rel in paths) {
                val text = runCatching {
                    // 必须显式 UTF-8，见 CoordTable 的说明。
                    context.assets.open(rel)
                        .bufferedReader(Charsets.UTF_8)
                        .use { it.readText() }
                }.getOrNull() ?: continue
                texts.add(text)
            }
            Log.i(TAG, "$scriptDir: 载入 ${texts.size} 个 JSON")
            return PipelineParser.parseAll(texts)
        }

        /**
         * 递归列出 assets 目录下所有 `.json` 的相对路径，按路径排序。
         *
         * AssetManager 的 `list()` 只返回一层，判断子目录的办法是
         * "对它再 list 一次，结果非空即目录"。空目录不会被返回，
         * 所以不会无限递归。
         */
        private fun listJsonPaths(context: Context, dir: String): List<String> {
            val out = ArrayList<String>()
            fun walk(current: String) {
                val children = runCatching { context.assets.list(current).orEmpty() }
                    .getOrDefault(emptyArray())
                for (name in children) {
                    val child = "$current/$name"
                    if (name.endsWith(".json", ignoreCase = true)) {
                        out.add(child)
                    } else {
                        // 有子项才是目录；无子项说明是文件（已在上面的分支处理）
                        // 或空目录，两种情况都不需要再走。
                        val grandChildren = runCatching { context.assets.list(child) }
                            .getOrDefault(emptyArray())
                        if (!grandChildren.isNullOrEmpty()) walk(child)
                    }
                }
            }
            walk(dir)
            return out.sorted()
        }

        /**
         * 自动挑入口。
         *
         * 自动精灵的脚本**没有显式 entry 字段** —— 它按"谁没被别人的 next 引用"
         * 推起点。这里照它的推断方式实现：选一个不被任何节点引用的节点。
         *
         * 若有多个（并列多条链时常见），优先名字带「前置」「开始」「Start」的，
         * 再不行取字典序第一个。结果会显示在界面上，用户可以自己改。
         */
        fun guessEntry(nodes: Map<String, NodeDef>): String? {
            if (nodes.isEmpty()) return null
            val referenced = HashSet<String>()
            for (node in nodes.values) {
                // ★ 必须去掉 [JumpBack] 前缀再记。
                //
                //   直接记原始字符串的话，`[JumpBack]X` 不等于 `X`，
                //   于是 X 看上去"没人引用"，会被误判成入口节点。
                //   真实脚本里两处回边指向的都是普通节点，一旦被选成入口，
                //   整个流程会从中间开始跑。
                node.next.forEach { referenced += NodeEdges.strip(it) }
                node.onError.forEach { referenced += NodeEdges.strip(it) }
            }
            val roots = nodes.keys.filter { it !in referenced }.sorted()
            if (roots.isEmpty()) return nodes.keys.sorted().firstOrNull()
            val preferred = roots.firstOrNull { r ->
                r.contains("前置") || r.contains("开始") ||
                    r.contains("Start", ignoreCase = true)
            }
            return preferred ?: roots.firstOrNull()
        }

        /** JVM 端测试用（不需要 Android Context 解析 assets 的场景）。 */
        internal fun parseTexts(texts: List<String>): Map<String, NodeDef> =
            PipelineParser.parseAll(texts)
    }
}
