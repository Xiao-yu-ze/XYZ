package com.pvz2.auto.zjs

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 按字段路径改动作。
 *
 * 这里断言的都是**用户改完之后导出，别人读到什么**。
 */
class ZFieldEditorTest {

    private fun action(json: String): Z.Action = Z.Action.parse(JSONObject(json))!!

    @Test
    fun `改备注`() {
        val a = action("""{"type":"点击","delayUnit":1}""")
        val b = ZFieldEditor.apply(a, "desc", "点开始")!!
        assertEquals("点开始", b.common.desc)
        // 原对象不能被改（data class 的意义就在这里）
        assertNull(a.common.desc)
    }

    @Test
    fun `改坐标写进 posData`() {
        val a = action("""{"type":"点击","delayUnit":1,"posData":{"type":"location","x":"1","y":"2"}}""")
        val b = ZFieldEditor.apply(a, "位置.x", "640")!!
        assertEquals("640", b.posData!!.x!!.raw)
        assertEquals("2", b.posData!!.y!!.raw)
        // 落盘后别人读到的是新坐标
        assertEquals("640", b.toJson().getJSONObject("posData").getString("x"))
    }

    @Test
    fun `改起点和终点不会串到位置`() {
        val a = action(
            """{"type":"滑动","delayUnit":1,
               "startPos":{"type":"location","x":"640","y":"520"},
               "endPos":{"type":"location","x":"640","y":"300"}}"""
        )
        val b = ZFieldEditor.apply(a, "终点.y", "100")!!
        assertEquals("100", b.endPos!!.y!!.raw)
        assertEquals("520", b.startPos!!.y!!.raw)
    }

    @Test
    fun `清空字段表示删除它`() {
        val a = action("""{"type":"点击","delayUnit":1,"desc":"旧的"}""")
        val b = ZFieldEditor.apply(a, "desc", "")!!
        assertNull(b.common.desc)
        // 落盘时不该出现这个键
        assertTrue(!b.toJson().has("desc"))
    }

    @Test
    fun `重复次数非数字时清空而不是写坏值`() {
        val a = action("""{"type":"点击","delayUnit":1,"repeatCount":3}""")
        val b = ZFieldEditor.apply(a, "repeatCount", "abc")!!
        assertNull(b.common.repeatCount)
    }

    @Test
    fun `限区域格式不对时拒绝`() {
        // 坏值会让执行器解析失败，而那时用户已经忘了自己填过什么
        val a = action(
            """{"type":"点击","delayUnit":1,
               "posData":{"type":"location","x":"1","y":"2"}}"""
        )
        assertNull(ZFieldEditor.apply(a, "位置.limitArea", "不是区域"))
        // 合法的则接受
        val ok = ZFieldEditor.apply(a, "位置.limitArea", "320 180 960 540")!!
        assertEquals("320 180 960 540", ok.posData!!.limitArea!!.toString())
    }

    @Test
    fun `不认识的路径返回 null 而不是静默丢弃`() {
        val a = action("""{"type":"点击","delayUnit":1}""")
        assertNull(ZFieldEditor.apply(a, "不存在的字段", "x"))
        assertNull(ZFieldEditor.apply(a, "位置.不存在的", "x"))
    }

    // ------------------------------------------------------------ 换类型

    @Test
    fun `换成点击图片时位置类型同步改成 image`() {
        // ★ 这是最关键的一条。
        //   type 和 posData.type 是绑定的，不同步的话存出来的是
        //   「点击图片」带一个 location 位置 —— 执行器按 image 找永远匹配不到，
        //   而用户完全看不出哪里错了。
        val a = action("""{"type":"点击","delayUnit":1,"posData":{"type":"location","x":"100","y":"200"}}""")
        val b = ZFieldEditor.changeType(a, Z.ActionType.CLICK_IMAGE)
        assertEquals(Z.ActionType.CLICK_IMAGE, b.type)
        assertEquals(Z.PosType.IMAGE, b.posData!!.type)

        val json = b.toJson()
        assertEquals("点击图片", json.getString("type"))
        assertEquals("image", json.getJSONObject("posData").getString("type"))
    }

    @Test
    fun `换回点击时位置类型回到 location`() {
        val a = action("""{"type":"点击图片","delayUnit":1,"posData":{"type":"image","imageData":{"data":"AA"}}}""")
        val b = ZFieldEditor.changeType(a, Z.ActionType.CLICK)
        assertEquals(Z.PosType.LOCATION, b.posData!!.type)
    }

    @Test
    fun `换类型时保留能带过去的坐标`() {
        val a = action("""{"type":"点击","delayUnit":1,"posData":{"type":"location","x":"100","y":"200"}}""")
        // location → location（换成同样要坐标的动作）
        val b = ZFieldEditor.changeType(a, Z.ActionType.SWIPE)
        assertEquals("100", b.posData!!.x!!.raw)
        assertEquals("200", b.posData!!.y!!.raw)
    }

    @Test
    fun `没有位置的动作换类型不会凭空造出位置`() {
        val a = action("""{"type":"按键","delayUnit":1}""")
        val b = ZFieldEditor.changeType(a, Z.ActionType.PROMPT)
        assertNull(b.posData)
    }

    @Test
    fun `动作类型要求的位置类型对照正确`() {
        assertEquals(Z.PosType.LOCATION, ZFieldEditor.expectedPosType(Z.ActionType.CLICK))
        assertEquals(Z.PosType.IMAGE, ZFieldEditor.expectedPosType(Z.ActionType.CLICK_IMAGE))
        assertEquals(Z.PosType.TEXT, ZFieldEditor.expectedPosType(Z.ActionType.CLICK_TEXT))
        assertEquals(Z.PosType.COLOR, ZFieldEditor.expectedPosType(Z.ActionType.CLICK_COLOR))
        assertEquals(Z.PosType.NODE, ZFieldEditor.expectedPosType(Z.ActionType.CLICK_NODE))
        assertEquals(Z.PosType.AI_LOC, ZFieldEditor.expectedPosType(Z.ActionType.AI_CLICK))
        // 没有位置的动作
        assertNull(ZFieldEditor.expectedPosType(Z.ActionType.KEY))
        assertNull(ZFieldEditor.expectedPosType(Z.ActionType.PROMPT))
    }

    // ------------------------------------------------------------ 枚举字段

    @Test
    fun `是 否 能写进布尔字段`() {
        val a = action("""{"type":"点击","delayUnit":1}""")
        assertEquals(true, ZFieldEditor.apply(a, "disabled", "是")!!.common.disabled)
        assertEquals(false, ZFieldEditor.apply(a, "disabled", "否")!!.common.disabled)
        // 也接受协议写法
        assertEquals(true, ZFieldEditor.apply(a, "disabled", "true")!!.common.disabled)
    }
}
