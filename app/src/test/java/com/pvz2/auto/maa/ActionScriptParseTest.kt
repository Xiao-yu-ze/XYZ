package com.pvz2.auto.maa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 示例动作脚本的解析。
 *
 * 这个脚本只有点击/滑动/结束三种节点，**不依赖任何识别**，
 * 用来验证"节点层本身能不能驱动游戏"—— 如果它跑不通，
 * 后面所有带识别的脚本都无从谈起。
 *
 * 所以把解析结果钉死：节点数、入口、每个节点的动作类型和参数。
 * 解析错了的话，运行时会静默退化成 DirectHit + DoNothing（永远命中、什么都不做），
 * 现象是"脚本瞬间跑完但游戏毫无反应"，极难反推。用测试挡住。
 */
class ActionScriptParseTest {

    private val json = """
    {
        "动作测试_开始": {
            "pre_delay": 200,
            "next": ["动作测试_点击中心"]
        },
        "动作测试_点击中心": {
            "action": { "type": "Click", "param": { "target": [640, 360] } },
            "post_delay": 900,
            "next": ["动作测试_上滑"]
        },
        "动作测试_上滑": {
            "action": {
                "type": "Swipe",
                "param": { "begin": [640, 520], "end": [640, 300], "duration": 300 }
            },
            "post_delay": 900,
            "next": ["动作测试_下滑"]
        },
        "动作测试_下滑": {
            "action": {
                "type": "Swipe",
                "param": { "begin": [640, 300], "end": [640, 520], "duration": 300 }
            },
            "post_delay": 500,
            "next": ["动作测试_结束"]
        },
        "动作测试_结束": {
            "action": { "type": "StopTask" }
        }
    }
    """.trimIndent()

    private fun parse(): Map<String, NodeDef> = PipelineParser.parseAll(listOf(json))

    @Test
    fun `五个节点都解析出来了`() {
        assertEquals(5, parse().size)
    }

    @Test
    fun `没有 recognition 的节点退化成 DirectHit_也就是一定命中`() {
        // 这是**有意**的语义：一个纯动作节点没有前置条件，轮到时就直接执行。
        // 注意与"未知识别类型"区分 —— 那种情况解析器会报错而不是退化。
        val nodes = parse()
        assertEquals(Recognition.DirectHit, nodes.getValue("动作测试_点击中心").recognition)
    }

    @Test
    fun `点击节点的目标坐标解析正确`() {
        val click = parse().getValue("动作测试_点击中心").action
        assertTrue("期望 Action.Click，实际 $click", click is Action.Click)
        click as Action.Click
        assertEquals(listOf(640, 360), click.target?.toList())
    }

    @Test
    fun `滑动节点的起终点和时长解析正确`() {
        val swipe = parse().getValue("动作测试_上滑").action
        assertTrue("期望 Action.Swipe，实际 $swipe", swipe is Action.Swipe)
        swipe as Action.Swipe
        assertEquals(listOf(640, 520), swipe.begin.toList())
        assertEquals(listOf(640, 300), swipe.end.toList())
        assertEquals(300L, swipe.durationMs)
    }

    @Test
    fun `结束节点是 StopTask`() {
        assertEquals(Action.StopTask(), parse().getValue("动作测试_结束").action)
    }

    @Test
    fun `入口推断选中没被引用的那个节点`() {
        // 自动精灵的脚本没有 entry 字段，靠"谁没被别人 next 引用"推起点。
        // 这个脚本里只有 动作测试_开始 没被引用。
        assertEquals("动作测试_开始", PipelineRunner.guessEntry(parse()))
    }

    @Test
    fun `next 链是完整的_不会中途断掉`() {
        val nodes = parse()
        for (node in nodes.values) {
            for (name in node.next) {
                assertTrue("节点 $name 不存在，链会在这里断掉", nodes.containsKey(name))
            }
        }
    }
}
