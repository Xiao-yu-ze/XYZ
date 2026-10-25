package com.pvz2.auto.zjs

import com.pvz2.auto.core.ZjsLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 运行日志（对应 Maa 的 `logs/` + `debug/on_error/`）。
 *
 * 用户的原话是"执行脚本时会有脚本日志，记录执行节点的详细数据，像 maa 这样"。
 * Maa 那套的粒度是：每个节点记**识别到什么、花了多久、接下来做了什么**，
 * 失败时再存一张当时的画面。这里守住三件事：
 *
 *  1. 每个节点的识别细节（范围/阈值/命中数/最佳分）真的进日志；
 *  2. 每次运行有自己的日志文件；
 *  3. 出现跳过/失败时，会去要一张现场截图。
 */
class ZExecutorRunLogTest {

    private class Rec : ZExecutor.Injector {
        val calls = ArrayList<String>()
        override fun tap(x: Int, y: Int, holdMs: Int) { calls.add("tap($x,$y)") }
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {}
        override fun key(keyCode: Int, holdMs: Int) {}
        override fun inputText(text: String) {}
    }

    /** 假匹配器：记下要过几次截图，并给出一句细节。 */
    private class DetailMatcher : ZExecutor.Matcher {
        var shots = 0
        override fun findImage(pos: Z.PosData, rect: IntArray) = emptyList<ZPosResolver.Hit>()
        override fun findText(pos: Z.PosData, rect: IntArray) =
            listOf(ZPosResolver.Hit(100, 200, 0.93))
        override fun findColor(pos: Z.PosData, rect: IntArray) =
            listOf(ZPosResolver.Hit(10, 20, 0.5))
        override fun findNode(pos: Z.PosData, rect: IntArray) = emptyList<ZPosResolver.Hit>()
        override fun lastReason() = null
        override fun lastDetail() = "文字 最佳93% 范围(0,0,1280,720) 要求90%"
        override fun saveFrame(dir: String, baseName: String): String {
            shots++
            return "$dir/$baseName.png"
        }
    }

    private fun script(vararg lines: String): Z.Script {
        val base = """{"repeatCount":1,"delay":"0","delayUnit":0,"pauseOnFail":true,""" +
            """"screenWidth":1280,"screenHeight":720,"description":"测试"}"""
        return ZjsFileFormat.parse((listOf(base) + lines).joinToString("\r\n") + "\r\n")
            .script ?: error("解析失败")
    }

    @Test
    fun `识别细节会进日志`() {
        val logs = ArrayList<String>()
        val ex = ZExecutor(Rec(), ZPosResolver.Screen(1280, 720, 1.5f), DetailMatcher()) {
            logs.add(it)
        }
        ex.run(
            script(
                """{"type":"点击文字","posData":{"type":"text","text":"开始战斗","similarPercent":90}}""",
                """{"type":"控制执行","controlRunType":"stop"}"""
            )
        )
        val line = logs.firstOrNull { it.contains("识别：") }
        assertTrue("日志里没有识别细节这一行：$logs", line != null)
        assertTrue("细节里缺少匹配器给的字段：$line", line!!.contains("最佳93%"))
        assertTrue("细节里缺少首点：$line", line.contains("首点(100,200)"))
        assertTrue("细节里缺少命中数：$line", line.contains("命中 1 个"))
    }

    /**
     * 一直等（timeout:-1）时，每 10 轮那条日志必须带上**这一轮看到了什么**。
     *
     * 只写"还在等"没有诊断价值：用户分不清画面没变、OCR 认成了别的字、
     * 还是相似度差一点。Maa 的日志能一眼看出问题，靠的就是这个。
     */
    @Test
    fun `一直等的重试行带识别细节`() {
        val logs = ArrayList<String>()
        val m = object : ZExecutor.Matcher {
            override fun findImage(pos: Z.PosData, rect: IntArray) = emptyList<ZPosResolver.Hit>()
            override fun findText(pos: Z.PosData, rect: IntArray) = emptyList<ZPosResolver.Hit>()
            override fun findColor(pos: Z.PosData, rect: IntArray) = emptyList<ZPosResolver.Hit>()
            override fun findNode(pos: Z.PosData, rect: IntArray) = emptyList<ZPosResolver.Hit>()
            override fun lastReason() = "画面里没有文字"
            override fun lastDetail() = "文字 最佳41% 范围(0,0,1280,720) 要求90% 认出=«开始»"
        }
        val ex = ZExecutor(Rec(), ZPosResolver.Screen(1280, 720, 1.5f), m) { logs.add(it) }
        val t = Thread {
            ex.run(
                script(
                    """{"type":"点击文字","posData":{"type":"text","text":"开始战斗","similarPercent":90},"waitTimeoutMs":-1,"waitIntervalMs":100}"""
                )
            )
        }
        t.start()
        // 10 轮 × 100ms 之后才会有第一行"还在等"（留足 JVM 调度余量）
        Thread.sleep(2500)
        ex.stop()
        t.join(2000)
        val line = logs.firstOrNull { it.contains("还在等") }
        assertTrue("没有重试日志：$logs", line != null)
        assertTrue("重试行没带识别细节：$line", line!!.contains("| 文字 最佳41%"))
    }

    /**
     * 每个节点都要有"用了多久"，而且把识别耗时单独列出来。
     *
     * 用户问的是"每个节点的运行时间、持续了多久、识别到了什么都有吗"。
     * 时间戳只能看出行与行之间的间隔，看不出：
     *  · 这条动作本身跑了多久；
     *  · 慢在识别，还是慢在等画面（重试间隔）。
     */
    @Test
    fun `每个节点都有耗时和识别耗时`() {
        val logs = ArrayList<String>()
        val ex = ZExecutor(Rec(), ZPosResolver.Screen(1280, 720, 1.5f), DetailMatcher()) {
            logs.add(it)
        }
        ex.run(
            script(
                """{"type":"点击文字","posData":{"type":"text","text":"开始战斗","similarPercent":90}}""",
                """{"type":"控制执行","controlRunType":"stop"}"""
            )
        )
        val done = logs.filter { it.contains("↳ 完成") }
        assertEquals("两条动作都该有完成行：$logs", 2, done.size)
        assertTrue("完成行里没有用时：$done", done.all { it.contains("用时") && it.contains("ms") })
        assertTrue("完成行里没有识别耗时：$done", done.all { it.contains("识别") && it.contains("次") })
        // 识别细节行也要带本次耗时
        val rec = logs.first { it.contains("识别：") }
        assertTrue("识别行没有本次耗时：$rec", rec.contains("本次识别") && rec.contains("ms"))
    }

    /**
     * 跳转的节点也要有"完成 + 用时"。
     *
     * 真机日志里发现过：等待候选命中后直接跳走（Maa 的 next 语义），
     * 那一跳的分支没有记完成行 —— 于是"这个分支节点花了多久"就丢了。
     */
    @Test
    fun `跳转分支的节点也有完成行`() {
        val logs = ArrayList<String>()
        val ex = ZExecutor(Rec(), ZPosResolver.Screen(1280, 720, 1.5f), DetailMatcher()) {
            logs.add(it)
        }
        ex.run(
            // 往前跳到标了 jumpId 的那条（往回跳会成死循环，maxSteps 兜住）
            script(
                """{"type":"控制执行","controlRunType":"jumpTo","jumpToPosition":"目标"}""",
                """{"type":"控制执行","controlRunType":"setJumpId","jumpId":"目标"}""",
                """{"type":"控制执行","controlRunType":"stop"}"""
            )
        )
        val jumped = logs.filter { it.contains("↳ 完成") && it.contains("→ 跳转") }
        assertEquals("跳转的那条应当有完成行：$logs", 1, jumped.size)
        assertTrue("跳转的完成行里缺少用时：$jumped", jumped[0].contains("用时") && jumped[0].contains("ms"))
    }

    @Test
    fun `每次运行会写一个日志文件`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "zjslog-${System.nanoTime()}")
        dir.mkdirs()
        ZjsLog.baseDir = dir
        ZjsLog.beginRun("单元测试.zjs")
        ZjsLog.add("第一行")
        ZjsLog.add("第二行")
        val f = ZjsLog.lastRunFile()
        ZjsLog.endRun("测试结束")
        assertTrue("没有生成日志文件", f != null && f.isFile)
        val text = f!!.readText()
        assertTrue("文件里缺少脚本名：$text", text.contains("单元测试.zjs"))
        assertTrue("文件里缺少正文：$text", text.contains("第一行") && text.contains("第二行"))
        assertTrue("文件里缺少结束行：$text", text.contains("测试结束"))
        // 文件名带上时间与脚本名，便于用户挑
        assertTrue("文件名不像 run-时间_脚本：${f.name}", f.name.startsWith("run-"))
    }

    @Test
    fun `有跳过时会要一张现场截图`() {
        val m = DetailMatcher()
        val logs = ArrayList<String>()
        val ex = ZExecutor(Rec(), ZPosResolver.Screen(1280, 720, 1.5f), m) { logs.add(it) }
        // 跳到一个不存在的 jumpId → 记成"跳过"并停下，正好触发失败现场截图
        ex.run(
            script(
                """{"type":"控制执行","controlRunType":"jumpTo","jumpToPosition":"没有这个标识"}"""
            )
        )
        assertEquals("应当去要一张失败现场图（日志：$logs）", 1, m.shots)
        assertTrue("日志里应当写出截图路径：$logs", logs.any { it.contains("现场截图") })
    }
}
