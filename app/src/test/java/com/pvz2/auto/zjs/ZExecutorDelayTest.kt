package com.pvz2.auto.zjs

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动作的前置等待必须真的等。
 *
 * 真机日志暴露过：配了 200ms 的滑动，节点"用时 4ms"就结束了。
 * 这条守住"写了 200 就是 200 左右"，不写时按默认 200 走。
 */
class ZExecutorDelayTest {

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

    private fun script(vararg lines: String): Z.Script {
        val base = """{"repeatCount":1,"delay":"0","delayUnit":0,"pauseOnFail":false,""" +
            """"screenWidth":1280,"screenHeight":720,"description":"测试"}"""
        return ZjsFileFormat.parse((listOf(base) + lines).joinToString("\r\n") + "\r\n")
            .script ?: error("解析失败")
    }

    private fun ex(logs: MutableList<String>) =
        ZExecutor(Rec(), ZPosResolver.Screen(1280, 720, 1.5f), NoMatch) { logs.add(it) }

    @Test
    fun `写了 200ms 的滑动会真的等`() {
        val logs = ArrayList<String>()
        val t0 = System.currentTimeMillis()
        ex(logs).run(
            script(
                """{"type":"滑动","delay":"200","delayUnit":0,""" +
                    """"startPos":{"type":"location","x":"100","y":"100"},""" +
                    """"endPos":{"type":"location","x":"200","y":"200"},"duration":"50"}""",
                """{"type":"控制执行","controlRunType":"stop"}"""
            )
        )
        val cost = System.currentTimeMillis() - t0
        assertTrue("200ms 的等待没生效（总耗时 ${cost}ms）：$logs", cost >= 180)
    }

    /**
     * 不写 delay 时按**默认 100ms** 走（自动精灵的节奏）。
     *
     * 原来是 200，于是"没写等待"的动作比作者预期慢一倍 ——
     * 用户实测反馈「等待缺省 100ms 没生效」。
     */
    @Test
    fun `不写 delay 时按默认 100ms`() {
        val logs = ArrayList<String>()
        val t0 = System.currentTimeMillis()
        ex(logs).run(
            script(
                """{"type":"滑动","startPos":{"type":"location","x":"100","y":"100"},""" +
                    """"endPos":{"type":"location","x":"200","y":"200"},"duration":"50"}""",
                """{"type":"控制执行","controlRunType":"stop"}"""
            )
        )
        val cost = System.currentTimeMillis() - t0
        assertTrue("默认 100ms 没生效（总耗时 ${cost}ms）：$logs", cost >= 80)
        assertTrue("默认值变成了 200ms 那么久（总耗时 ${cost}ms）：$logs", cost < 220)
    }
}
