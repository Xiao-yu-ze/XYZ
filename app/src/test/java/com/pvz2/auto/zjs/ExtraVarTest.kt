package com.pvz2.auto.zjs

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 游戏节点私有参数（extra）吃变量。
 *
 * ## 为什么必须有一条测试锁住
 *
 * 真机上一版把 `valueExp` 整段当成字面量：
 *
 * ```
 * 变量→extra：pvzWaveTimes = zdjl.getVar('心塔_点波次数')
 * 点「下一波」(1225,219)        ← 只点 1 次
 * ```
 *
 * 界面上「含变量」标记正常、日志也不报错，唯一异常是"点波次数不对"——
 * 而这种错在真机上要么看不出、要么被当成脚本参数写错，排查成本极高。
 */
class ExtraVarTest {

    private val base = "{\"repeatCount\":1,\"delay\":\"0\",\"delayUnit\":0," +
        "\"screenWidth\":1280,\"screenHeight\":720}"

    private class Rec : ZExecutor.Injector {
        val calls = ArrayList<String>()
        override fun tap(x: Int, y: Int, holdMs: Int) { calls.add("tap($x,$y)") }
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
            calls.add("swipe($x1,$y1→$x2,$y2,${durationMs}ms)")
        }
        override fun key(keyCode: Int, holdMs: Int) {}
        override fun inputText(text: String) {}
    }

    private fun run(vararg lines: String): Rec {
        val rec = Rec()
        val ex = ZExecutor(rec, ZPosResolver.Screen(1280, 720, 1.5f), object : ZExecutor.Matcher {
            override fun findImage(pos: Z.PosData, rect: IntArray) = emptyList<ZPosResolver.Hit>()
            override fun findText(pos: Z.PosData, rect: IntArray) = emptyList<ZPosResolver.Hit>()
            override fun findColor(pos: Z.PosData, rect: IntArray) = emptyList<ZPosResolver.Hit>()
            override fun findNode(pos: Z.PosData, rect: IntArray) = emptyList<ZPosResolver.Hit>()
            override fun lastReason() = "本用例不用识别"
        }) {}
        val script = ZjsFileFormat.parse((listOf(base) + lines).joinToString("\r\n") + "\r\n")
            .script ?: error("解析失败")
        ex.run(script)
        return rec
    }

    /** `valueExp` 是 JS 调用时，必须求值，不能当字符串。 */
    @Test
    fun `点波次数从变量表达式来`() {
        val rec = run(
            "{\"type\":\"运行JS代码\",\"jsCode\":\"zdjl.setVar('t','3')\"}",
            "{\"type\":\"点下一波\",\"pvzWaveInterval\":\"10\"," +
                "\"__vars\":{\"pvzWaveTimes\":{\"varType\":\"string\",\"varScope\":\"script\"," +
                "\"valueExp\":\"zdjl.getVar('t')\"}}}"
        )
        val waves = rec.calls.count { it.startsWith("tap(") }
        assertEquals("变量求值后应点 3 次，实际：${rec.calls}", 3, waves)
    }

    /** 纯字面量仍然按字面量走（不能被当成表达式求值成空）。 */
    @Test
    fun `字面量次数照旧`() {
        val rec = run(
            "{\"type\":\"点下一波\",\"pvzWaveInterval\":\"10\"," +
                "\"__vars\":{\"pvzWaveTimes\":{\"varType\":\"string\",\"varScope\":\"script\"," +
                "\"valueExp\":\"2\"}}}"
        )
        assertEquals(2, rec.calls.count { it.startsWith("tap(") })
    }

    /**
     * 组合动作（运行多个动作）**里面**的点波也要吃变量。
     *
     * 真机证据：主脚本跑起来时，第 1 关里那句点波只点了 1 次，日志也没有
     * 「变量→extra：pvzWaveTimes = …」——说明子动作里的 `__vars` 根本没被解析。
     */
    @Test
    fun `组合动作里的点波也吃变量`() {
        val rec = run(
            "{\"type\":\"运行JS代码\",\"jsCode\":\"zdjl.setVar('t','4')\"}",
            "{\"type\":\"运行多个动作\",\"scriptSet\":[" +
                "{\"type\":\"点下一波\",\"pvzWaveInterval\":\"10\"," +
                "\"__vars\":{\"pvzWaveTimes\":{\"varType\":\"string\",\"varScope\":\"script\"," +
                "\"valueExp\":\"zdjl.getVar('t')\"}}}]}"
        )
        assertEquals(
            "子动作里的变量应生效（点 4 次），实际：${rec.calls}",
            4, rec.calls.count { it.startsWith("tap(") }
        )
    }
}
