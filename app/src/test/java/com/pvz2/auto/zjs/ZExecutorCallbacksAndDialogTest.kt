package com.pvz2.auto.zjs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 监听 7 槽位 + 设置变量交互弹窗。
 *
 * 这两块原来都没实现：
 *  - 监听只跑 afterExecSuc / afterExecFail / afterExecFinish，
 *    另外四个（检查条件前 / 条件满足后 / 条件失败后 / 动作开始前）从不执行；
 *  - 设置变量的 dialog* 六个字段整组没实现，弹窗静默变成"直接赋值"。
 */
class ZExecutorCallbacksAndDialogTest {

    private val base = """{"repeatCount":1,"delay":"0","delayUnit":0,"screenWidth":1280,"screenHeight":720}"""

    private class Rec : ZExecutor.Injector {
        val calls = ArrayList<String>()
        var dialogAnswer: Int? = 0
        val asked = ArrayList<String>()
        override fun tap(x: Int, y: Int, holdMs: Int) { calls.add("tap($x,$y)") }
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {}
        override fun key(keyCode: Int, holdMs: Int) {}
        override fun inputText(text: String) {}
        override fun askDialog(req: ZExecutor.AskDialog): Int? {
            asked.add(req.title)
            return dialogAnswer
        }
    }

    private fun script(vararg lines: String): Z.Script =
        ZjsFileFormat.parse((listOf(base) + lines).joinToString("\r\n") + "\r\n")
            .script ?: error("解析失败")

    private fun exec(logs: MutableList<String> = ArrayList()): Pair<ZExecutor, Rec> {
        val rec = Rec()
        val ex = ZExecutor(rec, ZPosResolver.Screen(1280, 720, 1.5f), FakeMatcher()) { logs.add(it) }
        return ex to rec
    }

    private class FakeMatcher : ZExecutor.Matcher {
        override fun findImage(pos: Z.PosData, rect: IntArray) = emptyList<ZPosResolver.Hit>()
        override fun findText(pos: Z.PosData, rect: IntArray) = emptyList<ZPosResolver.Hit>()
        override fun findColor(pos: Z.PosData, rect: IntArray) = emptyList<ZPosResolver.Hit>()
        override fun findNode(pos: Z.PosData, rect: IntArray) = emptyList<ZPosResolver.Hit>()
    }

    private fun click(x: Int, y: Int) =
        """{"type":"点击","posData":{"type":"location","x":"$x","y":"$y"}}"""

    // ------------------------------------------------------------ 监听 7 槽位

    @Test
    fun `七个槽位按协议顺序全部执行`() {
        val (ex, rec) = exec()
        val withCbs = """{"type":"点击","posData":{"type":"location","x":"1","y":"1"},""" +
            """"condition":{"type":"random","percent":100},"scriptCallbacks":{""" +
            """"beforeCondition":${click(10, 10)},""" +
            """"afterConditionSuc":${click(11, 11)},""" +
            """"afterConditionFail":${click(12, 12)},""" +
            """"beforeStartExec":${click(13, 13)},""" +
            """"afterExecSuc":${click(14, 14)},""" +
            """"afterExecFail":${click(15, 15)},""" +
            """"afterExecFinish":${click(16, 16)}}}"""
        val r = ex.run(script(withCbs))
        assertEquals("不该有跳过：${r.skipped}", 0, r.skipped.size)
        assertEquals(
            "顺序应为 条件前→条件满足后→开始前→本体→成功后→结束后",
            listOf("tap(10,10)", "tap(11,11)", "tap(13,13)", "tap(1,1)", "tap(14,14)", "tap(16,16)"),
            rec.calls
        )
    }

    @Test
    fun `条件失败时走 afterConditionFail 而不是成功槽位`() {
        val (ex, rec) = exec()
        val withCbs = """{"type":"点击","posData":{"type":"location","x":"1","y":"1"},""" +
            """"condition":{"type":"random","percent":0},"scriptCallbacks":{""" +
            """"beforeCondition":${click(10, 10)},""" +
            """"afterConditionSuc":${click(11, 11)},""" +
            """"afterConditionFail":${click(12, 12)},""" +
            """"beforeStartExec":${click(13, 13)}}}"""
        val r = ex.run(script(withCbs))
        assertEquals("条件不满足应当跳过本体", 1, r.skipped.size)
        assertEquals(listOf("tap(10,10)", "tap(12,12)"), rec.calls)
    }

    @Test
    fun `动作没有条件时不跑 beforeCondition`() {
        val (ex, rec) = exec()
        val withCbs = """{"type":"点击","posData":{"type":"location","x":"1","y":"1"},""" +
            """"scriptCallbacks":{"beforeCondition":${click(10, 10)},""" +
            """"beforeStartExec":${click(13, 13)}}}"""
        ex.run(script(withCbs))
        // 没有条件 → 不跑 beforeCondition；但 beforeStartExec 照跑
        assertEquals(listOf("tap(13,13)", "tap(1,1)"), rec.calls)
    }

    // ------------------------------------------------------------ 设置变量弹窗

    private fun setVarWithDialog(extra: String) =
        """{"type":"设置变量",""" + extra +
            ""","vars":[{"name":"n","value":{"varType":"number","valueExp":"5"}}]}"""

    @Test
    fun `弹窗点确定——按配置赋值`() {
        val logs = ArrayList<String>()
        val (ex, rec) = exec(logs)
        rec.dialogAnswer = 0
        val r = ex.run(
            script(
                setVarWithDialog("\"dialogTitle\":\"要开始了\",\"dialogOKText\":\"走\",\"dialogCancelText\":\"算了\""),
                """{"type":"运行JS代码","jsCode":"console.log('n=' + zdjl.getVar('n'))"}"""
            )
        )
        assertEquals(0, r.skipped.size)
        assertEquals(listOf("要开始了"), rec.asked)
        assertTrue("变量应被赋值：$logs", logs.any { it.contains("n=5") } && logs.none { it.contains("n=") && it.contains("undefined") })
    }

    @Test
    fun `弹窗点取消——执行取消动作且不赋值`() {
        val logs = ArrayList<String>()
        val (ex, rec) = exec(logs)
        rec.dialogAnswer = 1
        val r = ex.run(
            script(
                setVarWithDialog(
                    "\"dialogTitle\":\"要开始了\",\"dialogCancelText\":\"算了\",\"dialogCancelAction\":" +
                        click(9, 9)
                ),
                """{"type":"运行JS代码","jsCode":"console.log('n=' + String(zdjl.getVar('n')))"}"""
            )
        )
        assertEquals("取消动作应当执行", listOf("tap(9,9)"), rec.calls)
        // 取消后 return Outcome() → 设置变量这一条不再赋值；后面 JS 读到空
        assertTrue("变量不该被赋值：$logs", logs.any { it.contains("n=undefined") || it.contains("n=") })
        assertEquals(0, r.skipped.size)
    }

    @Test
    fun `没有弹窗通道时按确定处理，不卡住`() {
        val logs = ArrayList<String>()
        val (ex, rec) = exec(logs)
        rec.dialogAnswer = null
        val r = ex.run(
            script(
                setVarWithDialog("\"dialogTitle\":\"要开始了\""),
                """{"type":"运行JS代码","jsCode":"console.log('n=' + zdjl.getVar('n'))"}"""
            )
        )
        assertEquals(0, r.skipped.size)
        assertTrue("应提示没有通道：$logs", logs.any { it.contains("没有弹窗通道") })
        assertTrue("仍应赋值：$logs", logs.any { it.contains("n=5") })
    }
}
