package com.pvz2.auto.zjs

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动精灵脚本模型的解析与回写。
 *
 * 最要紧的一条是**未知字段必须原样带回去**：本程序只执行安卓上有意义的那部分
 * 动作，但用户导入的脚本里有 Windows 专用字段、加密字段、新版本字段。
 * 丢掉它们等于把用户的脚本改坏了，而且要在导出之后才发现。
 */
class ZModelTest {

    // ------------------------------------------------------------ 动作类型

    @Test
    fun `26 种动作的线格式全部能认出来`() {
        // 21 个不同字符串（6 个控制执行共享 type="控制执行"）
        val wires = listOf(
            "点击", "点击图片", "点击文字", "点击颜色", "点击节点", "AI点击",
            "滑动", "单指手势", "多指手势", "按键", "输入内容", "识别屏幕",
            "打开应用", "打开链接", "执行脚本", "运行多个动作",
            "系统提示", "设置变量", "运行JS代码", "加密动作"
        )
        for (w in wires) {
            assertNotNull("认不出动作类型 [$w]", Z.ActionType.ofWire(w, null))
        }
        assertEquals(26, Z.ActionType.entries.size)
    }

    @Test
    fun `控制执行靠 controlRunType 区分`() {
        assertEquals(Z.ActionType.CONTROL_JUMP, Z.ActionType.ofWire("控制执行", "jumpTo"))
        assertEquals(Z.ActionType.CONTROL_WAIT, Z.ActionType.ofWire("控制执行", "waitDelay"))
        assertEquals(Z.ActionType.CONTROL_STOP, Z.ActionType.ofWire("控制执行", "stop"))
        assertEquals(Z.ActionType.CONTROL_PAUSE, Z.ActionType.ofWire("控制执行", "pause"))
    }

    @Test
    fun `执行器只标在安卓能做的那部分上`() {
        assertTrue(Z.ActionType.CLICK.executable)
        assertTrue(Z.ActionType.SWIPE.executable)
        // 这三类需要 PC 能力或 JS 沙箱，先不标
        assertTrue(!Z.ActionType.MULTI_GESTURE.executable)
        assertTrue(!Z.ActionType.RUN_JS.executable)
        assertTrue(!Z.ActionType.ENCRYPTED.executable)
    }

    // ------------------------------------------------------------ 位置

    @Test
    fun `6 种位置都能解析`() {
        for (t in listOf("location", "image", "text", "color", "node", "aiLoc")) {
            assertNotNull("认不出位置类型 [$t]", Z.PosType.of(t))
        }
    }

    @Test
    fun `坐标位置解析成 Value 而不是数字`() {
        val p = Z.PosData.parse(JSONObject("""{"type":"location","x":"386","y":"536"}"""))!!
        assertEquals(Z.PosType.LOCATION, p.type)
        assertEquals("386", p.x!!.raw)
        assertEquals(386f, p.x!!.toPx(1280, 2f), 0.01f)
    }

    @Test
    fun `图片位置带内嵌 base64 和截图尺寸`() {
        val json = """
        {"type":"image","imageData":{"data":"iVBORw0KGgo=","imageWidth":86,"imageHeight":32,
         "imageLeft":412,"imageTop":980,"screenWidth":1280,"screenHeight":720},
         "limitArea":"320 180 960 540","minSimilarPercent":90}
        """.trimIndent()
        val p = Z.PosData.parse(JSONObject(json))!!
        assertEquals("iVBORw0KGgo=", p.imageData!!.data)
        assertEquals(86, p.imageData!!.width)
        assertEquals(1280, p.imageData!!.screenWidth)
        assertEquals("320 180 960 540", p.limitArea!!.toString())
        assertEquals(90, p.minSimilarPercent)
    }

    // ------------------------------------------------------------ 未知字段

    @Test
    fun `动作里的未知字段会原样写回`() {
        // `futureField` 是 自动精灵 未来版本可能加的字段，我们不认识但不能丢
        val json = """
        {"type":"点击","delayUnit":1,"futureField":{"a":1},"posData":{"type":"location","x":"1","y":"2"}}
        """.trimIndent()
        val a = Z.Action.parse(JSONObject(json))!!
        assertEquals(Z.ActionType.CLICK, a.type)

        val back = a.toJson()
        assertTrue("unknown 字段丢了", back.has("futureField"))
        assertEquals(1, back.getJSONObject("futureField").getInt("a"))
    }

    @Test
    fun `位置里的未知字段也会写回`() {
        val p = Z.PosData.parse(
            JSONObject("""{"type":"location","x":"1","y":"2","weirdField":"keep me"}""")
        )!!
        assertEquals("keep me", p.toJson().getString("weirdField"))
    }

    @Test
    fun `脚本级未知字段同样保留`() {
        val s = Z.Script.parse(
            JSONObject("""{"baseInfo":{"delayUnit":1},"actions":[],"minVerName":"1.9.0"}""")
        )
        assertEquals("1.9.0", s.toJson().getString("minVerName"))
    }

    @Test
    fun `完整脚本往返不丢动作`() {
        val json = """
        {
          "baseInfo": {"repeatCount":1,"delay":"1000","delayUnit":0,"pauseOnFail":true},
          "actions": [
            {"type":"点击","delayUnit":1,"posData":{"type":"location","x":"386","y":"536"}},
            {"type":"滑动","delayUnit":1,
             "startPos":{"type":"location","x":"640","y":"520"},
             "endPos":{"type":"location","x":"640","y":"300"},
             "duration":"300"},
            {"type":"控制执行","controlRunType":"waitDelay"}
          ]
        }
        """.trimIndent()
        val s = Z.Script.parse(JSONObject(json))
        assertEquals(3, s.actions.size)
        assertEquals(Z.ActionType.CLICK, s.actions[0].type)
        assertEquals(Z.ActionType.SWIPE, s.actions[1].type)
        assertEquals(Z.ActionType.CONTROL_WAIT, s.actions[2].type)
        // 时间单位默认是秒，这里显式写 0 = 毫秒，必须读对
        assertEquals(Z.TimeUnit.MILLISECONDS, s.baseInfo.delayUnit)

        // 再解析一遍写出去的结果，动作数不能变
        val round = Z.Script.parse(s.toJson())
        assertEquals(3, round.actions.size)
    }

    @Test
    fun `时间单位默认是秒不是毫秒`() {
        // 协议里 delayUnit 缺省 = 1(秒)。搞错的话 1 秒的等待会变成 1 毫秒
        val a = Z.Action.parse(JSONObject("""{"type":"点击"}"""))!!
        assertEquals(Z.TimeUnit.SECONDS, a.common.delayUnit)
        assertEquals(1_000L, a.common.delayUnit.millisPer)
    }

    // ------------------------------------------------------------ 条件

    @Test
    fun `13 种条件都能解析`() {
        val wires = listOf(
            "image", "textFount", "colorFound", "nodeFound", "aiCloud",
            "limitRunTimes", "scriptRunState", "random", "timeAfter",
            "timeInterval", "jsExpression", "conditionSet", "AroundCondition"
        )
        for (w in wires) assertNotNull("认不出条件类型 [$w]", Z.ConditionType.of(w))
        assertEquals(13, Z.ConditionType.entries.size)
    }

    @Test
    fun `条件的反相与重复检查字段解析正确`() {
        val c = Z.Condition.parse(
            JSONObject("""{"type":"random","runWhenFalse":true,"repeatWhenFalse":true,
                "repeatWhenFalseLimitTimes":3,"percent":30}""")
        )!!
        assertTrue(c.runWhenFalse)
        assertTrue(c.repeatWhenFalse)
        assertEquals(3, c.repeatWhenFalseLimitTimes)
        assertEquals(30, (c.extra["percent"] as Number).toInt())
    }

    @Test
    fun `认不出的条件类型返回 null 而不是当成成立`() {
        // 静默当成"满足"会让动作在错误的时机执行，必须显式失败
        assertEquals(null, Z.Condition.parse(JSONObject("""{"type":"不存在"}""")))
    }
}
