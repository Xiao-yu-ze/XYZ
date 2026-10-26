package com.pvz2.auto.zjs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/**
 * 五个「游戏动作」：种植物 / 喂豆 / 铲除 / 点击格子 / 使用神器。
 *
 * 这些不是协议类型，是我们给 PVZ2 玩家包的语义动作：参数只有卡槽/行/列，
 * 点位查 [PvzCoords]。这里断言「最终注入的手势和坐标表完全一致」——
 * 差一个像素就会种到隔壁格子，而界面上看不出来。
 */
class PvzActionTest {

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

    private fun run(vararg lines: String): Pair<ZExecutor.Result, Rec> {
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
        return ex.run(script) to rec
    }

    // ---------------------------------------------------------- 坐标表本身

    @Test
    fun `坐标表尺寸与格子点位`() {
        assertEquals(5, PvzCoords.ROWS)
        assertEquals(9, PvzCoords.COLS)
        // 首行那几个手调异常点：照表查，不能算
        assertEquals(listOf(856, 120), PvzCoords.cell(1, 5)!!.toList())
        assertEquals(listOf(1013, 183), PvzCoords.cell(1, 7)!!.toList())
        assertEquals(listOf(1108, 182), PvzCoords.cell(1, 8)!!.toList())
        assertEquals(listOf(1193, 156), PvzCoords.cell(1, 9)!!.toList())
        assertEquals(listOf(1188, 275), PvzCoords.cell(2, 9)!!.toList())
        // 偶数行就是等距的
        assertEquals(listOf(469, 368), PvzCoords.cell(3, 1)!!.toList())
        assertEquals(listOf(1197, 584), PvzCoords.cell(5, 9)!!.toList())
        // 越界
        assertEquals(null, PvzCoords.cell(0, 1))
        assertEquals(null, PvzCoords.cell(6, 1))
        assertEquals(null, PvzCoords.cell(1, 10))
    }

    @Test
    fun `卡槽点位`() {
        assertEquals(listOf(57, 120), PvzCoords.slot(1)!!.toList())
        assertEquals(listOf(57, 613), PvzCoords.slot(8)!!.toList())
        assertEquals(null, PvzCoords.slot(0))
        assertEquals(null, PvzCoords.slot(9))
    }

    // ---------------------------------------------------------- 五个动作

    @Test
    fun `种植物——从卡槽3拖到第2行第5列`() {
        val (r, rec) = run(
            """{"type":"种植物","pvzSlot":"3","pvzRow":"2","pvzCol":"5"}"""
        )
        assertEquals(0, r.skipped.size)
        // 卡槽3 = (56,267)，格子 2行5列 = (833,260)，滑 80ms
        assertEquals(listOf("swipe(56,267→833,260,80ms)"), rec.calls)
    }

    @Test
    fun `喂豆——从能量豆拖到格子`() {
        val (r, rec) = run("""{"type":"喂豆","pvzRow":"3","pvzCol":"3"}""")
        assertEquals(0, r.skipped.size)
        assertEquals(listOf("swipe(480,679→651,368,80ms)"), rec.calls)
    }

    @Test
    fun `铲除——从铲子拖到格子`() {
        val (r, rec) = run("""{"type":"铲除","pvzRow":"1","pvzCol":"1"}""")
        assertEquals(0, r.skipped.size)
        assertEquals(listOf("swipe(1222,674→469,152,80ms)"), rec.calls)
    }

    @Test
    fun `使用神器——从神器图标拖到格子`() {
        val (r, rec) = run(
            """{"type":"使用神器","pvzArtifact":"bean","pvzRow":"2","pvzCol":"9"}"""
        )
        assertEquals(0, r.skipped.size)
        assertEquals(listOf("swipe(749,703→1188,275,80ms)"), rec.calls)
    }

    @Test
    fun `点击格子——只点不拖`() {
        val (r, rec) = run("""{"type":"点击格子","pvzRow":"5","pvzCol":"9"}""")
        assertEquals(0, r.skipped.size)
        assertEquals(listOf("tap(1197,584)"), rec.calls)
    }

    // ---------------------------------------------------------- 参数校验

    @Test
    fun `缺行列必须明确跳过`() {
        val (r, rec) = run("""{"type":"点击格子","pvzRow":"2"}""")
        assertEquals(0, rec.calls.size)
        assertEquals(1, r.skipped.size)
        assertTrue(
            "应说明缺行列：${r.skipped[0].reason}",
            r.skipped[0].reason.contains("缺行列")
        )
    }

    @Test
    fun `行列越界必须明确跳过而不是点错格子`() {
        val (r, rec) = run("""{"type":"点击格子","pvzRow":"6","pvzCol":"1"}""")
        assertEquals(0, rec.calls.size)
        assertTrue(
            "应说明越界：${r.skipped[0].reason}",
            r.skipped[0].reason.contains("越界")
        )
    }

    @Test
    fun `种植物缺卡槽号必须明确跳过`() {
        val (r, rec) = run("""{"type":"种植物","pvzRow":"2","pvzCol":"5"}""")
        assertEquals(0, rec.calls.size)
        assertTrue(
            "应说明缺卡槽：${r.skipped[0].reason}",
            r.skipped[0].reason.contains("卡槽")
        )
    }

    // ---------------------------------------------------------- 落盘往返

    @Test
    fun `行列卡槽能原样存回去`() {
        val a = Z.Action.parse(
            JSONObject("""{"type":"种植物","pvzSlot":"4","pvzRow":"2","pvzCol":"7"}""")
        )!!
        val out = a.toJson()
        assertEquals("种植物", out.getString("type"))
        assertEquals("4", out.getString("pvzSlot"))
        assertEquals("2", out.getString("pvzRow"))
        assertEquals("7", out.getString("pvzCol"))
    }

    @Test
    fun `葫芦按体型选起点`() {
        val (r, rec) = run(
            """{"type":"使用神器","pvzArtifact":"gourd_l","pvzRow":"3","pvzCol":"3"}"""
        )
        assertEquals(0, r.skipped.size)
        // 葫芦·大 = (830,577)，格子 3行3列 = (651,368)
        assertEquals(listOf("swipe(830,577→651,368,80ms)"), rec.calls)
    }

    @Test
    fun `神器缺参数要明确说清楚`() {
        val (r, rec) = run("""{"type":"使用神器","pvzRow":"1","pvzCol":"1"}""")
        assertEquals(0, rec.calls.size)
        assertTrue(r.skipped.isNotEmpty())
        assertTrue(
            "应提示缺神器参数：${r.skipped[0].reason}",
            r.skipped[0].reason.contains("神器")
        )
    }

    @Test
    fun `点波可以连点 N 次`() {
        val (r, rec) = run(
            """{"type":"点下一波","pvzWaveTimes":"3","pvzWaveInterval":"0"}"""
        )
        assertEquals(0, r.skipped.size)
        assertEquals(3, rec.calls.size)
        assertEquals(listOf("tap(1225,219)"), rec.calls.distinct())
    }
}
