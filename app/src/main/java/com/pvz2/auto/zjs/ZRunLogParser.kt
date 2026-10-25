package com.pvz2.auto.zjs

/**
 * 把一次运行的日志文件解析成"按节点分组"的结构，给日志页用。
 *
 * ## 为什么需要
 *
 * 日志文件本身是给人看的流水账：用户想知道"跑到了哪、卡在哪、那条花了多久"，
 * 只能从头滚到底，还看不出哪几行属于同一个节点。所以先解析成节点列表：
 * 列表只显示**顺序 + 类型/名字 + 用时 + 状态**，点进去才看完整细节。
 *
 * 解析的格式就是 [com.pvz2.auto.core.ZjsLog] 写出来的那种，不另发明格式：
 *
 *     ── [1/76] 点击文字 · 点击文字
 *        识别：…
 *        ↳ 完成 [1] 用时 31797ms（识别 3291ms × 29 次）
 *
 * 解析只认**行首**的这两种标记，中间的行一律归给当前节点 —— 这样以后日志里
 * 加新行也不会破坏分组。
 */
object ZRunLogParser {

    /** 一个节点的执行记录。 */
    data class NodeLog(
        /** 日志里的序号（从 1 开始）。 */
        val index: Int,
        val total: Int,
        /** 动作类型，例如「点击文字」「等待候选」。 */
        val typeName: String,
        /** 节点名字（jumpId 或描述）。 */
        val name: String,
        /** 这条的耗时（毫秒）；没写完（中断）时为 null。 */
        val costMs: Long?,
        val recognizeMs: Long?,
        val recognizeCount: Int?,
        /** 结束方式。 */
        val outcome: Outcome,
        /** 属于这条的全部原始行（不含标记行）。 */
        val lines: List<String>
    ) {
        /** 列表里显示的一行摘要。 */
        fun summary(): String = if (costMs != null) "${costMs}ms" else "未完成"
    }

    enum class Outcome(val label: String) {
        OK("成功"),
        JUMPED("跳转"),
        SKIPPED("跳过"),
        FAILED("失败"),
        UNFINISHED("未完成")
    }

    data class RunLog(
        val scriptName: String,
        val startedAt: String,
        val finishedAt: String?,
        val summary: String?,
        val nodes: List<NodeLog>,
        /** 不属于任何节点的行（运行请求、结束汇总）。 */
        val headLines: List<String>
    )

    private val NODE = Regex("""──\s*\[(\d+)/(\d+)]\s*([^·]+?)\s*·\s*(.*)$""")
    private val DONE = Regex("""↳\s*完成\s*\[(\d+)]\s*用时\s*(\d+)ms(?:（识别\s*(\d+)ms\s*×\s*(\d+)\s*次）)?""")
    private val STAMP = Regex("""^\[[^]]*]\s?""")

    fun parse(text: String): RunLog {
        var scriptName = ""
        var startedAt = ""
        var finishedAt: String? = null
        var summary: String? = null
        val head = ArrayList<String>()

        val nodes = ArrayList<NodeLog>()
        var curIndex = 0
        var curTotal = 0
        var curType = ""
        var curName = ""
        var curLines = ArrayList<String>()
        var curCost: Long? = null
        var curRecoMs: Long? = null
        var curRecoN: Int? = null
        var curOutcome = Outcome.UNFINISHED

        fun flush() {
            if (curIndex == 0) return
            // "跳过/失败"以行内容为准：完成行里没有状态，只能看细节行说了什么
            val joined = curLines.joinToString("\n")
            val outcome = when {
                joined.contains("→ 跳转") -> Outcome.JUMPED
                joined.contains("没有匹配到目标") || joined.contains("停在这一条") ||
                    joined.contains("读不出") -> Outcome.FAILED
                joined.contains("已跳过") || joined.contains("条件不成立，跳过") -> Outcome.SKIPPED
                curCost != null -> Outcome.OK
                else -> Outcome.UNFINISHED
            }
            nodes.add(
                NodeLog(
                    index = curIndex,
                    total = curTotal,
                    typeName = curType.trim(),
                    name = curName.trim(),
                    costMs = curCost,
                    recognizeMs = curRecoMs,
                    recognizeCount = curRecoN,
                    outcome = outcome,
                    lines = curLines.toList()
                )
            )
        }

        for (raw in text.lineSequence()) {
            val line = raw.trimEnd()
            if (line.isBlank()) continue
            when {
                line.startsWith("# 脚本:") -> scriptName = line.substringAfter(":").trim()
                line.startsWith("# 开始:") -> startedAt = line.substringAfter(":").trim()
                line.startsWith("# 结束:") -> {
                    finishedAt = STAMP.find(line)?.value?.trim('[', ']', ' ')
                    summary = line
                }
                else -> {
                    val body = STAMP.replace(line, "")
                    val nm = NODE.find(body)
                    val dm = DONE.find(body)
                    when {
                        nm != null -> {
                            flush()
                            curIndex = nm.groupValues[1].toIntOrNull() ?: 0
                            curTotal = nm.groupValues[2].toIntOrNull() ?: 0
                            curType = nm.groupValues[3]
                            curName = nm.groupValues[4]
                            curLines = ArrayList()
                            curCost = null
                            curRecoMs = null
                            curRecoN = null
                            curOutcome = Outcome.UNFINISHED
                        }
                        dm != null -> {
                            curCost = dm.groupValues[2].toLongOrNull()
                            curRecoMs = dm.groupValues[3].toLongOrNull()
                            curRecoN = dm.groupValues[4].toIntOrNull()
                            curLines.add(body)
                        }
                        curIndex > 0 -> curLines.add(body)
                        else -> head.add(line)
                    }
                }
            }
        }
        flush()
        return RunLog(scriptName, startedAt, finishedAt, summary, nodes, head)
    }
}
