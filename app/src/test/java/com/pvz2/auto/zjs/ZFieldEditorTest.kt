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
    // ------------------------------------------------------------ 模板图

    @Test
    fun `写模板图会带上全部尺寸字段`() {
        // 只写 base64、不写 imageWidth/Left/screenWidth 的话，
        // 自动精灵打开这张图位置是错的 —— 而这些字段界面上看不到。
        val a = action(
            """{"type":"点击图片","delayUnit":0,
                "posData":{"type":"image","imageData":{"data":"AA"}}}"""
        )
        val img = Z.ImageData(
            data = "BB",
            width = 40, height = 30, left = 100, top = 200,
            screenWidth = 1280, screenHeight = 720
        )
        val out = ZFieldEditor.applyImage(a, "位置.image", img)!!
        val json = out.posData!!.imageData!!.toJson()
        assertEquals("BB", json.getString("data"))
        assertEquals(40, json.getInt("imageWidth"))
        assertEquals(30, json.getInt("imageHeight"))
        assertEquals(100, json.getInt("imageLeft"))
        assertEquals(200, json.getInt("imageTop"))
        assertEquals(1280, json.getInt("screenWidth"))
        assertEquals(720, json.getInt("screenHeight"))
    }

    @Test
    fun `清空模板图`() {
        val a = action(
            """{"type":"点击图片","delayUnit":0,
                "posData":{"type":"image","imageData":{"data":"AA","imageWidth":10}}}"""
        )
        val out = ZFieldEditor.applyImage(a, "位置.image", null)!!
        assertNull(out.posData!!.imageData)
    }

    @Test
    fun `框选范围存成百分比`() {
        val a = action(
            """{"type":"点击图片","delayUnit":0,
                "posData":{"type":"image","imageData":{"data":"AA"}}}"""
        )
        val out = ZFieldEditor.apply(a, "位置.limitArea", "25.00% 25.00% 75.00% 75.00%")!!
        val area = out.posData!!.limitArea!!
        // 存的是百分比，换分辨率不失效
        assertEquals("25.00%", area.left.raw)
        assertEquals("75.00%", area.right.raw)
        assertEquals("25.00%", area.top.raw)
        assertEquals("75.00%", area.bottom.raw)
    }
    // ------------------------------------------------------------ 相似度阈值

    @Test
    fun `相似度带百分号也能填`() {
        // 界面上显示的是 "90%"，用户照着改就带着百分号
        val a = action("""{"type":"点击图片","delayUnit":0,
            "posData":{"type":"image","imageData":{"data":"AA"}}}""")
        val out = ZFieldEditor.apply(a, "位置.minSimilarPercent", "85%")!!
        assertEquals(85, out.posData!!.minSimilarPercent)
    }

    @Test
    fun `相似度超范围夹住而不是报错`() {
        // 用户想表达"最严格"，弹个错只会让他再猜一次
        val a = action("""{"type":"点击图片","delayUnit":0,
            "posData":{"type":"image","imageData":{"data":"AA"}}}""")
        assertEquals(100, ZFieldEditor.apply(a, "位置.minSimilarPercent", "150")!!.posData!!.minSimilarPercent)
        // 协议范围是 50~100，低于 50 等于"全屏最像的就算命中"，夹到 50
        assertEquals(50, ZFieldEditor.apply(a, "位置.minSimilarPercent", "-5")!!.posData!!.minSimilarPercent)
    }

    @Test
    fun `清空相似度回到默认`() {
        val a = action("""{"type":"点击图片","delayUnit":0,
            "posData":{"type":"image","imageData":{"data":"AA"},"minSimilarPercent":77}}""")
        val out = ZFieldEditor.apply(a, "位置.minSimilarPercent", "")!!
        assertNull(out.posData!!.minSimilarPercent)
    }
    // ------------------------------------------------------------ 搜索范围的单位

    @Test
    fun `搜索范围返回的是左上右下不是宽高`() {
        // ★ 这里钉住一个曾经的真实 bug：
        //   toPxRect 返回 [左,上,宽,高]，而所有消费者按 [左,上,右,下] 用。
        //   "320 180 960 540" 于是被算成 320×180，搜索范围只有真实的四分之一，
        //   现象是"模板比范围还大"或者"明明在画面里却找不到"，且没有任何报错。
        val screen = ZPosResolver.Screen(1280, 720, 1f)
        val pos = Z.PosData(
            type = Z.PosType.IMAGE,
            limitArea = ZArea(
                ZValue.parse("320"), ZValue.parse("180"),
                ZValue.parse("960"), ZValue.parse("540")
            )
        )
        val r = ZPosResolver.searchRect(pos, screen)
        assertEquals(320, r[0])
        assertEquals(180, r[1])
        assertEquals(960, r[2])   // 右，不是宽 640
        assertEquals(540, r[3])   // 下，不是高 360
        assertEquals(640, r[2] - r[0])
        assertEquals(360, r[3] - r[1])
    }

    @Test
    fun `百分比范围也按左上右下返回`() {
        val screen = ZPosResolver.Screen(1000, 500, 1f)
        val pos = Z.PosData(
            type = Z.PosType.IMAGE,
            limitArea = ZArea(
                ZValue.parse("25%"), ZValue.parse("20%"),
                ZValue.parse("75%"), ZValue.parse("80%")
            )
        )
        val r = ZPosResolver.searchRect(pos, screen)
        assertEquals(250, r[0])
        assertEquals(100, r[1])
        assertEquals(750, r[2])
        assertEquals(400, r[3])
    }
}