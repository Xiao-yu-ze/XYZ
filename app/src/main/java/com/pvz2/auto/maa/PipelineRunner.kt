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
            ScriptRunState.finish("流程结束")
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

        /** 载入一个脚本目录下所有 JSON 并合并。后者覆盖前者（文件名排序）。 */
        fun loadNodes(context: Context, scriptDir: String): Map<String, NodeDef> {
            val base = "$ASSETS_ROOT/$scriptDir"
            val files = runCatching {
                context.assets.list(base).orEmpty()
                    .filter { it.endsWith(".json", ignoreCase = true) }
                    .sorted()
            }.getOrDefault(emptyList())

            if (files.isEmpty()) {
                Log.e(TAG, "assets/$base 下没有 pipeline JSON")
                return emptyMap()
            }

            val texts = ArrayList<String>(files.size)
            for (name in files) {
                val text = runCatching {
                    // 必须显式 UTF-8，见 CoordTable 的说明。
                    context.assets.open("$base/$name")
                        .bufferedReader(Charsets.UTF_8)
                        .use { it.readText() }
                }.getOrNull() ?: continue
                texts.add(text)
            }
            return PipelineParser.parseAll(texts)
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
                referenced += node.next
                referenced += node.onError
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
