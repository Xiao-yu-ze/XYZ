package com.pvz2.auto.maa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动精灵协议的完整字段覆盖。
 *
 * 这几个字段不是"看起来该有"才加的 —— 是扫描了全部 15 个真实脚本文件后
 * 按实际出现次数补的：`[JumpBack]` 2 处、`post_wait_freezes` 3 处、
 * `repeat_delay` 1 处、`order_by` 1 处。
 *
 * 共同特点是**错了不会报错**：前缀不认识 → 边被静默过滤；
 * 字段名打错 → 解析成默认值；order_by 拼错 → 点错目标。
 * 所以每个都用一个测试钉住。
 */
class ProtocolFieldsTest {

    // ---------------------------------------------------------- [JumpBack]

    @Test
    fun `回边前缀能识别也能剥掉`() {
        assertTrue(NodeEdges.isJumpBack("[JumpBack]让我看看我在不在"))
        assertFalse(NodeEdges.isJumpBack("普通节点"))

        assertEquals("让我看看我在不在", NodeEdges.strip("[JumpBack]让我看看我在不在"))
        // 没有前缀时必须原样返回，不能吃掉第一个字符
        assertEquals("普通节点", NodeEdges.strip("普通节点"))
    }

    @Test
    fun `回边指向的节点不会被误判成入口`() {
        // 这正是真实脚本的形状：入口的 next 里有一条回边指向「检查」节点。
        // 不剥前缀的话 `[JumpBack]检查` != `检查`，于是「检查」看上去没人引用，
        // 会被选成入口 —— 流程从中间开始跑。
        val json = """
        {
            "入口": { "next": ["[JumpBack]检查", "后续"] },
            "检查": { "next": [] },
            "后续": { "next": [] }
        }
        """.trimIndent()

        val nodes = PipelineParser.parseAll(listOf(json))
        assertEquals("入口", PipelineRunner.guessEntry(nodes))
    }

    @Test
    fun `回边名字里的节点确实存在`() {
        val json = """
        { "A": { "next": ["[JumpBack]B"] }, "B": { "next": [] } }
        """.trimIndent()
        val nodes = PipelineParser.parseAll(listOf(json))
        // 剥掉前缀后应当能在节点表里找到，否则这条边会被 resolveNext 丢掉
        val raw = nodes.getValue("A").next.single()
        assertTrue(nodes.containsKey(NodeEdges.strip(raw)))
    }

    // ------------------------------------------------- 新增的三个字段

    @Test
    fun `post_wait_freezes 解析成毫秒`() {
        val json = """
        { "A": { "post_wait_freezes": 100, "next": [] } }
        """.trimIndent()
        assertEquals(100L, PipelineParser.parseAll(listOf(json)).getValue("A").postWaitFreezes)
    }

    @Test
    fun `repeat_delay 解析成毫秒`() {
        val json = """
        { "A": { "repeat_delay": 250, "next": [] } }
        """.trimIndent()
        assertEquals(250L, PipelineParser.parseAll(listOf(json)).getValue("A").repeatDelay)
    }

    @Test
    fun `order_by 解析成字符串`() {
        val json = """
        { "A": { "recognition": "OCR", "order_by": "Vertical", "next": [] } }
        """.trimIndent()
        assertEquals("Vertical", PipelineParser.parseAll(listOf(json)).getValue("A").orderBy)
    }

    @Test
    fun `三个新字段的默认值都不影响原有行为`() {
        val node = PipelineParser.parseAll(listOf("""{ "A": { "next": [] } }""")).getValue("A")
        assertEquals(0L, node.postWaitFreezes)
        assertEquals(0L, node.repeatDelay)
        assertNull(node.orderBy)
    }

    // ------------------------------------------------- 展开显示

    @Test
    fun `新字段在编辑器里能看到`() {
        val node = NodeDef(
            name = "x",
            recognition = Recognition.Ocr(expected = listOf("返回")),
            postWaitFreezes = 100,
            repeatDelay = 250,
            orderBy = "Vertical"
        )
        val rows = NodeDescriber.describe(node).associate { it.label.trim() to it.value }
        assertEquals("100 ms", rows["等画面稳定"])
        assertEquals("250 ms", rows["重试间隔"])
        assertEquals("Vertical", rows["选取顺序"])
    }

    @Test
    fun `order_by 只在 OCR 节点上显示`() {
        // 模板匹配目前不走多目标排序（见 Recognizer 的说明），
        // 显示出来会让人以为它生效了。
        val click = NodeDef(name = "x", orderBy = "Vertical", action = Action.Click())
        val rows = NodeDescriber.describe(click).associate { it.label.trim() to it.value }
        assertNull(rows["选取顺序"])
    }
}
