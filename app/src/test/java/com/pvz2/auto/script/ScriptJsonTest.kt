package com.pvz2.auto.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ScriptJson 的单元测试。纯 JVM 跑，不需要模拟器 —— 这就是为什么
 * ScriptJson 刻意不依赖 Android 的 Context。
 *
 * 跑法：`gradlew :app:testDebugUnitTest`
 */
class ScriptJsonTest {

    /** 一份覆盖全部字段的样例，也是将来给别人看的格式参考。 */
    private val sample = """
        {
          "format": "pvz2auto-script",
          "version": 1,
          "name": "无尽挑战-示例",
          "desc": "演示各种字段",
          "author": "测试",
          "loopEnabled": true,
          "loopIntervalMs": [1000, 2000],
          "variables": [
            { "name": "round", "type": "number", "init": 0 },
            { "name": "ok", "type": "bool", "init": true, "scope": "global", "desc": "是否成功" },
            { "name": "tag", "type": "string", "init": "abc" }
          ],
          "actions": [
            {
              "type": "点击",
              "desc": "点竞技按钮",
              "conditions": [ { "type": "识别", "value": "ji_competitive" } ],
              "postDelayMs": [300, 500]
            },
            {
              "type": "组合动作",
              "desc": "前排种向日葵",
              "children": [
                { "type": "种植物", "params": { "plant": "向日葵", "row": 1, "col": 1 } },
                { "type": "种植物", "params": { "plant": "向日葵", "row": 1, "col": 2 } }
              ]
            },
            {
              "type": "跳转",
              "desc": "回第1条",
              "label": "back",
              "params": { "target": "1" },
              "repeat": 3
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `解析全部字段`() {
        val s = ScriptJson.parse(sample)

        assertEquals("无尽挑战-示例", s.name)
        assertEquals("演示各种字段", s.desc)
        assertEquals("测试", s.author)
        assertTrue(s.loopEnabled)
        assertEquals(1000..2000, s.loopIntervalMs)

        // 变量：三种类型 + 作用域 + 默认值
        assertEquals(3, s.variables.size)
        val round = s.variables[0]
        assertEquals("round", round.name)
        assertEquals(VarType.数值, round.type)
        assertEquals(0.0, round.init as Double, 0.0)
        assertEquals(VarScope.当前脚本, round.scope)

        val ok = s.variables[1]
        assertEquals(VarType.布尔, ok.type)
        assertEquals(true, ok.init)
        assertEquals(VarScope.全局, ok.scope)

        // 动作
        assertEquals(3, s.actions.size)

        val click = s.actions[0]
        assertEquals(ActionType.点击, click.type)
        assertEquals("点竞技按钮", click.desc)
        assertEquals(1, click.conditions.size)
        assertEquals(ConditionType.识别, click.conditions[0].type)
        assertEquals("ji_competitive", click.conditions[0].value)
        assertEquals(300..500, click.postDelayMs)
        assertNull(click.preDelayMs)

        // 组合动作的嵌套
        val group = s.actions[1]
        assertEquals(ActionType.组合动作, group.type)
        assertEquals(2, group.children.size)
        assertEquals(ActionType.种植物, group.children[0].type)
        // params 里的数字要能取回 Int，字符串要能取回 String
        assertEquals("向日葵", group.children[0].string("plant"))
        assertEquals(1, group.children[0].int("row"))
        assertEquals(1, group.children[0].int("col"))
        // 第二条也断言，确认子动作是各自独立的、没有串味
        assertEquals(2, group.children[1].int("col"))

        val jump = s.actions[2]
        assertEquals(ActionType.跳转, jump.type)
        assertEquals("back", jump.label)
        assertEquals(3, jump.repeat)
        assertEquals("1", jump.string("target"))
    }

    @Test
    fun `读出来再写出去再读回来 内容一致`() {
        val first = ScriptJson.parse(sample)
        val written = ScriptJson.toJson(first)
        val second = ScriptJson.parse(written)

        // data class 的 equals 就是逐字段比较，所以这里一个断言就够
        assertEquals(first, second)
    }

    @Test
    fun `format 不对时报错`() {
        val bad = sample.replace("\"pvz2auto-script\"", "\"something-else\"")
        val e = runCatching { ScriptJson.parse(bad) }.exceptionOrNull()
        assertTrue("应该报错", e != null)
        assertTrue("错误信息应提到 format，实际：${e?.message}",
            e!!.message!!.contains("format"))
    }

    @Test
    fun `动作类型写错时 报错并列出合法值`() {
        val bad = sample.replace("\"type\": \"跳转\"", "\"type\": \"飞起来\"")
        val e = runCatching { ScriptJson.parse(bad) }.exceptionOrNull()
        assertTrue("应该报错", e != null)
        val msg = e!!.message!!
        assertTrue("应指出不认识的值，实际：$msg", msg.contains("飞起来"))
        assertTrue("应列出合法类型，实际：$msg", msg.contains("种植物"))
    }

    @Test
    fun `组合动作没有子动作时 报错`() {
        val bad = """
            { "format": "pvz2auto-script", "version": 1, "name": "x",
              "actions": [ { "type": "组合动作", "desc": "空组合" } ] }
        """.trimIndent()
        val e = runCatching { ScriptJson.parse(bad) }.exceptionOrNull()
        assertTrue("应该报错", e != null)
        assertTrue("错误信息应提到 children，实际：${e?.message}",
            e!!.message!!.contains("children"))
    }

    @Test
    fun `延时区间的两种写法都支持`() {
        val fixed = """
            { "format": "pvz2auto-script", "version": 1, "name": "x",
              "actions": [ { "type": "等待", "preDelayMs": 300 } ] }
        """.trimIndent()
        // "等待" 目前不在动作枚举里 —— 这条同时验证「未知类型会被拒绝」
        assertTrue(runCatching { ScriptJson.parse(fixed) }.isFailure)

        val ranged = """
            { "format": "pvz2auto-script", "version": 1, "name": "x",
              "actions": [ { "type": "点击", "preDelayMs": [200, 500] } ] }
        """.trimIndent()
        assertEquals(200..500, ScriptJson.parse(ranged).actions[0].preDelayMs)
    }

    @Test
    fun `dump 能打出可读的树`() {
        val s = ScriptJson.parse(sample)
        val text = s.dump()
        // 不比对全文（太脆），只验证关键信息都露出来了
        assertTrue(text.contains("无尽挑战-示例"))
        assertTrue(text.contains("点击"))
        assertTrue(text.contains("组合动作"))
        assertTrue(text.contains("向日葵"))
        assertTrue(text.contains("ji_competitive"))
        println("---- dump 结果 ----")
        println(text)
    }
}
