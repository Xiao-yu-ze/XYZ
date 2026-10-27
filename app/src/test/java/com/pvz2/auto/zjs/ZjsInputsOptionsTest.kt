package com.pvz2.auto.zjs

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「设置变量」的 `inputOptions` —— 运行前弹窗要不要变成下拉。
 *
 * 背景：心塔脚本的 option（起始关卡 / 喂豆间隔）以前只能让用户手打数字，
 * 现在脚本里给 `value.inputOptions` 就直接出下拉。
 */
class ZjsInputsOptionsTest {

    private fun script(varJson: String): Z.Script {
        val a = Z.Action.parse(
            JSONObject(
                """{"type":"设置变量","vars":[$varJson],"delay":"0"}"""
            )
        )
        return Z.Script(actions = listOf(requireNotNull(a) { "解析失败" }))
    }

    @Test
    fun `给了下拉选项就能收集到`() {
        val items = ZjsInputs.collect(
            script(
                """{"name":"心塔_起始关卡",
                    "value":{"showInput":true,"inputOptions":["1关","2关","3-149关"]}}"""
            )
        )
        assertEquals(1, items.size)
        assertEquals(listOf("1关", "2关", "3-149关"), items[0].options)
    }

    @Test
    fun `没给选项就是普通文本框`() {
        val items = ZjsInputs.collect(
            script("""{"name":"随手填","value":{"showInput":true}}""")
        )
        assertEquals(1, items.size)
        assertTrue(items[0].options.isEmpty())
    }

    @Test
    fun `选项也能写成逗号分隔的字符串`() {
        val items = ZjsInputs.collect(
            script("""{"name":"间隔","value":{"showInput":true,"inputOptions":"5秒,6秒,7秒"}}""")
        )
        assertEquals(listOf("5秒", "6秒", "7秒"), items[0].options)
    }
}
