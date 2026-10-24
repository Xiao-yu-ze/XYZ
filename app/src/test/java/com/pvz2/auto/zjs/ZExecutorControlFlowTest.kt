package com.pvz2.auto.zjs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 这轮新写的控制流/条件/子脚本的回归测试。
 *
 * 全部用假注入器 + 假匹配器跑**真的 ZExecutor**，所以不需要虚拟屏，
 * 也不碰游戏。断言的每一条都对应一个"配了不生效"的老毛病：
 * `&N` 子列表跳转、限制运行次数、时间间隔的 ignoreInitPass、
 * 动作运行状态、执行脚本、暂停能被停止打断。
 */
class ZExecutorControlFlowTest {

    private val base = "{\"repeatCount\":1,\"delay\":\"0\",\"delayUnit\":0,\"pauseOnFail\":true," +
        "\"screenWidth\":1280,\"screenHeight\":720,\"description\":\"测试\"}"

    private fun script(vararg lines: String): Z.Script =
        ZjsFileFormat.parse((listOf(base) + lines).joinToString("\r\n") + "\r\n")
            .script ?: error("解析失败")

    private class Rec : ZExecutor.Injector {
        val calls = ArrayList<String>()
        override fun tap(x: Int, y: Int, holdMs: Int) { calls.add("tap($x,$y)") }
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {}
        override fun key(keyCode: Int, holdMs: Int) {}
        override fun inputText(text: String) {}
    }

    private object NoMatch : ZExecutor.Matcher {
        override fun findImage(pos: Z.PosData, rect: IntArray) = emptyList<ZPosResolver.Hit>()
        override fun findText(pos: Z.PosData, rect: IntArray) = emptyList<ZPosResolver.Hit>()
        override fun findColor(pos: Z.PosData, rect: IntArray) = emptyList<ZPosResolver.Hit>()
        override fun findNode(pos: Z.PosData, rect: IntArray) = emptyList<ZPosResolver.Hit>()
        override fun lastReason() = "假匹配器"
    }

    private fun executor(logs: MutableList<String>, inject: Rec = Rec()) =
        ZExecutor(inject, ZPosResolver.Screen(1280, 720, 1.5f), NoMatch) { logs.add(it) }

    @Test
    fun `子列表的 &N 跳转能往回跳，限制运行次数到点就停`() {
        val logs = ArrayList<String>()
        val s = script(
            "{\"type\":\"运行多个动作\",\"scriptSet\":[" +
                "{\"type\":\"控制执行\",\"controlRunType\":\"waitDelay\",\"delay\":\"0\"}," +
                "{\"type\":\"控制执行\",\"controlRunType\":\"jumpTo\",\"jumpToPosition\":\"&1\"," +
                "\"condition\":{\"type\":\"limitRunTimes\",\"limitTimes\":3}}]}"
        )
        executor(logs).run(s)
        val jumps = logs.count { it.contains("子列表跳转到第 1 条") }
        assertEquals("&1 应该跳 3 次（limitTimes=3）", 3, jumps)
        assertTrue("用完要给出原因", logs.any { it.contains("限制运行次数") })
    }

    @Test
    fun `时间间隔勾了 ignoreInitPass 首次不通过`() {
        val logs = ArrayList<String>()
        val s = script(
            "{\"type\":\"控制执行\",\"controlRunType\":\"waitDelay\",\"delay\":\"0\"," +
                "\"condition\":{\"type\":\"timeInterval\",\"timeIntervalValue\":\"1\"," +
                "\"timeIntervalUnit\":1,\"ignoreInitPass\":true}}"
        )
        val r = executor(logs).run(s)
        assertTrue("首次应当不成立 → 跳过", logs.any { it.contains("首次 → false") })
        assertEquals(1, r.skipped.size)
    }

    @Test
    fun `时间间隔没勾 ignoreInitPass 首次通过`() {
        val logs = ArrayList<String>()
        val s = script(
            "{\"type\":\"控制执行\",\"controlRunType\":\"waitDelay\",\"delay\":\"0\"," +
                "\"condition\":{\"type\":\"timeInterval\",\"timeIntervalValue\":\"1\",\"timeIntervalUnit\":1}}"
        )
        val r = executor(logs).run(s)
        assertTrue("没勾就该首次通过", logs.any { it.contains("首次 → true") })
        assertEquals(0, r.skipped.size)
    }

    @Test
    fun `跳过原因要指向真正卡住的条件，而不是上一次识别失败`() {
        val logs = ArrayList<String>()
        // 时间间隔（勾了首次不通过）→ 一定跳过；跳过原因必须是"时间间隔"，
        // 不能是匹配器上一次失败的理由（那样用户会去调阈值/换模板）。
        val s = script(
            "{\"type\":\"控制执行\",\"controlRunType\":\"waitDelay\",\"delay\":\"0\"," +
                "\"condition\":{\"type\":\"timeInterval\",\"timeIntervalValue\":\"5\"," +
                "\"timeIntervalUnit\":1,\"ignoreInitPass\":true}}"
        )
        val r = executor(logs).run(s)
        assertEquals(1, r.skipped.size)
        val reason = r.skipped[0].reason
        assertTrue("原因要带条件名：$reason", reason.contains("时间间隔"))
        assertTrue("不能贴匹配器的理由：$reason", !reason.contains("假匹配器"))
    }

    @Test
    fun `动作运行状态的三种写法都能解析`() {
        val logs = ArrayList<String>()
        val s = script(
            "{\"type\":\"控制执行\",\"controlRunType\":\"waitDelay\",\"delay\":\"0\"}",
            "{\"type\":\"控制执行\",\"controlRunType\":\"waitDelay\",\"delay\":\"0\"," +
                "\"condition\":{\"type\":\"scriptRunState\",\"aimPosition\":\"1\",\"aimScriptRunState\":\"suc\"}}",
            "{\"type\":\"控制执行\",\"controlRunType\":\"waitDelay\",\"delay\":\"0\"," +
                "\"condition\":{\"type\":\"scriptRunState\",\"aimPosition\":\"-1\",\"aimScriptRunState\":\"suc\"}}"
        )
        val r = executor(logs).run(s)
        // 两个条件都应该成立（第 1 条 / 前 1 条都跑成功了）
        assertEquals("两个状态条件都该成立：${r.skipped}", 0, r.skipped.size)
        assertTrue(logs.any { it.contains("实际 true") })
    }

    @Test
    fun `执行脚本会真去加载并跑子脚本`() {
        val logs = ArrayList<String>()
        val ex = executor(logs)
        var loaded = ""
        ex.scriptLoader = { name ->
            loaded = name
            script("{\"type\":\"控制执行\",\"controlRunType\":\"waitDelay\",\"delay\":\"0\"}")
        }
        val r = ex.run(script(
            "{\"type\":\"执行脚本\",\"filePath\":\"子.zjs\",\"continueCurrentAfterFinish\":true}"
        ))
        assertEquals("子.zjs", loaded)
        assertEquals(null, r.error)
        assertTrue(logs.any { it.contains("开始执行子脚本") })
        assertTrue(logs.any { it.contains("结束") })
    }

    @Test
    fun `暂停能被打断，不会把停止吞掉`() {
        val logs = ArrayList<String>()
        val ex = executor(logs)
        Thread {
            Thread.sleep(200)
            ex.stop()
        }.start()
        val r = ex.run(script("{\"type\":\"控制执行\",\"controlRunType\":\"pause\"}"))
        assertTrue("应当记下暂停中被停止", logs.any { it.contains("暂停中被停止") })
        assertEquals(null, r.error)
    }
}
