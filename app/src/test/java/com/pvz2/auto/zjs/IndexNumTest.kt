package com.pvz2.auto.zjs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「选第几个」（`indexNum`）各种写法。
 *
 * 真机上不好造"同一个词出现多次"的画面，所以这里用假 matcher 直接喂多个命中，
 * 断言最终点的是哪个点（按点击坐标看）。
 */
class IndexNumTest {

    private class Rec : ZExecutor.Injector {
        val taps = ArrayList<Pair<Int, Int>>()
        override fun tap(x: Int, y: Int, holdMs: Int) { taps.add(x to y) }
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {}
        override fun key(keyCode: Int, holdMs: Int) {}
        override fun inputText(text: String) {}
    }

    /** 三个命中点，相似度分别 60 / 90 / 75，位置从上到下。 */
    private class ThreeHits : ZExecutor.Matcher {
        override fun findImage(pos: Z.PosData, rect: IntArray) = hits()
        override fun findText(pos: Z.PosData, rect: IntArray) = hits()
        override fun findColor(pos: Z.PosData, rect: IntArray) = hits()
        override fun findNode(pos: Z.PosData, rect: IntArray) = hits()
        override fun lastReason() = null
        private fun hits() = listOf(
            ZPosResolver.Hit(100, 100, 60.0),
            ZPosResolver.Hit(300, 300, 90.0),
            ZPosResolver.Hit(500, 500, 75.0)
        )
    }

    private fun run(indexNum: String?): List<Pair<Int, Int>> {
        val rec = Rec()
        val pos = """{"type":"text","text":"x","similarPercent":50""" +
            (indexNum?.let { ""","indexNum":"$it"""" } ?: "") + "}"
        val base = """{"repeatCount":1,"delay":"0","delayUnit":0,"pauseOnFail":false,""" +
            """"screenWidth":1280,"screenHeight":720}"""
        val act = """{"type":"点击文字","posData":$pos,"delay":"0","delayUnit":0,"duration":"10"}"""
        val stop = """{"type":"控制执行","controlRunType":"stop"}"""
        val script = ZjsFileFormat.parse(
            listOf(base, act, stop).joinToString("\r\n") + "\r\n"
        ).script!!
        ZExecutor(rec, ZPosResolver.Screen(1280, 720, 1.5f), ThreeHits()) {}.run(script)
        return rec.taps
    }

    @Test
    fun `不写就点相似度最高的那个`() {
        assertEquals(listOf(300 to 300), run(null))
    }

    @Test
    fun `写 2 点第二个`() {
        assertEquals(listOf(300 to 300), run("2"))
    }

    @Test
    fun `写 1 点第一个`() {
        assertEquals(listOf(100 to 100), run("1"))
    }

    @Test
    fun `写 -1 点最后一个`() {
        assertEquals(listOf(500 to 500), run("-1"))
    }

    @Test
    fun `逗号写多点全点`() {
        val taps = run("1,3")
        assertEquals(2, taps.size)
        assertTrue("应按书写顺序点 1 再 3：$taps", taps[0] == (100 to 100) && taps[1] == (500 to 500))
    }

    @Test
    fun `越界单值会夹住而不是不点`() {
        // 只有 3 个命中，写 9 → 夹到最后一个（保持旧行为，避免"这条静默不执行"）
        assertEquals(listOf(500 to 500), run("9"))
    }
}
