package com.pvz2.auto.zjs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `__vars` 里 **expression / random** 类型的求值。
 *
 * 自动精灵把"字段改成变量"时会把原字段删掉，值放在 `__vars` 里：
 * ```
 * "delay": 不存在
 * "__vars": {"delay": {"varType":"expression","valueExp":"100+50"}}
 * ```
 * 这两类 `valueExp` 本身就是一段 JS 表达式，所以直接用 Rhino 求值；
 * 求不出来的（`js_function` 等）必须**明确跳过**，不能当默认值跑。
 */
class VarExpressionTest {

    private val base = "{\"repeatCount\":1,\"delay\":\"0\",\"delayUnit\":0," +
        "\"screenWidth\":1280,\"screenHeight\":720}"

    private class Rec : ZExecutor.Injector {
        val calls = ArrayList<String>()
        override fun tap(x: Int, y: Int, holdMs: Int) { calls.add("tap($x,$y,$holdMs)") }
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {}
        override fun key(keyCode: Int, holdMs: Int) {}
        override fun inputText(text: String) {}
    }

    private fun matcher(): ZExecutor.Matcher = object : ZExecutor.Matcher {
        override fun findImage(pos: Z.PosData, rect: IntArray) = listOf(ZPosResolver.Hit(100, 200, 1.0))
        override fun findText(pos: Z.PosData, rect: IntArray) = listOf(ZPosResolver.Hit(100, 200, 1.0))
        override fun findColor(pos: Z.PosData, rect: IntArray) = listOf(ZPosResolver.Hit(100, 200, 1.0))
        override fun findNode(pos: Z.PosData, rect: IntArray) = emptyList<ZPosResolver.Hit>()
        override fun lastReason() = "假匹配器"
    }

    private val logs = ArrayList<String>()

    private fun run(vararg lines: String): Pair<ZExecutor.Result, Rec> {
        val rec = Rec()
        logs.clear()
        val ex = ZExecutor(rec, ZPosResolver.Screen(1280, 720, 1.5f), matcher()) { logs.add(it) }
        val script = ZjsFileFormat.parse((listOf(base) + lines).joinToString("\r\n") + "\r\n")
            .script ?: error("解析失败")
        return ex.run(script) to rec
    }

    @Test
    fun `delay 用 expression 求值`() {
        val (r, rec) = run(
            """{"type":"点击","posData":{"type":"location","x":"10","y":"20"},"delayUnit":0,""" +
                """"__vars":{"delay":{"varType":"expression","valueExp":"100+50"}}}"""
        )
        assertEquals(0, r.skipped.size)
        assertEquals(listOf("tap(10,20,100)"), rec.calls)
        // 记录里能看出等了 150ms（不是回落到默认 100ms）
        assertTrue("应等待 150ms：$logs", logs.any { it.contains("等待 150ms") })
    }

    @Test
    fun `repeatCount 用 expression 求值`() {
        val (r, rec) = run(
            """{"type":"点击","posData":{"type":"location","x":"10","y":"20"},"delayUnit":0,""" +
                """"__vars":{"repeatCount":{"varType":"expression","valueExp":"1+2"}}}"""
        )
        assertEquals(0, r.skipped.size)
        assertEquals(3, rec.calls.size)
    }

    @Test
    fun `random 用 Math_random 求值并落在区间内`() {
        val (r, rec) = run(
            """{"type":"点击","posData":{"type":"location","x":"10","y":"20"},"delayUnit":0,""" +
                """"__vars":{"repeatCount":{"varType":"random","valueExp":"Math.floor(Math.random()*2)+2"}}}"""
        )
        assertEquals(0, r.skipped.size)
        assertTrue("重复次数应落在 2..3：${rec.calls.size}", rec.calls.size in 2..3)
    }

    @Test
    fun `表达式读得到运行时变量`() {
        val (r, rec) = run(
            """{"type":"设置变量","vars":[{"name":"n","value":{"varType":"number","valueExp":"3"}}]}""",
            """{"type":"点击","posData":{"type":"location","x":"10","y":"20"},"delayUnit":0,""" +
                """"__vars":{"repeatCount":{"varType":"expression","valueExp":"n"}}}"""
        )
        assertEquals(0, r.skipped.size)
        assertEquals(3, rec.calls.size)
    }

    @Test
    fun `js_function 仍然明确跳过（不当默认值跑）`() {
        val (r, rec) = run(
            """{"type":"点击","posData":{"type":"location","x":"10","y":"20"},"delayUnit":0,""" +
                """"__vars":{"delay":{"varType":"js_function","valueExp":"function(){return 150}"}}}"""
        )
        assertEquals(0, rec.calls.size)
        assertEquals(1, r.skipped.size)
        assertTrue(
            "应说明无法求值：${r.skipped.map { it.reason }}",
            r.skipped[0].reason.contains("求不出来")
        )
    }

    @Test
    fun `表达式语法错误也明确跳过`() {
        val (r, rec) = run(
            """{"type":"点击","posData":{"type":"location","x":"10","y":"20"},"delayUnit":0,""" +
                """"__vars":{"delay":{"varType":"expression","valueExp":"100+"}}}"""
        )
        assertEquals(0, rec.calls.size)
        assertEquals(1, r.skipped.size)
        assertTrue(
            "应说明无法求值：${r.skipped.map { it.reason }}",
            r.skipped[0].reason.contains("求不出来")
        )
    }
}
