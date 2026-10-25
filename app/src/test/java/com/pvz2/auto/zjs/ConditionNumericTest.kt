package com.pvz2.auto.zjs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 条件里的数字字段：`Number` 和"数字字符串"都要认。
 *
 * 真机踩到的坑：`percent` 是字符串 `"100"` 时，`as? Number` 不匹配 →
 * **静默**回落 50%，于是"100% 触发"实际只有一半概率，"0%"却有一半概率通过；
 * `limitTimes` 同理回落 0，而 0 在语义上等于"不限次数"。
 */
class ConditionNumericTest {

    private class Rec : ZExecutor.Injector {
        override fun tap(x: Int, y: Int, holdMs: Int) {}
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {}
        override fun key(keyCode: Int, holdMs: Int) {}
        override fun inputText(text: String) {}
    }

    private object NoMatch : ZExecutor.Matcher {
        override fun findImage(pos: Z.PosData, rect: IntArray) = emptyList<ZPosResolver.Hit>()
        override fun findText(pos: Z.PosData, rect: IntArray) = emptyList<ZPosResolver.Hit>()
        override fun findColor(pos: Z.PosData, rect: IntArray) = emptyList<ZPosResolver.Hit>()
        override fun findNode(pos: Z.PosData, rect: IntArray) = emptyList<ZPosResolver.Hit>()
        override fun lastReason() = null
    }

    private fun run(condJson: String): Pair<Int, List<String>> {
        val logs = ArrayList<String>()
        val base = """{"repeatCount":1,"delay":"0","delayUnit":0,"pauseOnFail":false,""" +
            """"screenWidth":1280,"screenHeight":720}"""
        val toast = """{"type":"系统提示","promptText":"x","delay":"0","delayUnit":0,""" +
            """"condition":$condJson}"""
        val stop = """{"type":"控制执行","controlRunType":"stop"}"""
        val script = ZjsFileFormat.parse(
            listOf(base, toast, stop).joinToString("\r\n") + "\r\n"
        ).script!!
        val r = ZExecutor(Rec(), ZPosResolver.Screen(1280, 720, 1.5f), NoMatch) { logs.add(it) }
            .run(script)
        return r.executed to logs
    }

    @Test
    fun `随机概率写成字符串 100 一定成立`() {
        // 注意：单测里的注入器没有提示通道，节点本身会被记"跳过"，
        // 所以只能断言"条件没被判不成立"，不能断言 executed 增加。
        val (_, logs) = run("""{"type":"random","percent":"100"}""")
        assertTrue("100% 不该被判不成立：$logs", logs.none { it.contains("条件不满足（随机运行）") })
    }

    @Test
    fun `随机概率写成字符串 0 一定不成立`() {
        val (_, logs) = run("""{"type":"random","percent":"0"}""")
        assertTrue("0% 应判不成立：$logs", logs.any { it.contains("条件不满足（随机运行）") })
    }

    @Test
    fun `限制次数写成字符串能读到上限`() {
        val (_, logs) = run(
            """{"type":"limitRunTimes","limitTimes":"2","resetTime":"afterFinish"}"""
        )
        assertTrue("应看到上限 2（而不是 0）：$logs", logs.any { it.contains("已用 0 / 2") })
        assertTrue("首次应成立：$logs", logs.none { it.contains("条件不满足（限制运行次数）") })
    }
}
