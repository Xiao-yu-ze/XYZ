package com.pvz2.auto.maa

import android.content.Context
import android.util.Log
import com.pvz2.auto.core.BotState
import com.pvz2.auto.core.ScreenCapturer
import com.pvz2.auto.core.TouchInjector

/**
 * ============================================================================
 * 无尽挑战「声明式子集」运行器
 * ============================================================================
 *
 * 这是方案 C 的实现：**只跑 pipeline 里纯声明式的节点链，不碰任何 Custom 动作**。
 *
 * 目的不是打关，而是**回答一个我们至今没有答案的问题**：
 * 模板匹配和 OCR 在真机上到底准不准。识别层是整个自动化的地基 ——
 * 如果分数普遍 0.9+、OCR 能认对「继续挑战」，那么把 MaaPVZ 的引擎
 * （JobSetLoad / JobSetFight / BatchSwipe）移植过来才有意义；
 * 如果识别本身就飘，移植 5000 行引擎是白费。
 *
 * ── 为什么是这条链 ────────────────────────────────────────────────────────
 *
 * 入口 `无尽挑战_前置检查_ref` 之后的第一步就是 `JobSetLoad`（Custom 动作），
 * 我们跑不了。所以这里不从头跑，而是**从识别链的中段切入**：
 *
 *     无尽挑战_点击竞技        TemplateMatch: create_yard/竞技.png
 *       └→ 无尽挑战_点击无尽挑战  TemplateMatch: Endless/无尽挑战.png
 *            └→ 无尽挑战_点击开始挑战  OCR: 开始挑战 / 继续挑战
 *
 * 这三个节点覆盖了**三种识别通道**（模板匹配 / 多模板 / OCR + 紧 ROI），
 * 且全部是声明式的，不依赖任何 Custom 动作。点击行为也是真实的
 * （走同一个 TouchInjector），所以「识别 → 坐标换算 → 点击」整条链路都会被验证。
 *
 * ── 干跑模式 ──────────────────────────────────────────────────────────────
 *
 * [dryRun] = true 时**只识别不点击**。日志会打印「本该点哪里」，
 * 这样即使模板分数不对、坐标算错，也不会误触游戏。第一次上真机务必用它。
 *
 * 必须运行在 `Dispatchers.IO` 协程里：截图和注入都是阻塞调用。
 */
class PipelineRunner(
    private val context: Context,
    private val capturer: ScreenCapturer,
    private val injector: TouchInjector,
    private val recognizer: Recognizer,
    /** true = 只识别不点击，把决策打日志。首次真机验证请保持 true。 */
    var dryRun: Boolean = true
) {

    /** 跑一条链。[entry] 是起始节点名。 */
    suspend fun run(entry: String) {
        val nodes = loadNodes()
        if (nodes.isEmpty()) {
            BotState.setStatus("pipeline 加载失败：0 个节点")
            return
        }
        Log.i(TAG, "pipeline 载入 ${nodes.size} 个节点，入口 [$entry]，dryRun=$dryRun")

        val graph = NodeGraph(
            capturer = capturer,
            injector = injector,
            recognizer = recognizer,
            nodes = nodes,
            dryRun = dryRun
        )
        try {
            graph.run(entry)
        } finally {
            graph.release()
        }
    }

    /**
     * 载入 `assets/pipeline/Endless_ref/` 下所有 JSON 并合并。
     *
     * 合并顺序 = 文件名排序（01_ → 02_ → …），**后者覆盖前者**。
     * 这与 MaaFramework 的加载语义一致，也是 MaaPVZ 给文件加数字前缀的原因。
     */
    private fun loadNodes(): Map<String, NodeDef> {
        val files = runCatching {
            context.assets.list(ASSETS_DIR).orEmpty()
                .filter { it.endsWith(".json", ignoreCase = true) }
                .sorted()
        }.getOrDefault(emptyList())

        if (files.isEmpty()) {
            Log.e(TAG, "assets/$ASSETS_DIR 下没有 pipeline JSON —— " +
                "请先运行 tools/sync-maapvz-assets.ps1 同步素材")
            return emptyMap()
        }

        val texts = ArrayList<String>(files.size)
        for (name in files) {
            val text = runCatching {
                context.assets.open("$ASSETS_DIR/$name")
                    .bufferedReader(Charsets.UTF_8)     // 必须显式 UTF-8，见 CoordTable 的说明
                    .use { it.readText() }
            }.getOrNull() ?: continue
            texts.add(text)
            Log.i(TAG, "载入 $name（${text.length} 字符）")
        }
        return PipelineParser.parseAll(texts)
    }

    private companion object {
        const val TAG = "PipelineRunner"

        /**
         * 只同步了 Endless_ref 一棵树（见 tools/sync-maapvz-assets.ps1）。
         * 根目录下的 JSON 是主链，子目录（03_Endless_fight 等）是 Custom 动作的宿主，
         * 这里不递归 —— 那些节点我们不跑。
         */
        const val ASSETS_DIR = "pipeline/Endless_ref"
    }
}

/** 方案 C 默认跑的链。三条都是纯声明式节点，覆盖模板匹配 / 多模板 / OCR。 */
object VerifyChain {

    /** 从这里开始。它自己会决定往哪走（竞技 → 无尽挑战 → 开始挑战）。 */
    const val ENTRY = "无尽挑战_点击竞技"

    /**
     * 用于识别自检的模板清单 —— 全是这条链上真正会用到的图。
     * 和 [ProbeTargets] 不同：那些是无尽挑战局内的图，这些是**入口界面**的图。
     */
    // 原 TEMPLATES 常量已删除：没有任何调用方，且路径带的是已废弃的
    // `maapvz/` 前缀（现役资源在 res/raw 下按 NodeDef 的 id 索引）。
    // 留着只会让后来的人以为它还在用，照着它的格式写资源路径必踩。
}
