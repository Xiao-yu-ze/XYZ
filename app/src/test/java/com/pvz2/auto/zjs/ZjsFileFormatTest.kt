package com.pvz2.auto.zjs

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `.zjs` 的真实文件格式（NDJSON + CRLF + baseInfo 注入字段）。
 *
 * 依据 SPEC.md:670-717。格式错了我们导出的文件脚本格式打不开，
 * 而用户要到导出之后、在另一个程序里才发现。
 */
class ZjsFileFormatTest {

    private fun script(json: String) = Z.Script.parse(JSONObject(json))

    @Test
    fun `每行一个 JSON，不是一个大对象`() {
        val s = script(
            """{"baseInfo":{"repeatCount":1,"delayUnit":1},
               "actions":[{"type":"点击","delayUnit":1},{"type":"滑动","delayUnit":1}]}"""
        )
        val text = ZjsFileFormat.stringify(s)
        val lines = text.split("\r\n")
        assertEquals("1 行 baseInfo + 2 行动作", 3, lines.size)

        // 每一行都必须是能单独解析的 JSON
        for (l in lines) assertEquals(l, l, JSONObject(l).toString())
    }

    @Test
    fun `换行必须是 CRLF`() {
        val s = script("""{"baseInfo":{},"actions":[{"type":"点击","delayUnit":1}]}""")
        val text = ZjsFileFormat.stringify(s)
        assertTrue("应含 CRLF", text.contains("\r\n"))
        // 不能有裸 LF
        assertTrue("不该有裸 LF", !text.replace("\r\n", "").contains("\n"))
    }

    @Test
    fun `baseInfo 注入 8 个字段`() {
        val s = script("""{"baseInfo":{"repeatCount":1},"actions":[]}""")
        val first = JSONObject(ZjsFileFormat.stringify(s).split("\r\n").first())

        for (key in listOf(
            "minVerName", "minVerCode", "count",
            "savedVerName", "savedVerCode"
        )) {
            assertTrue("缺注入字段 [$key]：$first", first.has(key))
        }
        assertTrue("应该有 count", first.has("count"))
        assertTrue("minVerName 不能为空", first.getString("minVerName").isNotBlank())
    }

    @Test
    fun `给了屏幕尺寸就写进去`() {
        val s = script("""{"baseInfo":{},"actions":[]}""")
        val first = JSONObject(
            ZjsFileFormat.stringify(s, screenWidth = 1280, screenHeight = 720, screenDpi = 240f)
                .split("\r\n").first()
        )
        assertEquals(1280, first.getInt("lastSaveScreenWidth"))
        assertEquals(720, first.getInt("lastSaveScreenHeight"))
        assertEquals(240.0, first.getDouble("lastSaveScreenDpi"), 0.01)
    }

    @Test
    fun `没给屏幕尺寸就不写，而不是写 0`() {
        // 写 0 会让脚本格式以为屏幕是 0×0，坐标换算全错
        val s = script("""{"baseInfo":{},"actions":[]}""")
        val first = JSONObject(ZjsFileFormat.stringify(s).split("\r\n").first())
        assertTrue(!first.has("lastSaveScreenWidth"))
    }

    @Test
    fun `count 递归统计监听里的动作`() {
        // SPEC.md:702 `e.getActionsCount()` 是递归的
        val s = script(
            """{"baseInfo":{},"actions":[
                 {"type":"点击","delayUnit":1,
                  "scriptCallbacks":{
                    "afterExecSuc":{"type":"点击","delayUnit":1},
                    "afterExecFail":{"type":"点击","delayUnit":1}}},
                 {"type":"滑动","delayUnit":1}]}"""
        )
        val first = JSONObject(ZjsFileFormat.stringify(s).split("\r\n").first())
        // 1 个主动作 + 2 个监听 + 1 个滑动 = 4
        assertEquals(4, first.getInt("count"))
    }

    // ------------------------------------------------------------ 解析

    @Test
    fun `能读回自己写出去的文件`() {
        val src = script(
            """{"baseInfo":{"repeatCount":2,"delayUnit":1},
               "actions":[
                 {"type":"点击","desc":"A","delayUnit":1},
                 {"type":"滑动","desc":"B","delayUnit":1}]}"""
        )
        val text = ZjsFileFormat.stringify(src)
        val back = ZjsFileFormat.parse(text)

        assertNull(back.error)
        assertEquals(2, back.script!!.actions.size)
        assertEquals("A", back.script!!.actions[0].common.desc)
        assertEquals(2, back.script!!.baseInfo.repeatCount)
    }

    @Test
    fun `LF 换行的老文件也能读`() {
        val text = "{\"baseInfo\":{}}\n{\"type\":\"点击\",\"delayUnit\":1}\n"
        val back = ZjsFileFormat.parse(text)
        assertNull(back.error)
        assertEquals(1, back.script!!.actions.size)
    }

    @Test
    fun `坏行跳过但记下行号，其余动作照常读`() {
        val text = "{\"baseInfo\":{}}\r\n{\"type\":\"点击\",\"delayUnit\":1}\r\nnot json\r\n"
        val back = ZjsFileFormat.parse(text)
        assertEquals(1, back.script!!.actions.size)
        assertEquals("应记下坏行", listOf(3), back.badLines)
    }

    @Test
    fun `linkedFile 行不会丢`() {
        // 我们还不支持内嵌子脚本，但绝不能丢 —— 丢了用户的脚本就不完整
        val text = "{\"baseInfo\":{}}\r\n{\"type\":\"linkedFile\",\"name\":\"a\",\"content\":\"x\"}\r\n"
        val back = ZjsFileFormat.parse(text)
        assertEquals(1, back.linkedFiles.size)
        assertEquals("a", back.linkedFiles[0].getString("name"))
    }

    @Test
    fun `空文件返回明确错误`() {
        assertNotNull(ZjsFileFormat.parse("").error)
        assertNotNull(ZjsFileFormat.parse("   \r\n  ").error)
    }

    @Test
    fun `第一行不是 baseInfo 时给出可读的错误`() {
        val back = ZjsFileFormat.parse("{\"actions\":[]}\r\n")
        // baseInfo.parse 对缺字段是宽容的，所以这里应该能过
        assertNull(back.error)

        val bad = ZjsFileFormat.parse("not json at all\r\n")
        assertNotNull(bad.error)
        assertTrue("错误信息要能看懂：${bad.error}", bad.error!!.contains("baseInfo"))
    }
    // ------------------------------------------------------------ 未知字段往返

    @Test
    fun `脚本级未知字段并进第 1 行，不会静默消失`() {
        // NDJSON 里只有第 1 行能装"脚本级"信息。
        // 用户脚本里可能有 fromStore / encrypt 这类我们没建模的字段。
        val s = script("""{"baseInfo":{"repeatCount":1},"actions":[],"fromStore":{"id":"x"}}""")
        val first = JSONObject(ZjsFileFormat.stringify(s).split("\r\n").first())
        assertTrue("fromStore 应该被保住：$first", first.has("fromStore"))
        assertEquals("x", first.getJSONObject("fromStore").getString("id"))
    }

    @Test
    fun `未知字段往返回来还在`() {
        val text = "{\"baseInfo\":{},\"vendorField\":\"keep\"}\r\n" +
            "{\"type\":\"点击\",\"delayUnit\":1,\"actionVendor\":\"also keep\"}\r\n"
        val back = ZjsFileFormat.parse(text)
        val s = back.script!!
        assertEquals("keep", s.extra["vendorField"])
        assertEquals("also keep", s.actions[0].extra["actionVendor"])

        // 再写出去，两个都还在
        val out = ZjsFileFormat.stringify(s)
        assertTrue(out.contains("vendorField"))
        assertTrue(out.contains("actionVendor"))
    }

    @Test
    fun `注入字段不会把用户的旧值顶掉又写重复`() {
        // 文件里已有 minVerName 时，以我们的为准（注入的优先），
        // 但不能出现两个同名键
        val text = "{\"baseInfo\":{},\"minVerName\":\"0.0.1\"}\r\n"
        val back = ZjsFileFormat.parse(text)
        val first = JSONObject(ZjsFileFormat.stringify(back.script!!).split("\r\n").first())
        assertEquals(ZjsFileFormat.SAVED_VER_NAME, first.getString("minVerName"))
    }

    @Test
    fun `真实 zjs 的样例能读进来`() {
        // 三行：baseInfo + 两个动作，CRLF
        val real = "{\"repeatCount\":0,\"delayUnit\":0,\"pauseOnFail\":true,\"count\":2,\"minVerName\":\"26.0.0\"}\r\n" +
            "{\"type\":\"点击\",\"delayUnit\":0,\"posData\":{\"type\":\"location\",\"x\":\"640\",\"y\":\"360\"}}\r\n" +
            "{\"type\":\"滑动\",\"delayUnit\":0,\"startPos\":{\"type\":\"location\",\"x\":\"1\",\"y\":\"2\"},\"endPos\":{\"type\":\"location\",\"x\":\"3\",\"y\":\"4\"}}\r\n"
        val back = ZjsFileFormat.parse(real)
        assertNull(back.error)
        assertEquals(2, back.script!!.actions.size)
        // baseInfo 的 repeatCount=0 表示无限，要读对
        assertEquals(0, back.script!!.baseInfo.repeatCount)
        // 注入字段不该被当成"未知"塞进 extra
        assertTrue("count 不该进 extra", !back.script!!.extra.containsKey("count"))
        assertTrue("minVerName 不该进 extra", !back.script!!.extra.containsKey("minVerName"))
    }
}