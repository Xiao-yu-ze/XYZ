package com.pvz2.auto.zjs

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动作摊平成字段行。编辑器直接渲染这些行，所以这里断言的是**用户会看到什么**。
 */
class ZActionDescriberTest {

    private fun action(json: String): Z.Action = Z.Action.parse(JSONObject(json))!!

    private fun rows(json: String) = ZActionDescriber.describe(action(json))

    private fun row(json: String, label: String) =
        rows(json).firstOrNull { it.label == label }

    @Test
    fun `点击会列出类型和坐标`() {
        val r = rows("""{"type":"点击","delayUnit":1,"posData":{"type":"location","x":"386","y":"536"}}""")
        assertEquals("点击", r.first().value)
        assertEquals("386", r.first { it.label == "位置 X" }.value)
        assertEquals("536", r.first { it.label == "位置 Y" }.value)
    }

    @Test
    fun `坐标字段挂的是取点类型，UI 才能放取点按钮`() {
        val r = row("""{"type":"点击","delayUnit":1,"posData":{"type":"location","x":"1","y":"2"}}""", "位置 X")!!
        assertEquals(ZActionDescriber.Kind.COORD, r.kind)
        assertEquals("位置.x", r.field)
    }

    @Test
    fun `滑动会列出两组位置`() {
        val r = rows(
            """{"type":"滑动","delayUnit":1,
               "startPos":{"type":"location","x":"640","y":"520"},
               "endPos":{"type":"location","x":"640","y":"300"}}"""
        )
        assertEquals("640", r.first { it.label == "起点 X" }.value)
        assertEquals("300", r.first { it.label == "终点 Y" }.value)
    }

    @Test
    fun `控制执行要显示 controlRunType，否则五种动作看起来一样`() {
        val r = rows("""{"type":"控制执行","controlRunType":"jumpTo","delayUnit":1}""")
        assertEquals("控制执行", r.first().value)
        assertEquals("jumpTo", r.first { it.label == "控制方式" }.value)
    }

    @Test
    fun `安卓做不了的动作会有一行醒目提示`() {
        val r = rows("""{"type":"多指手势","delayUnit":1}""")
        assertTrue("应提示暂不支持：$r", r.any { it.label.contains("执行") && it.value.contains("暂不支持") })
    }

    @Test
    fun `未知字段也会列出来，用户不会以为脚本缺东西`() {
        val r = rows("""{"type":"点击","delayUnit":1,"vendorThing":"xyz"}""")
        assertTrue("未知字段应可见：$r", r.any { it.label == "vendorThing" && it.value == "xyz" })
    }

    @Test
    fun `被变量覆盖的字段只能看不能改`() {
        // 改了也会被 __vars 覆盖掉，所以标成只读，避免用户白改
        val r = row(
            """{"type":"点击","delayUnit":1,"duration":"100",
               "__vars":{"duration":{"varType":"number","valueExp":"500"}}}""",
            "持续(ms)"
        )!!
        assertEquals(ZActionDescriber.Kind.READONLY, r.kind)
    }

    @Test
    fun `时间单位会写进标签，避免把秒当毫秒`() {
        val r = row("""{"type":"点击","delay":"2","delayUnit":1}""", "等待(s)")
        assertTrue("应按秒显示：$r", r != null)
        val ms = row("""{"type":"点击","delay":"200","delayUnit":0}""", "等待(ms)")
        assertTrue("应按毫秒显示：$ms", ms != null)
    }

    @Test
    fun `重复次数为 0 显示成无限`() {
        val r = row("""{"type":"点击","delayUnit":1,"repeatCount":0}""", "重复次数")!!
        assertEquals("无限", r.value)
    }

    @Test
    fun `图片位置显示尺寸而不是整坨 base64`() {
        val r = rows(
            """{"type":"点击图片","delayUnit":1,
               "posData":{"type":"image","imageData":{"data":"AAAA","imageWidth":86,"imageHeight":32}}}"""
        )
        val img = r.first { it.label == "位置 模板" }
        assertTrue("应显示尺寸：${img.value}", img.value.contains("86×32"))
        assertTrue("不该糊出 base64：${img.value}", !img.value.contains("AAAA"))
    }

    // ------------------------------------------------------------ 条件

    @Test
    fun `条件摘要是一句人话`() {
        val c = Z.Condition.parse(
            JSONObject("""{"type":"image","image":{"type":"image","imageData":{"data":"AA","imageWidth":10,"imageHeight":20}}}""")
        )!!
        assertTrue(ZConditionDescriber.summary(c).contains("图片匹配"))
    }

    @Test
    fun `反相条件会被明确说出来`() {
        // 反相最容易看漏，看漏了整条脚本的逻辑就是反的
        val c = Z.Condition.parse(JSONObject("""{"type":"random","runWhenFalse":true,"percent":30}"""))!!
        assertTrue(ZConditionDescriber.summary(c).contains("取反"))
    }

    @Test
    fun `随机条件显示百分比`() {
        val c = Z.Condition.parse(JSONObject("""{"type":"random","percent":30}"""))!!
        assertTrue(ZConditionDescriber.summary(c).contains("30"))
    }
    // ------------------------------------------------------------ 通用字段齐全

    @Test
    fun `14 个通用字段即使缺省也要列出来`() {
        // 以前是"有什么显示什么"，用户分不清"字段不存在"和"它等于默认值"。
        val labels = rows("""{"type":"点击","delayUnit":1}""").map { it.label }
        for (must in listOf(
            "备注", "跳转标识", "持续(ms)", "等待单位", "重复次数",
            "快速重复", "禁用", "条件"
        )) {
            assertTrue("缺 [$must]：$labels", labels.any { it.startsWith(must) })
        }
    }

    @Test
    fun `起点终点不再单列"类型"行`() {
        // 坐标类型对用户是格式细节，单列一行只占地方。
        val labels = rows(
            """{"type":"滑动","delayUnit":1,
               "startPos":{"type":"location","x":"640","y":"520"},
               "endPos":{"type":"location","x":"640","y":"300"}}"""
        ).map { it.label }
        assertTrue("不该有 [起点 类型]：$labels", labels.none { it == "起点 类型" })
        assertTrue("不该有 [终点 类型]：$labels", labels.none { it == "终点 类型" })
        // 但坐标本身必须在
        assertTrue(labels.contains("起点 X"))
        assertTrue(labels.contains("终点 Y"))
    }

    @Test
    fun `非坐标类型仍然提示类型`() {
        // 图片/文字/颜色要告诉用户"这一条是按什么找的"
        val labels = rows(
            """{"type":"点击文字","delayUnit":1,"posData":{"type":"text","text":"开始"}}"""
        ).map { it.label }
        assertTrue("应提示文字类型：$labels", labels.contains("位置 类型"))
    }

    @Test
    fun `变量覆盖会单独列出来`() {
        val rows = rows(
            """{"type":"点击","delayUnit":1,
               "__vars":{"duration":{"varType":"number","valueExp":"500"}}}"""
        )
        val v = rows.firstOrNull { it.label.contains("变量") }
        assertTrue("应列出变量：$rows", v != null)
        assertTrue("应显示表达式：${v!!.value}", v.value.contains("500"))
    }
}