package com.pvz2.auto.zjs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三个预置「状态识别」（从 MAA pipeline 抄的判据）。
 *
 * 重点钉住两件容易出错的事：
 *  - MAA 那套 ROI 是 1920×1080，我们虚拟屏 1280×720，必须 ×2/3；
 *  - 生成的 Condition 能落盘、能再被解析回来（否则生成脚本里是坏节点）。
 */
class PvzRecognizersTest {

    @Test
    fun `ROI 从 1920 换算到 1280`() {
        // 继续挑战 [1074,931,268,75] → ÷1.5
        val r = PvzRecognizers.scaleRoi(1074, 931, 268, 75)
        assertEquals(716, r[0])
        assertEquals(620, r[1])
        assertEquals(178, r[2])
        assertEquals(50, r[3])
        // 开始战斗 [1590,970,301,88]
        val s = PvzRecognizers.scaleRoi(1590, 970, 301, 88)
        assertEquals(1060, s[0])
        assertEquals(646, s[1])
        assertTrue("换算后必须落在 1280 内：${s[0] + s[2]}", s[0] + s[2] <= 1280)
        assertTrue("换算后必须落在 720 内：${s[1] + s[3]}", s[1] + s[3] <= 720)
    }

    @Test
    fun `结算识别是文字条件且带范围`() {
        val c = PvzRecognizers.settleContinue()
        assertEquals(Z.ConditionType.TEXT, c.type)
        assertEquals("继续挑战", c.posData!!.text)
        assertEquals("716 620 894 670", c.posData!!.limitArea!!.toString())
    }

    @Test
    fun `补给入口是颜色条件`() {
        val c = PvzRecognizers.supplyEntry()
        assertEquals(Z.ConditionType.COLOR, c.type)
        assertEquals("#9AD216", c.posData!!.color)
        assertTrue("应带百分比容差", (c.posData!!.similarPercent ?: 0) > 0)
    }

    @Test
    fun `三个预置都能落盘并被解析回来`() {
        val base = """{"repeatCount":1,"delay":"0","delayUnit":0,""" +
            """"screenWidth":1280,"screenHeight":720}"""
        for (ps in PvzRecognizers.PRESETS) {
            val node = org.json.JSONObject().apply {
                put("type", "系统提示")
                put("promptText", "x")
                put("condition", ps.build().toJson())
            }
            val script = ZjsFileFormat.parse("$base\r\n$node\r\n").script
            assertTrue("预置「${ps.label}」生成的动作解析失败", script != null)
            assertEquals(1, script!!.actions.size)
            val cond = script.actions[0].common.condition
            assertEquals("预置「${ps.label}」条件丢了", ps.build().type, cond!!.type)
        }
    }
}
