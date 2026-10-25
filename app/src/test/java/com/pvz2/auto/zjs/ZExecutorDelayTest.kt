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
        // 滑动自己的 duration 50 也占时间了，所以下限是 200 + 50
        assertTrue("200ms 的等待没生效（总耗时 ${cost}ms）：$logs", cost >= 230)
    }

    /**
     * 不写 delay 时按**默认 100ms** 走（自动精灵的节奏）。
     *
     * 原来是 200，于是"没写等待"的动作比作者预期慢一倍 ——
     * 用户实测反馈「等待缺省 100ms 没生效」。
     */
    @Test
    fun `不写 delay 时按默认 100ms`() {
        // JVM 单测的绝对耗时不稳（预热/GC），所以比**两条节点之差**：
        //   delay 显式 0 vs 不写（缺省）→ 差值就是缺省值本身
        fun nodeMs(delayJson: String): Int {
            val logs = ArrayList<String>()
            ex(logs).run(
                script(
                    """{"type":"点击","posData":{"type":"location","x":"1","y":"1"},""" +
                        """"duration":"10"$delayJson}""",
                    """{"type":"控制执行","controlRunType":"stop"}"""
                )
            )
            return Regex("""完成 \[1] 用时 (\d+)ms""")
                .find(logs.joinToString("\n"))?.groupValues?.get(1)?.toIntOrNull()
                ?: error("没有完成行：$logs")
        }
        val zero = nodeMs(""","delay":"0","delayUnit":0""")
        val dflt = nodeMs("")
        val diff = dflt - zero
        assertTrue("缺省等待没有生效（0 → ${zero}ms，缺省 → ${dflt}ms）", diff >= 70)
        assertTrue("缺省值看起来不是 100ms（差值 ${diff}ms，0 → ${zero}ms，缺省 → ${dflt}ms）", diff <= 200)
    }
}
