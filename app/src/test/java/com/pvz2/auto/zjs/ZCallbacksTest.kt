package com.pvz2.auto.zjs

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 监听槽位（scriptCallbacks）。
 *
 * 依据 FIELDS.md:285-303：
 *  - 7 个槽位，**每个槽位是一个完整动作**，不是数组；
 *  - 空 map 视为"未设置"，落盘时不写。
 *
 * ## 为什么必须建模而不是扔进 extra
 *
 * 扔进 extra 的话结构不会坏，但用户在编辑器里**看不到** ——
 * 一个挂了 7 段监听的动作展开后干干净净，用户以为没挂，
 * 而那些监听在自动精灵里是会真的跑的。
 */
class ZCallbacksTest {

    private fun action(json: String): Z.Action = Z.Action.parse(JSONObject(json))!!

    @Test
    fun `7 个槽位都能解析`() {
        val slots = listOf(
            "beforeCondition", "afterConditionSuc", "afterConditionFail",
            "beforeStartExec", "afterExecSuc", "afterExecFail", "afterExecFinish"
        )
        assertEquals(7, Z.CallbackSlot.entries.size)
        for (s in slots) {
            assertTrue("认不出槽位 [$s]", Z.CallbackSlot.of(s) != null)
        }
    }

    @Test
    fun `槽位里装的是完整动作而不是数组`() {
        val a = action(
            """{"type":"点击","delayUnit":1,
               "scriptCallbacks":{"afterExecSuc":{"type":"点击","desc":"成功后点一下","delayUnit":1}}}"""
        )
        assertEquals(1, a.callbacks.size)
        val cb = a.callbacks[Z.CallbackSlot.AFTER_EXEC_SUC]!!
        assertEquals(Z.ActionType.CLICK, cb.type)
        assertEquals("成功后点一下", cb.common.desc)
    }

    @Test
    fun `监听往返后槽位名和内容都不变`() {
        val src = """
        {"type":"点击","delayUnit":1,
         "scriptCallbacks":{
           "beforeCondition":{"type":"控制执行","controlRunType":"waitDelay","delayUnit":1},
           "afterExecFail":{"type":"系统提示","delayUnit":1}}}
        """.trimIndent()
        val back = action(src).toJson()
        val cb = back.getJSONObject("scriptCallbacks")
        assertEquals("控制执行", cb.getJSONObject("beforeCondition").getString("type"))
        assertEquals("waitDelay", cb.getJSONObject("beforeCondition").getString("controlRunType"))
        assertEquals("系统提示", cb.getJSONObject("afterExecFail").getString("type"))
    }

    @Test
    fun `没有监听时不写 scriptCallbacks 键`() {
        // FIELDS.md:303：空 map 视为未设置（hasValue() = keys.length > 0）
        val back = action("""{"type":"点击","delayUnit":1}""").toJson()
        assertFalse("空监听不该落盘：$back", back.has("scriptCallbacks"))
    }

    @Test
    fun `认不出的槽位名不会让整条动作解析失败`() {
        // 将来版本可能加第 8 个槽位。认不出就跳过，
        // 但动作本身必须还能读出来。
        val a = action(
            """{"type":"点击","delayUnit":1,
               "scriptCallbacks":{"futureSlot":{"type":"点击","delayUnit":1},
                                  "afterExecSuc":{"type":"点击","delayUnit":1}}}"""
        )
        assertEquals(1, a.callbacks.size)
        assertEquals(Z.ActionType.CLICK, a.type)
    }

    @Test
    fun `监听会在描述器里显示出来`() {
        // 用户必须能看见"这条动作挂了监听"，否则以为没挂
        val a = action(
            """{"type":"点击","delayUnit":1,
               "scriptCallbacks":{"afterExecSuc":{"type":"点击","desc":"成功后点一下","delayUnit":1}}}"""
        )
        val rows = ZActionDescriber.describe(a)
        val row = rows.firstOrNull { it.label.startsWith("监听") }
        assertTrue("应显示监听行：${rows.map { it.label }}", row != null)
        assertTrue("应显示槽位名：${row!!.label}", row.label.contains("运行成功后"))
        assertTrue("应显示监听动作：${row.value}", row.value.contains("成功后点一下"))
    }

    @Test
    fun `多个监听按固定顺序显示`() {
        // 不按 Map 遍历顺序 —— 用户要能对照着看
        val a = action(
            """{"type":"点击","delayUnit":1,
               "scriptCallbacks":{
                 "afterExecFinish":{"type":"点击","desc":"C","delayUnit":1},
                 "beforeCondition":{"type":"点击","desc":"A","delayUnit":1},
                 "afterExecSuc":{"type":"点击","desc":"B","delayUnit":1}}}"""
        )
        val labels = ZActionDescriber.describe(a).filter { it.label.startsWith("监听") }.map { it.value }
        assertEquals(listOf("A", "B", "C"), labels)
    }
}
