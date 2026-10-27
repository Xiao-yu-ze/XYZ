package com.pvz2.auto.zjs

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 颜色的「一个旋钮」语义。
 *
 * 背景：参考管线 的颜色判定是逐通道区间 [lower, upper]。它一直被两个问题坑着：
 *  1. `colorLower` 的 getter 只认 Kotlin List，而脚本解析出来是 JSONArray
 *     → 永远返回 null，区间判定从未生效；
 *  2. 界面上只有「相似度」一个旋钮，可区间是独立存的 → 调了毫无反应。
 *
 * 这里把两条都钉住：区间能被解析出来，且调相似度会重算区间。
 */
class ColorRangeTest {

    private fun pos(json: String): Z.PosData =
        Z.PosData.parse(JSONObject(json)) ?: error("解析失败：$json")

    @Test
    fun `上下界能从 JSONArray 解析出来`() {
        val p = pos("""{"type":"color","color":"#9edf12",
            "colorLower":[148,213,8],"colorUpper":[168,233,28]}""")
        // ← 这就是那个 bug：JSONArray 不是 List，旧 getter 返回 null
        assertEquals(listOf(148, 213, 8), p.colorLower)
        assertEquals(listOf(168, 233, 28), p.colorUpper)
    }

    @Test
    fun `上下界是字符串形态也能解析`() {
        val p = pos("""{"type":"color","color":"#000000","colorLower":"10,20,30","colorUpper":"40 50 60"}""")
        assertEquals(listOf(10, 20, 30), p.colorLower)
        assertEquals(listOf(40, 50, 60), p.colorUpper)
    }

    @Test
    fun `没有上下界时返回 null`() {
        assertNull(pos("""{"type":"color","color":"#9edf12"}""").colorLower)
    }

    /** 调相似度必须真的改判定范围，否则用户看到"调了没用"。 */
    @Test
    fun `调相似度会重算上下界`() {
        val p0 = pos("""{"type":"color","color":"#9edf12","similarPercent":90}""")
        val action = Z.Action(type = Z.ActionType.CLICK_COLOR, posData = p0)

        // 96% → 容差 (100-96)% × 255 = 10
        val a96 = ZFieldEditor.apply(action, "位置.similarPercent", "96") ?: error("写回失败")
        val p96 = a96.posData!!
        assertEquals(listOf(148, 213, 8), p96.colorLower)
        assertEquals(listOf(168, 233, 28), p96.colorUpper)

        // 80% → 容差 51，范围明显放大
        val a80 = ZFieldEditor.apply(action, "位置.similarPercent", "80") ?: error("写回失败")
        val p80 = a80.posData!!
        assertEquals(listOf(107, 172, 0), p80.colorLower)
        assertEquals(listOf(209, 255, 69), p80.colorUpper)
    }

    /** 容差为 0（相似度 100）时上下界收成同一点：只认完全相同的像素。 */
    @Test
    fun `相似度 100 时区间收成一点`() {
        val p0 = pos("""{"type":"color","color":"#ff0000"}""")
        val action = Z.Action(type = Z.ActionType.CLICK_COLOR, posData = p0)
        val a = ZFieldEditor.apply(action, "位置.similarPercent", "100") ?: error("写回失败")
        assertEquals(listOf(255, 0, 0), a.posData!!.colorLower)
        assertEquals(listOf(255, 0, 0), a.posData!!.colorUpper)
    }
}
