package com.pvz2.auto.zjs

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 条件的增删改。
 *
 * 依据 FIELDS.md 第 4 节：13 种条件、8 个通用字段。
 */
class ZConditionEditorTest {

    @Test
    fun `13 种类型都能新建`() {
        assertEquals(13, ZConditionEditor.TYPE_LABELS.size)
        for ((type, label) in ZConditionEditor.TYPE_LABELS) {
            val c = ZConditionEditor.create(type)
            assertEquals("[$label] 类型应保持", type, c.type)
            // 新建的条件落盘后必须能被读回来
            val back = Z.Condition.parse(c.toJson())
            assertNotNull("[$label] 应能往返", back)
            assertEquals(type, back!!.type)
        }
    }

    @Test
    fun `随机条件默认 50 百分比`() {
        // 空条件执行时必然判 false，用户会以为动作永远不跑。
        // 所以每种类型都要给个能跑的默认值。
        val c = ZConditionEditor.create(Z.ConditionType.RANDOM)
        assertEquals(50, c.extra["percent"])
    }

    @Test
    fun `限制运行次数默认 1 次`() {
        val c = ZConditionEditor.create(Z.ConditionType.LIMIT_RUN_TIMES)
        assertEquals(1, c.extra["limitTimes"])
        assertEquals("afterFinish", c.extra["resetTime"])
    }

    @Test
    fun `位置类条件带对应类型的空位置`() {
        // 键名必须和类型匹配，否则落盘后自动精灵取不到（FIELDS.md:266-269）
        val img = ZConditionEditor.create(Z.ConditionType.IMAGE)
        assertEquals(Z.PosType.IMAGE, img.posData!!.type)

        val color = ZConditionEditor.create(Z.ConditionType.COLOR)
        assertEquals(Z.PosType.COLOR, color.posData!!.type)
        // 落盘键名是 colorData
        assertTrue(color.toJson().has("colorData"))
        assertTrue(!color.toJson().has("image"))
    }

    @Test
    fun `改条件的标量字段`() {
        val c = ZConditionEditor.create(Z.ConditionType.RANDOM)
        val a = ZConditionEditor.apply(c, "percent", "30")!!
        assertEquals(30, a.extra["percent"])
        // 原对象不变
        assertEquals(50, c.extra["percent"])
    }

    @Test
    fun `改取反和重复检查`() {
        var c = ZConditionEditor.create(Z.ConditionType.RANDOM)
        c = ZConditionEditor.apply(c, "runWhenFalse", "是")!!
        c = ZConditionEditor.apply(c, "repeatWhenFalse", "是")!!
        c = ZConditionEditor.apply(c, "repeatWhenFalseLimitTimes", "5")!!

        assertTrue(c.runWhenFalse)
        assertTrue(c.repeatWhenFalse)
        assertEquals(5, c.repeatWhenFalseLimitTimes)
    }

    @Test
    fun `换类型保留通用开关`() {
        // 用户勾了取反、写了备注，换类型时不该丢
        var c = ZConditionEditor.create(Z.ConditionType.RANDOM)
        c = ZConditionEditor.apply(c, "runWhenFalse", "是")!!
        c = ZConditionEditor.apply(c, "desc", "我的条件")!!

        val switched = ZConditionEditor.changeType(c, Z.ConditionType.IMAGE)
        assertEquals(Z.ConditionType.IMAGE, switched.type)
        assertTrue("取反要保留", switched.runWhenFalse)
        assertEquals("我的条件", switched.desc)
        // 位置对象要是新类型该有的
        assertEquals(Z.PosType.IMAGE, switched.posData!!.type)
    }

    @Test
    fun `不认识的字段返回 null`() {
        val c = ZConditionEditor.create(Z.ConditionType.RANDOM)
        assertNull(ZConditionEditor.apply(c, "不存在的", "x"))
    }

    // ------------------------------------------------------------ 摊平成行

    @Test
    fun `条件摊平后标量和开关都有`() {
        var c = ZConditionEditor.create(Z.ConditionType.RANDOM)
        c = ZConditionEditor.apply(c, "repeatWhenFalse", "是")!!

        val rows = ZConditionEditor.describe(c)
        val labels = rows.map { it.label }
        for (must in listOf("条件类型", "百分比", "备注", "取反", "禁用", "不成立时重复")) {
            assertTrue("缺 [$must]：$labels", labels.any { it == must })
        }
        // 开了重复检查才显示次数
        assertTrue(labels.contains("最多重试"))
    }

    @Test
    fun `没开重复检查时不显示次数`() {
        val c = ZConditionEditor.create(Z.ConditionType.RANDOM)
        assertTrue(ZConditionEditor.describe(c).none { it.label == "最多重试" })
    }

    @Test
    fun `条件类型显示中文名`() {
        val c = ZConditionEditor.create(Z.ConditionType.IMAGE)
        val row = ZConditionEditor.describe(c).first()
        assertEquals("出现图片", row.value)
    }

    @Test
    fun `开关字段挂的是枚举让我们能下拉选`() {
        val c = ZConditionEditor.create(Z.ConditionType.RANDOM)
        val row = ZConditionEditor.describe(c).first { it.label == "取反" }
        assertEquals(ZActionDescriber.Kind.ENUM, row.kind)
        assertEquals(listOf("否", "是"), row.options)
    }

    @Test
    fun `条件组显示匹配方式`() {
        val c = ZConditionEditor.create(Z.ConditionType.CONDITION_SET)
        val rows = ZConditionEditor.describe(c)
        val mode = rows.firstOrNull { it.label == "匹配方式" }
        assertNotNull(mode)
        assertEquals("and", mode!!.value)
        assertEquals(listOf("and", "or", "count"), mode.options)
    }

    @Test
    fun `坐标类型的位置条件显示坐标行并挂取点类型`() {
        // location 类型才有 x/y。/位置周围条件 这类会带坐标基准点。
        val c = Z.Condition.parse(
            JSONObject("""{"type":"image","image":{"type":"location","x":"100","y":"200"}}""")
        )!!
        val rows = ZConditionEditor.describe(c)
        val x = rows.firstOrNull { it.label == "位置 X" }
        assertTrue("应有坐标行：${rows.map { it.label }}", x != null)
        assertEquals(ZActionDescriber.Kind.COORD, x!!.kind)
        assertEquals("100", x.value)
    }

    @Test
    fun `文字条件显示文字而不是坐标`() {
        // textFount 的位置是 text 对象，没有 x/y —— 显示"文字"才对
        val c = Z.Condition.parse(
            JSONObject("""{"type":"textFount","text":{"type":"text","text":"开始"}}""")
        )!!
        val rows = ZConditionEditor.describe(c)
        assertTrue("应有文字行：${rows.map { it.label }}", rows.any { it.label == "文字" })
        assertTrue("不该有坐标行", rows.none { it.label == "位置 X" })
    }
}
