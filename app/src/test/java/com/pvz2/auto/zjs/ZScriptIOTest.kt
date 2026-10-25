package com.pvz2.auto.zjs

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 脚本读写。核心是**往返不丢字段** —— 导入别人的脚本、改两笔、再导出，
 * 结果必须和原文件在语义上一致。
 */
class ZScriptIOTest {

    private fun tempDir(): File = Files.createTempDirectory("zscript").toFile()

    @Test
    fun `保存再读取，动作数量和未知字段都保留`() {
        val dir = tempDir()
        val path = File(dir, "s.json").absolutePath
        val original = """
        {
          "baseInfo": {"repeatCount": 3, "delayUnit": 1, "pauseOnFail": true},
          "actions": [
            {"type":"点击","delayUnit":1,"posData":{"type":"location","x":"100","y":"200"},
             "vendorExtra":"keep me"},
            {"type":"滑动","delayUnit":1,
             "startPos":{"type":"location","x":"640","y":"520"},
             "endPos":{"type":"location","x":"640","y":"300"}}
          ],
          "scriptExtra": {"a": 1}
        }
        """.trimIndent()

        val script = Z.Script.parse(JSONObject(original))
        assertTrue(ZScriptIO.save(script, path))

        val loaded = ZScriptIO.load(path)
        assertNull(loaded.error)
        val back = loaded.script!!
        assertEquals(2, back.actions.size)
        // 未知字段必须还在
        assertEquals("keep me", back.actions[0].extra["vendorExtra"])
        assertNotNull(back.extra["scriptExtra"])
        assertEquals(3, back.baseInfo.repeatCount)
    }

    @Test
    fun `文件不存在时返回错误而不是崩溃`() {
        val loaded = ZScriptIO.load("/definitely/not/here/s.json")
        assertNull(loaded.script)
        assertNotNull(loaded.error)
    }

    @Test
    fun `空文件返回明确错误`() {
        val dir = tempDir()
        val f = File(dir, "empty.json")
        f.writeText("")
        val loaded = ZScriptIO.load(f.absolutePath)
        assertNotNull(loaded.error)
        assertTrue(loaded.error!!.contains("空"))
    }

    @Test
    fun `坏 JSON 返回错误而不是崩溃`() {
        val dir = tempDir()
        val f = File(dir, "bad.json")
        f.writeText("{ this is not json")
        val loaded = ZScriptIO.load(f.absolutePath)
        assertNull(loaded.script)
        assertNotNull(loaded.error)
    }

    // ------------------------------------------------------------ 校验

    @Test
    fun `点击缺位置会被报出来`() {
        val s = Z.Script.parse(
            JSONObject("""{"actions":[{"type":"点击","delayUnit":1}]}""")
        )
        val issues = ZScriptIO.validate(s)
        assertTrue("应报缺位置：$issues", issues.any { it.contains("缺位置") })
    }

    @Test
    fun `滑动缺起终点会被报出来`() {
        val s = Z.Script.parse(JSONObject("""{"actions":[{"type":"滑动","delayUnit":1}]}"""))
        val issues = ZScriptIO.validate(s)
        assertTrue(issues.any { it.contains("缺起点") })
        assertTrue(issues.any { it.contains("缺终点") })
    }

    @Test
    fun `空脚本会被报出来`() {
        val s = Z.Script.parse(JSONObject("""{"actions":[]}"""))
        assertTrue(ZScriptIO.validate(s).any { it.contains("没有动作") })
    }

    @Test
    fun `标签优先用描述，其次用类型名`() {
        val withDesc = Z.Action.parse(
            JSONObject("""{"type":"点击","desc":"点开始按钮","delayUnit":1}""")
        )!!
        assertEquals("点开始按钮", ZScriptIO.actionLabel(withDesc))

        val noDesc = Z.Action.parse(JSONObject("""{"type":"点击","delayUnit":1}"""))!!
        assertEquals("点击", ZScriptIO.actionLabel(noDesc))

        // 控制执行族要带上 controlRunType，否则五种动作看起来都叫"控制执行"。
        // 子类型显示**中文**：中文界面里孤零零一段 jumpTo 用户看不懂；
        // 落盘仍是英文 wire（ZModel.toJson 只写 controlRunType 原值）。
        val jump = Z.Action.parse(
            JSONObject("""{"type":"控制执行","controlRunType":"jumpTo","delayUnit":1}""")
        )!!
        assertEquals("控制执行·跳转到", ZScriptIO.actionLabel(jump))
        // 落盘不受显示影响
        assertEquals("jumpTo", jump.toJson().optString("controlRunType"))
    }
    // ------------------------------------------------------------ 校验的漏报

    @Test
    fun `位置类型与动作类型不匹配要报出来`() {
        // FIELDS.md:443：点击图片只接受 image 位置。
        // 不报的话界面上「位置 X 640」看着完全正常，运行起来永远匹配不到。
        val s = Z.Script.parse(
            JSONObject("""{"actions":[{"type":"点击图片","delayUnit":1,
                "posData":{"type":"location","x":"100","y":"200"}}]}""")
        )
        val issues = ZScriptIO.validate(s)
        assertTrue("应报类型不匹配：$issues", issues.any { it.contains("必须是 image") })
    }

    @Test
    fun `滑动起点终点类型必须是坐标`() {
        val s = Z.Script.parse(
            JSONObject("""{"actions":[{"type":"滑动","delayUnit":1,
                "startPos":{"type":"location","x":"1","y":"2"},
                "endPos":{"type":"image","imageData":{"data":"AA"}}}]}""")
        )
        assertTrue(ZScriptIO.validate(s).any { it.contains("终点必须是坐标") })
    }

    @Test
    fun `跳转缺目标要报出来`() {
        // FIELDS.md:546。不给的话运行时一定报错。
        val s = Z.Script.parse(
            JSONObject("""{"actions":[{"type":"控制执行","controlRunType":"jumpTo","delayUnit":1}]}""")
        )
        assertTrue(ZScriptIO.validate(s).any { it.contains("缺跳转目标") })
    }

    @Test
    fun `给了跳转目标就不报`() {
        val s = Z.Script.parse(
            JSONObject("""{"actions":[{"type":"控制执行","controlRunType":"jumpTo",
                "jumpToPosition":"start","delayUnit":1}]}""")
        )
        assertTrue(ZScriptIO.validate(s).none { it.contains("跳转") })
    }

    @Test
    fun `执行脚本缺路径要报出来`() {
        val s = Z.Script.parse(JSONObject("""{"actions":[{"type":"执行脚本","delayUnit":1}]}"""))
        assertTrue(ZScriptIO.validate(s).any { it.contains("缺脚本路径") })
    }

    @Test
    fun `示例脚本的 validate 不抛异常且返回空`() {
        // 这是运行按钮走的路径。抛异常的话按钮点了没反应，
        // 而异常被外层 runCatching 吞掉，界面上什么都看不到。
        val json = java.io.File("app/src/main/assets/zjs/示例_基础动作.json")
        org.junit.Assume.assumeTrue(json.isFile)
        val script = ZjsFileFormat.parse(json.readText()).script!!
        val issues = ZScriptIO.validate(script)
        assertTrue("示例脚本应该没问题：$issues", issues.isEmpty())
    }
}