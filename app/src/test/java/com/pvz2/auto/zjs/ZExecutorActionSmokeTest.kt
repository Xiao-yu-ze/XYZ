package com.pvz2.auto.zjs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 每个动作类型**真跑一遍**，断言注入器收到什么。
 *
 * 为什么要有这一套：字段审计只能证明"代码里读了那个字段"，证明不了
 * "这个节点真的会做事"。刚出过的事故就是「出现节点」整条链路是死的 ——
 * 代码看着有、测试里也提到过这个名字，但真跑起来什么都不会发生。
 *
 * 所以这里一个类型一个用例：给定最小合法参数，跑真的 ZExecutor
 * （假注入器 + 假匹配器），断言
 *  - 注入器收到了预期调用，或
 *  - 给出了**明确**的跳过原因（不是空跑）。
 */
class ZExecutorActionSmokeTest {

    private val base = "{\"repeatCount\":1,\"delay\":\"0\",\"delayUnit\":0," +
        "\"screenWidth\":1280,\"screenHeight\":720}"

    private class Rec : ZExecutor.Injector {
        val calls = ArrayList<String>()
        var clipboard: String? = null
        override fun tap(x: Int, y: Int, holdMs: Int) { calls.add("tap($x,$y,$holdMs)") }
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
            calls.add("swipe($x1,$y1,$x2,$y2)")
        }
        override fun multiSwipe(paths: List<Pair<ZPosResolver.Hit, ZPosResolver.Hit>>, durationMs: Int) {
            calls.add("multiSwipe(${paths.size})")
        }
        override fun key(keyCode: Int, holdMs: Int) { calls.add("key($keyCode)") }
        override fun inputText(text: String) { calls.add("input($text)") }
        override fun setClipboard(text: String): Boolean {
            clipboard = text; calls.add("clipboard($text)"); return true
        }
        /** 「系统提示」走的是这个通道（不是 jsToast）。 */
        val toasts = ArrayList<String>()
        override fun toaster(): ((String) -> Unit)? = { msg -> toasts.add(msg) }
    }

    private fun hit(x: Int, y: Int) = ZPosResolver.Hit(x, y, 1.0)

    /** 假匹配器：图像/文字/颜色一律命中 (100,200)，节点按"服务未启用"报错。 */
    private fun matcher(): ZExecutor.Matcher = object : ZExecutor.Matcher {
        override fun findImage(pos: Z.PosData, rect: IntArray) = listOf(hit(100, 200))
        override fun findText(pos: Z.PosData, rect: IntArray) = listOf(hit(100, 200))
        override fun findColor(pos: Z.PosData, rect: IntArray) = listOf(hit(100, 200))
        override fun findNode(pos: Z.PosData, rect: IntArray) = emptyList<ZPosResolver.Hit>()
        override fun ocrTexts(rect: IntArray) = listOf("甲", "乙")
        override fun lastReason() = "假匹配器"
    }

    private fun run(
        vararg lines: String,
        loader: ((String) -> Z.Script?)? = null,
        toast: MutableList<String>? = null
    ): Pair<ZExecutor.Result, Rec> {
        val rec = Rec()
        val ex = ZExecutor(rec, ZPosResolver.Screen(1280, 720, 1.5f), matcher()) {}
        ex.scriptLoader = loader
        if (toast != null) ex.jsToast = { toast.add(it) }
        val script = ZjsFileFormat.parse((listOf(base) + lines).joinToString("\r\n") + "\r\n")
            .script ?: error("解析失败")
        return ex.run(script) to rec
    }

    // ---------------------------------------------------------------- 点击族

    @Test
    fun `点击——按坐标注入`() {
        val (r, rec) = run("""{"type":"点击","posData":{"type":"location","x":"10","y":"20"}}""")
        assertEquals(listOf("tap(10,20,100)"), rec.calls)
        assertEquals(0, r.skipped.size)
    }

    @Test
    fun `点击图片——命中后按命中点注入`() {
        val (r, rec) = run(
            """{"type":"点击图片","posData":{"type":"image","imageData":{"data":"AA","width":1,"height":1}}}"""
        )
        assertEquals(listOf("tap(100,200,100)"), rec.calls)
        assertEquals(0, r.skipped.size)
    }

    @Test
    fun `点击文字——命中后注入`() {
        val (r, rec) = run("""{"type":"点击文字","posData":{"type":"text","text":"开始"}}""")
        assertEquals(listOf("tap(100,200,100)"), rec.calls)
        assertEquals(0, r.skipped.size)
    }

    @Test
    fun `点击颜色——命中后注入`() {
        val (r, rec) = run("""{"type":"点击颜色","posData":{"type":"color","color":16711680}}""")
        assertEquals(listOf("tap(100,200,100)"), rec.calls)
        assertEquals(0, r.skipped.size)
    }

    @Test
    fun `点击节点——必须给出明确原因，不能空跑`() {
        val (r, rec) = run("""{"type":"点击节点","posData":{"type":"node","text":"开始"}}""")
        assertEquals(0, rec.calls.size)
        assertTrue(
            "必须留下跳过原因（真实匹配器会写明无障碍/虚拟屏）：${r.skipped}",
            r.skipped.isNotEmpty()
        )
    }

    @Test
    fun `AI点击——标为不支持并跳过`() {
        val (r, rec) = run("""{"type":"AI点击","posData":{"type":"aiLoc","text":"x"}}""")
        assertEquals(0, rec.calls.size)
        assertTrue("要说清不支持：${r.skipped}", r.skipped.any { it.reason.contains("不支持") })
    }

    @Test
    fun `极速点击——把 hold 传成 0（注入层据此跳过等待）`() {
        val (r, rec) = run(
            """{"type":"点击","highSpeedTap":true,"posData":{"type":"location","x":"10","y":"20"}}"""
        )
        assertEquals(listOf("tap(10,20,0)"), rec.calls)
        assertEquals(0, r.skipped.size)
    }

    @Test
    fun `普通点击——把 duration 当按住时长传下去`() {
        val (r, rec) = run(
            """{"type":"点击","duration":"150","posData":{"type":"location","x":"10","y":"20"}}"""
        )
        assertEquals(listOf("tap(10,20,150)"), rec.calls)
        assertEquals(0, r.skipped.size)
    }

    @Test
    fun `识别后等待——在识别之后、点击之前发生`() {
        val logs = ArrayList<String>()
        val rec = Rec()
        val ex = ZExecutor(rec, ZPosResolver.Screen(1280, 720, 1.5f), matcher()) { logs.add(it) }
        val script = ZjsFileFormat.parse(
            listOf(
                base,
                """{"type":"点击文字","posData":{"type":"text","text":"开始"},"delayAfterLocate":"40"}"""
            ).joinToString("\r\n") + "\r\n"
        ).script!!
        ex.run(script)
        // 这条日志必须在点击**之前**出现（Maa 的 pre_delay 位置）
        val iLog = logs.indexOfFirst { it.contains("识别后等待") }
        assertTrue("应当有识别后等待的日志：$logs", iLog >= 0)
        assertEquals(listOf("tap(100,200,100)"), rec.calls)
    }

    // ---------------------------------------------------------------- 手势

    @Test
    fun `滑动——起终点各解析一次`() {
        val (r, rec) = run(
            """{"type":"滑动","startPos":{"type":"location","x":"10","y":"20"},""" +
                """"endPos":{"type":"location","x":"30","y":"40"}}"""
        )
        assertEquals(listOf("swipe(10,20,30,40)"), rec.calls)
        assertEquals(0, r.skipped.size)
    }

    @Test
    fun `单指手势——轨迹点按顺序执行`() {
        val (r, rec) = run(
            """{"type":"单指手势","gestureActions":[""" +
                """{"type":"点击","posData":{"type":"location","x":"1","y":"1"}},""" +
                """{"type":"点击","posData":{"type":"location","x":"2","y":"2"}}]}"""
        )
        assertEquals(listOf("tap(1,1,100)", "tap(2,2,100)"), rec.calls)
        assertEquals(0, r.skipped.size)
    }

    @Test
    fun `多指手势——每根手指一条路径`() {
        val (r, rec) = run(
            """{"type":"多指手势","multiSwipe":[""" +
                """{"startPos":{"type":"location","x":"1","y":"1"},"endPos":{"type":"location","x":"2","y":"2"}},""" +
                """{"startPos":{"type":"location","x":"3","y":"3"},"endPos":{"type":"location","x":"4","y":"4"}}]}"""
        )
        assertTrue("应当并发多指：${rec.calls}", rec.calls.any { it.startsWith("multiSwipe(2)") })
        assertEquals(0, r.skipped.size)
    }

    // ---------------------------------------------------------------- 键盘 / 输入

    @Test
    fun `按键——按 keyCode 注入`() {
        val (r, rec) = run("""{"type":"按键","keyCode":4}""")
        assertEquals(listOf("key(4)"), rec.calls)
        assertEquals(0, r.skipped.size)
    }

    @Test
    fun `按键——缺 keyCode 要报出来`() {
        val (r, rec) = run("""{"type":"按键"}""")
        assertEquals(0, rec.calls.size)
        assertTrue("${r.skipped}", r.skipped.any { it.reason.contains("keyCode") })
    }

    @Test
    fun `输入内容——走注入通道`() {
        val (r, rec) = run("""{"type":"输入内容","inputText":"abc"}""")
        assertEquals(listOf("input(abc)"), rec.calls)
        assertEquals(0, r.skipped.size)
    }

    @Test
    fun `输入内容——空文本要跳过并说明`() {
        val (r, rec) = run("""{"type":"输入内容"}""")
        assertEquals(0, rec.calls.size)
        assertTrue("${r.skipped}", r.skipped.any { it.reason.contains("没有要输入") })
    }

    // ---------------------------------------------------------------- 识别屏幕

    @Test
    fun `识别屏幕——默认剪贴板模式写入识别结果`() {
        val (r, rec) = run("""{"type":"识别屏幕","config":{"recognitionMode":"ocr_local"}}""")
        assertTrue("应当写剪贴板：${rec.calls}", rec.calls.any { it.startsWith("clipboard(") })
        assertTrue("剪贴板内容应含识别文字：${rec.clipboard}", rec.clipboard?.contains("甲") == true)
        assertEquals(0, r.skipped.size)
    }

    @Test
    fun `识别屏幕——input 模式遇到中文要明确拒绝`() {
        val (r, rec) = run(
            """{"type":"识别屏幕","config":{"recognitionMode":"ocr_local"},"useResultMode":"input"}"""
        )
        assertEquals(0, rec.calls.size)
        assertTrue(
            "input text 打不了中文，要说清楚：${r.skipped}",
            r.skipped.any { it.reason.contains("ASCII") || it.reason.contains("input") }
        )
    }

    // ---------------------------------------------------------------- 应用 / 链接

    @Test
    fun `打开应用——通道没接时明确报错`() {
        val (r, _) = run("""{"type":"打开应用","appPackageName":"com.x"}""")
        assertTrue("${r.skipped}", r.skipped.any { it.reason.contains("应用") })
    }

    @Test
    fun `打开链接——通道没接时明确报错`() {
        val (r, _) = run("""{"type":"打开链接","link":"https://x"}""")
        assertTrue("${r.skipped}", r.skipped.any { it.reason.contains("链接") })
    }

    // ---------------------------------------------------------------- 控制执行

    @Test
    fun `控制执行·跳转到——跳到指定标识继续`() {
        val (r, rec) = run(
            """{"type":"控制执行","controlRunType":"jumpTo","jumpToPosition":":B"}""",
            """{"type":"点击","posData":{"type":"location","x":"9","y":"9"}}""",
            """{"type":"点击","jumpId":"B","posData":{"type":"location","x":"1","y":"1"}}"""
        )
        assertEquals(listOf("tap(1,1,100)"), rec.calls)
        assertEquals(0, r.skipped.size)
    }

    @Test
    fun `控制执行·停止——后面的动作不再执行`() {
        val (r, rec) = run(
            """{"type":"控制执行","controlRunType":"stop"}""",
            """{"type":"点击","posData":{"type":"location","x":"1","y":"1"}}"""
        )
        assertEquals(0, rec.calls.size)
        assertEquals(0, r.skipped.size)
    }

    @Test
    fun `控制执行·等待——等待时长走 delay`() {
        val (r, _) = run("""{"type":"控制执行","controlRunType":"waitDelay","delay":"1"}""")
        assertEquals(0, r.skipped.size)
    }

    // ---------------------------------------------------------------- 提示 / 变量 / JS

    @Test
    fun `系统提示——toast 走通道`() {
        val (r, rec) = run("""{"type":"系统提示","promptType":"toast","promptText":"你好"}""")
        assertTrue("应当弹提示：${rec.toasts}", rec.toasts.any { it.contains("你好") })
        assertEquals(0, r.skipped.size)
    }

    @Test
    fun `设置变量——写进去的值 JS 能读到`() {
        val logs = ArrayList<String>()
        val rec = Rec()
        val ex = ZExecutor(rec, ZPosResolver.Screen(1280, 720, 1.5f), matcher()) { logs.add(it) }
        val script = ZjsFileFormat.parse(
            listOf(
                base,
                """{"type":"设置变量","vars":[{"name":"关卡","value":{"varType":"number","valueExp":"7"}}]}""",
                """{"type":"运行JS代码","jsCode":"console.log('读到 ' + zdjl.getVar('关卡'))"}"""
            ).joinToString("\r\n") + "\r\n"
        ).script!!
        ex.run(script)
        assertTrue("JS 应读到 7：$logs", logs.any { it.contains("读到 7") })
    }

    @Test
    fun `运行JS代码——写回变量`() {
        val logs = ArrayList<String>()
        val rec = Rec()
        val ex = ZExecutor(rec, ZPosResolver.Screen(1280, 720, 1.5f), matcher()) { logs.add(it) }
        val script = ZjsFileFormat.parse(
            listOf(
                base,
                """{"type":"设置变量","vars":[{"name":"n","value":{"varType":"number","valueExp":"1"}}]}""",
                """{"type":"运行JS代码","jsCode":"zdjl.setVar('n', zdjl.getVar('n') + 41)"}""",
                """{"type":"控制执行","controlRunType":"waitDelay","delay":"0",""" +
                    """"condition":{"type":"jsExpression","expression":"zdjl.getVar('n') === 42"}}"""
            ).joinToString("\r\n") + "\r\n"
        ).script!!
        val r = ex.run(script)
        assertEquals("JS 应把 n 改成 42：${r.skipped}", 0, r.skipped.size)
    }

    @Test
    fun `加密动作——明确跳过`() {
        val (r, rec) = run("""{"type":"加密动作","encryptId":"x","content":"y"}""")
        assertEquals(0, rec.calls.size)
        assertTrue("${r.skipped}", r.skipped.any { it.reason.contains("不支持") })
    }

    // ---------------------------------------------------------------- 容器

    @Test
    fun `运行多个动作——子动作按顺序执行`() {
        val (r, rec) = run(
            """{"type":"运行多个动作","scriptSet":[""" +
                """{"type":"点击","posData":{"type":"location","x":"1","y":"1"}},""" +
                """{"type":"点击","posData":{"type":"location","x":"2","y":"2"}}]}"""
        )
        assertEquals(listOf("tap(1,1,100)", "tap(2,2,100)"), rec.calls)
        assertEquals(0, r.skipped.size)
    }

    @Test
    fun `执行脚本——加载并跑子脚本`() {
        val child = ZjsFileFormat.parse(
            listOf(base, """{"type":"点击","posData":{"type":"location","x":"5","y":"5"}}""")
                .joinToString("\r\n") + "\r\n"
        ).script!!
        val (r, rec) = run(
            """{"type":"执行脚本","filePath":"子.zjs","continueCurrentAfterFinish":true}""",
            loader = { child }
        )
        assertEquals(listOf("tap(5,5,100)"), rec.calls)
        assertEquals(0, r.skipped.size)
    }
}
