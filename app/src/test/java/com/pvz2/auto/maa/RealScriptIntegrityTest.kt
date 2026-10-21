package com.pvz2.auto.maa

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 用**真实的** 自动精灵 脚本做端到端解析校验。
 *
 * ## 为什么这个测试比其余的更重要
 *
 * 前面那些测试用的是我手写的小 JSON。真实脚本有 100+ 节点、跨文件引用、
 * 回边、`enabled=false` 等等，只有拿它们跑一遍才能发现
 * "解析器能吃下我的样例，但吃不下用户的脚本"。
 *
 * 这里查的是最要命的一类问题：**断链**。
 * `next` 指向一个不存在的节点时，`resolveNext` 会把它静默过滤掉 ——
 * 运行时表现是"走到这里就正常结束了"，日志里没有任何异常。
 * 之前 `loadNodes` 不递归导致 101 个节点只加载了 66 个，就是这个症状。
 *
 * 素材目录由 `tools/sync-maapvz-assets.ps1` 同步，没同步时自动跳过。
 */
class RealScriptIntegrityTest {

    /**
     * 素材里确实缺的节点 —— **不是引擎的问题，是同步进来的脚本集不完整**。
     *
     * 这三处引用在 MaaPVZ 上游属于其它脚本目录，`sync-maapvz-assets.ps1`
     * 只拉了无尽挑战那一套，所以定义没进来。
     *
     * 允许它们存在，但**运行时会在日志里显式报出来**（NodeGraph.resolveNext），
     * 不会像以前那样静默把边丢掉 —— 静默才是真正的问题。
     */
    private val KNOWN_MISSING_FROM_SYNC = setOf(
        "让我看看我在不在开始战斗界面奥",
        "检查当前是否在主界面",
        "通用_重开_暂停"
    )

    private fun pipelineDir(): File? {
        // 单测的工作目录是模块根（app/）
        val candidates = listOf(
            File("src/main/assets/pipeline"),
            File("app/src/main/assets/pipeline")
        )
        return candidates.firstOrNull { it.isDirectory }
    }

    private fun loadAll(dir: File): Map<String, NodeDef> {
        val texts = ArrayList<String>()
        dir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".json", ignoreCase = true) }
            .sortedBy { it.path }
            .forEach { texts.add(it.readText(Charsets.UTF_8)) }
        return PipelineParser.parseAll(texts)
    }

    private fun scripts(dir: File): List<File> =
        dir.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: emptyList()

    @Test
    fun `每个脚本都能解析出节点`() {
        val dir = pipelineDir()
        assumeTrue("没有同步素材，跳过", dir != null)

        val problems = ArrayList<String>()
        for (script in scripts(dir!!)) {
            val nodes = loadAll(script)
            if (nodes.isEmpty()) problems += "${script.name}: 一个节点都没解析出来"
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `没有断链_所有 next 和 on_error 都指向存在的节点`() {
        val dir = pipelineDir()
        assumeTrue("没有同步素材，跳过", dir != null)

        val problems = ArrayList<String>()
        for (script in scripts(dir!!)) {
            val nodes = loadAll(script)
            for (node in nodes.values) {
                for (raw in node.next + node.onError) {
                    // 回边要去掉前缀再查 —— 这正是 [JumpBack] 支持的一部分
                    val target = NodeEdges.strip(raw)
                    if (target !in nodes && target !in KNOWN_MISSING_FROM_SYNC) {
                        problems += "${script.name}: [${node.name}] 引用了不存在的 [$target]"
                    }
                }
            }
        }
        assertTrue(
            "断链会让流程静默提前结束：\n" + problems.joinToString("\n"),
            problems.isEmpty()
        )
    }

    @Test
    fun `每个脚本都能推断出入口节点`() {
        val dir = pipelineDir()
        assumeTrue("没有同步素材，跳过", dir != null)

        val problems = ArrayList<String>()
        for (script in scripts(dir!!)) {
            val nodes = loadAll(script)
            if (nodes.isEmpty()) continue
            val entry = PipelineRunner.guessEntry(nodes)
            if (entry == null) problems += "${script.name}: 推断不出入口"
            else if (entry !in nodes) problems += "${script.name}: 入口 [$entry] 不在节点表里"
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `回边指向的节点都真实存在`() {
        val dir = pipelineDir()
        assumeTrue("没有同步素材，跳过", dir != null)

        val problems = ArrayList<String>()
        var jumpBackCount = 0
        for (script in scripts(dir!!)) {
            val nodes = loadAll(script)
            for (node in nodes.values) {
                for (raw in node.next) {
                    if (!NodeEdges.isJumpBack(raw)) continue
                    jumpBackCount++
                    val target = NodeEdges.strip(raw)
                    if (target !in nodes && target !in KNOWN_MISSING_FROM_SYNC) {
                        problems += "${script.name}: [${node.name}] 回边指向不存在的 [$target]"
                    }
                }
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
        // 素材里确实有回边；一个都没有说明解析把带前缀的边全吃掉了
        if (scripts(dir).isNotEmpty()) {
            assertTrue("没有解析到任何回边，说明 [JumpBack] 前缀没被正确处理", jumpBackCount > 0)
        }
    }
}
