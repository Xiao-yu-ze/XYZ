package com.pvz2.auto.maa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 节点参数展开。
 *
 * 编辑器"展开看参数"是这个功能的主要用途 —— 显示错了比不显示更糟：
 * 用户会照着错的数字去改脚本。所以逐字段钉死。
 */
class NodeDescriberTest {

    private fun rows(node: NodeDef): Map<String, String> =
        NodeDescriber.describe(node).associate { it.label.trim() to it.value }

    @Test
    fun `摘要显示识别类型和动作类型`() {
        val node = NodeDef(
            name = "x",
            recognition = Recognition.Template(templates = listOf("a.png")),
            action = Action.Click()
        )
        assertEquals("TemplateMatch · Click", NodeDescriber.summary(node))
    }

    @Test
    fun `模板匹配展开出模板列表_ROI_阈值`() {
        val node = NodeDef(
            name = "x",
            recognition = Recognition.Template(
                templates = listOf("General/暂停.png", "General/加速.png"),
                roi = intArrayOf(10, 20, 300, 100),
                threshold = 0.8
            )
        )
        val r = rows(node)
        assertEquals("General/暂停.png\nGeneral/加速.png", r["模板"])
        assertEquals("(10,20) 300×100", r["ROI"])
        assertEquals("0.80", r["阈值"])
    }

    @Test
    fun `点击动作的目标两种形式都能显示`() {
        // [x,y] 绝对点
        assertEquals(
            "(640, 360)",
            rows(NodeDef("a", action = Action.Click(target = intArrayOf(640, 360))))["目标"]
        )
        // [x,y,w,h] 矩形取中心
        assertEquals(
            "(10, 20) 100×50",
            rows(NodeDef("b", action = Action.Click(target = intArrayOf(10, 20, 100, 50))))["目标"]
        )
        // null = 用命中框中心
        assertEquals("命中框中心", rows(NodeDef("c", action = Action.Click()))["目标"])
    }

    @Test
    fun `滑动动作展开出起点终点时长`() {
        val node = NodeDef(
            name = "x",
            action = Action.Swipe(
                begin = intArrayOf(640, 520),
                end = intArrayOf(640, 300),
                durationMs = 300
            )
        )
        val r = rows(node)
        assertEquals("(640, 520)", r["起点"])
        assertEquals("(640, 300)", r["终点"])
        assertEquals("300 ms", r["时长"])
    }

    @Test
    fun `OCR 展开出期望文本和相似度`() {
        val node = NodeDef(
            name = "x",
            recognition = Recognition.Ocr(
                expected = listOf("开始挑战", "继续挑战"),
                threshold = 0.3
            )
        )
        val r = rows(node)
        assertEquals("开始挑战\n继续挑战", r["期望文本"])
        assertEquals("0.30", r["相似度"])
    }

    @Test
    fun `next 和 on_error 都列出来`() {
        val node = NodeDef("x", next = listOf("a", "b"), onError = listOf("c"))
        val r = rows(node)
        assertEquals("a\nb", r["next"])
        assertEquals("c", r["on_error"])
    }

    @Test
    fun `默认值的时序项不占地方_非默认才显示`() {
        // 默认 200ms 的两项不显示，避免每张卡片塞一堆"正常"
        val plain = rows(NodeDef("x"))
        assertTrue("默认值不该出现", plain["动作前等待"] == null)
        assertTrue("默认值不该出现", plain["动作后等待"] == null)

        // 改成非默认值就应当出现
        val custom = rows(NodeDef("y", preDelay = 50, postDelay = 900))
        assertEquals("50 ms", custom["动作前等待"])
        assertEquals("900 ms", custom["动作后等待"])
    }

    @Test
    fun `禁用和最大命中只在非默认时显示`() {
        assertTrue(rows(NodeDef("x"))["启用"] == null)
        assertEquals("否", rows(NodeDef("x", enabled = false))["启用"])

        assertTrue(rows(NodeDef("x"))["最大命中"] == null)
        assertEquals("3", rows(NodeDef("x", maxHit = 3))["最大命中"])
    }

    @Test
    fun `识别排在最前面_时序排在最后`() {
        // 顺序是给人看的：先看等什么画面，再看做什么，最后才关心毫秒。
        val labels = NodeDescriber.describe(NodeDef("x")).map { it.label.trim() }
        assertTrue("识别应在动作之前", labels.indexOf("识别") < labels.indexOf("动作"))
        assertTrue("动作应在超时之前", labels.indexOf("动作") < labels.indexOf("超时"))
    }
}
