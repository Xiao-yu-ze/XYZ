package com.pvz2.auto.zjs

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 条件里内嵌的位置对象，**键名必须随条件类型变化**。
 *
 * 依据 FIELDS.md:266-269：
 *
 * | type | 内嵌键 |
 * |---|---|
 * | `image` | `image` |
 * | `textFount` | `text` |
 * | `colorFound` | **`colorData`** |
 * | `nodeFound` | **`node`** |
 *
 * ## 为什么这一条特别要紧
 *
 * 键名写错时 JSON 结构完全合法，导出的文件不报任何错。
 * 但自动精灵按 `type` 去取对应的键，取不到就当**条件恒为 false**
 * （SPEC.md:1031-1035：条件异常被吞掉，按不成立处理）。
 *
 * 用户看到的是「这条动作本来有颜色条件，导出后条件静默失效，
 * 动作要么从不执行、要么每轮都执行」，而界面上一切正常。
 */
class ZConditionKeyTest {

    private fun cond(json: String): Z.Condition = Z.Condition.parse(JSONObject(json))!!

    @Test
    fun `colorFound 的键名是 colorData 不是 image`() {
        val c = cond(
            """{"type":"colorFound",
               "colorData":{"type":"color","color":"FF0000","similarPercent":90}}"""
        )
        val json = c.toJson()
        assertTrue("必须是 colorData：$json", json.has("colorData"))
        assertTrue("不能写成 image：$json", !json.has("image"))
        assertEquals("FF0000", json.getJSONObject("colorData").getString("color"))
    }

    @Test
    fun `textFount 的键名是 text`() {
        val c = cond("""{"type":"textFount","text":{"type":"text","text":"开始"}}""")
        val json = c.toJson()
        assertTrue(json.has("text"))
        assertEquals("开始", json.getJSONObject("text").getString("text"))
    }

    @Test
    fun `image 的键名是 image`() {
        val c = cond("""{"type":"image","image":{"type":"image","imageData":{"data":"AA"}}}""")
        assertTrue(c.toJson().has("image"))
    }

    @Test
    fun `没有位置的 conditionSet 不会凭空写出键`() {
        val c = cond("""{"type":"conditionSet","matchMode":"and"}""")
        val json = c.toJson()
        assertNull(json.optJSONObject("image"))
        assertNull(json.optJSONObject("text"))
        assertNull(json.optJSONObject("colorData"))
        assertNull(json.optJSONObject("node"))
    }
}
