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
        assertEquals("386", r.first { it.label == "X" }.value)
        assertEquals("536", r.first { it.label == "Y" }.value)
    }

    @Test
    fun `坐标字段挂的是取点类型，UI 才能放取点按钮`() {
        val r = row("""{"type":"点击","delayUnit":1,"posData":{"type":"location","x":"1","y":"2"}}""", "X")!!
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
        // 类型行现在是可点的下拉，六个控制执行靠 ·子类型 区分。
        // 子类型显示**中文**：中文界面里孤零零一段 jumpTo 用户看不懂，
        // 而落盘仍是英文 wire（协议不许改）。
        assertEquals("控制执行·跳转到", r.first().value)
        assertEquals("跳转到", r.first { it.label == "控制方式" }.value)
    }

    @Test
    fun `安卓做不了的动作会有一行醒目提示`() {
        val r = rows("""{"type":"设置变量","delayUnit":1}""")
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
    fun `等待的值和单位分成两行，值里不能再混单位`() {
        /*
         * 以前把单位拼进值（"2 s"）又原样写回 delay，
         * 执行器解析 "3 s" 失败 → 静默掉回默认 200ms：
         * 界面上改了 2 秒，实际几乎不停顿。
         * 现在值就是纯数字，单位单独一行、单独写回。
         */
        val sec = rows("""{"type":"点击","delay":"2","delayUnit":1}""")
        assertEquals("2", sec.first { it.label == "等待" }.value)
        assertEquals("s", sec.first { it.label == "等待单位" }.value)

        val ms = rows("""{"type":"点击","delay":"200","delayUnit":0}""")
        assertEquals("200", ms.first { it.label == "等待" }.value)
        assertEquals("ms", ms.first { it.label == "等待单位" }.value)
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
        val img = r.first { it.label == "模板" }
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
            "备注", "跳转标识", "持续(ms)", "等待", "重复次数", "重复间隔",
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
        assertTrue("应提示文字类型：$labels", labels.contains("类型"))
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
    // ------------------------------------------------------------ 本轮审计修复

    @Test
    fun `位置的选择器与偏移都能编辑`() {
        // 之前这三行没有 field，看得见点不动，也不说明为什么
        val rows = rows(
            """{"type":"点击","delayUnit":1,
               "posData":{"type":"location","x":"1","y":"2",
                          "indexNum":"2","xOffset":"5","yOffset":"6"}}"""
        )
        for (label in listOf("选第几个", "X偏移", "Y偏移")) {
            val r = rows.firstOrNull { it.label == label }
            assertTrue("缺 [$label]：${rows.map { it.label }}", r != null)
            assertTrue("[$label] 应该可编辑，实际 ${r!!.kind}", r.kind != ZActionDescriber.Kind.READONLY)
            assertTrue("[$label] 应该有 field", r.field.isNotEmpty())
        }
    }

    @Test
    fun `位置周围条件用摘要显示而不是原始 JSON`() {
        val rows = rows(
            """{"type":"点击","delayUnit":1,
               "posData":{"type":"location","x":"1","y":"2",
                 "filterByAroundConditionSet":{"type":"conditionSet","matchMode":"and"}}}"""
        )
        val r = rows.firstOrNull { it.label.contains("周围条件") }
        assertTrue("应显示周围条件：${rows.map { it.label }}", r != null)
        assertTrue("不该是原始 JSON：${r!!.value}", !r.value.startsWith("{"))
    }

    @Test
    fun `取反和重复检查要同时显示`() {
        // 两个是独立开关，可同时开启。提前 return 会把重试次数吞掉。
        val c = Z.Condition.parse(
            JSONObject("""{"type":"random","runWhenFalse":true,
                "repeatWhenFalse":true,"repeatWhenFalseLimitTimes":5,"percent":30}""")
        )!!
        val s = ZConditionDescriber.summary(c)
        assertTrue("要有取反：$s", s.contains("取反"))
        assertTrue("要有重试次数：$s", s.contains("5"))
        assertTrue("要有百分比：$s", s.contains("30"))
    }

    @Test
    fun `随机条件缺省百分比显示 50`() {
        // 协议默认 50。显示 "?" 会让用户以为没配置好。
        val c = Z.Condition.parse(JSONObject("""{"type":"random"}"""))!!
        assertTrue(ZConditionDescriber.summary(c).contains("50"))
    }
    @Test
    fun `没有条件时也要有一行入口`() {
        val labels = rows("""{"type":"点击","delayUnit":1}""").map { it.label }
        assertTrue("应无条件行：$labels", labels.contains("条件"))
    }

    @Test
    fun `条件和监听行都是可进入的`() {
        val r = rows("""{"type":"点击","delayUnit":1}""")
        val cond = r.firstOrNull { it.label == "条件" }
        val cb = r.firstOrNull { it.label == "监听" }
        assertTrue("应有条件行", cond != null)
        assertTrue("应有监听行", cb != null)
        assertEquals(ZActionDescriber.Kind.ENTER, cb!!.kind)
        assertEquals("callbacks", cb.field)
    }
    // ------------------------------------------------------------ 多指滑动

    @Test
    fun `多指滑动的手指行必须可点`() {
        // ★ 这条钉住一个真实的 bug：手指行一度是 READONLY 且没有 field，
        //   于是 UI 不挂点击，用户点了没反应。
        //   只设 Kind.ENTER 不够 —— makeRow 还要求 field 非空。
        val r = rows("""{"type":"多指滑动","delayUnit":1}""")
        val row = r.firstOrNull { it.label == "手指" }
        assertTrue("应有手指行：${r.map { it.label }}", row != null)
        assertEquals(ZActionDescriber.Kind.ENTER, row!!.kind)
        assertTrue("field 不能为空，否则挂不上点击", row.field.isNotEmpty())
        assertEquals("fingers", row.field)
    }

    @Test
    fun `手指行显示根数`() {
        val r = rows(
            """{"type":"多指滑动","delayUnit":1,
               "multiSwipe":[
                 {"startPos":{"type":"location","x":"1","y":"2"},
                  "endPos":{"type":"location","x":"3","y":"4"}},
                 {"startPos":{"type":"location","x":"5","y":"6"},
                  "endPos":{"type":"location","x":"7","y":"8"}}]}"""
        )
        val row = r.firstOrNull { it.label == "手指" }!!
        assertTrue("应显示 2 根：${row.value}", row.value.contains("2"))
    }

    @Test
    fun `手指行排在每根手指详情之前`() {
        // 用户展开后第一眼该看到"怎么加"，而不是先滚过一堆坐标
        val r = rows(
            """{"type":"多指滑动","delayUnit":1,
               "multiSwipe":[
                 {"startPos":{"type":"location","x":"1","y":"2"},
                  "endPos":{"type":"location","x":"3","y":"4"}}]}"""
        )
        val mgr = r.indexOfFirst { it.label == "手指" }
        val detail = r.indexOfFirst { it.label.startsWith("手指1") }
        assertTrue("管理行应在详情之前：mgr=$mgr detail=$detail", mgr in 0 until detail)
    }

    @Test
    fun `每根手指都摊出起终点`() {
        val labels = rows(
            """{"type":"多指滑动","delayUnit":1,
               "multiSwipe":[
                 {"startPos":{"type":"location","x":"1","y":"2"},
                  "endPos":{"type":"location","x":"3","y":"4"}},
                 {"startPos":{"type":"location","x":"5","y":"6"},
                  "endPos":{"type":"location","x":"7","y":"8"}}]}"""
        ).map { it.label }
        for (must in listOf("手指1 起点 X", "手指1 终点 Y", "手指2 起点 X", "手指2 终点 Y")) {
            assertTrue("缺 [$must]：$labels", labels.any { it == must })
        }
    }
    // ------------------------------------------------------------ 取点/框选按钮

    @Test
    fun `范围和模板行必须带 field 才挂得上按钮`() {
        // 同手指行那次教训：只给 kind 不够，field 空着 UI 就不挂按钮，
        // 用户看到一行字但点不动。
        val r = rows(
            """{"type":"点击图片","delayUnit":1,
               "posData":{"type":"image",
                 "imageData":{"data":"AA","imageWidth":10,"imageHeight":10},
                 "limitArea":"10% 20% 30% 40%"}}"""
        )
        val area = r.firstOrNull { it.kind == ZActionDescriber.Kind.AREA }
        assertTrue("应有范围行", area != null)
        assertEquals("位置.limitArea", area!!.field)

        val img = r.firstOrNull { it.kind == ZActionDescriber.Kind.IMAGE }
        assertTrue("应有模板行", img != null)
        assertEquals("位置.image", img!!.field)
    }
    @Test
    fun `需要查找的类型有预览行`() {
        // 调阈值是模板匹配最费时间的部分，必须有"在画面上看结果"的入口
        for (json in listOf(
            """{"type":"点击图片","delayUnit":1,"posData":{"type":"image",
               "imageData":{"data":"AA","imageWidth":10,"imageHeight":10}}}""",
            """{"type":"点击文字","delayUnit":1,"posData":{"type":"text","text":"确定"}}""",
            """{"type":"点击颜色","delayUnit":1,"posData":{"type":"color","color":"#FF0000"}}"""
        )) {
            val r = rows(json)
            val row = r.firstOrNull { it.field.endsWith(".preview") }
            assertTrue("应有预览行：${r.map { it.label }}", row != null)
            assertEquals(ZActionDescriber.Kind.ENTER, row!!.kind)
        }
    }

    @Test
    fun `固定坐标没有预览行`() {
        // 固定坐标不"查找"，给它一个点了没反应的按钮只会让人困惑
        val r = rows("""{"type":"点击","delayUnit":1,"posData":{"type":"location","x":"1","y":"2"}}""")
        assertTrue("不该有预览行：${r.map { it.label }}", r.none { it.field.endsWith(".preview") })
    }
}