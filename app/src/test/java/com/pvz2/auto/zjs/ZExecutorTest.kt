package com.pvz2.auto.zjs

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 执行器：重复、跳转、条件、跳过。
 *
 * 这些都是"看起来对但会在真实脚本上出错"的部分，所以用假的注入器
 * 在 JVM 上跑，断言**注入了什么**，而不是点了屏幕上真的发生了什么。
 */
class ZExecutorTest {

    /** 记录注入调用，不碰真机。 */
    private class FakeInjector : ZExecutor.Injector {
        val taps = ArrayList<Pair<Int, Int>>()
        val swipes = ArrayList<List<Int>>()
        val keys = ArrayList<Int>()
        val texts = ArrayList<String>()

        override fun tap(x: Int, y: Int, holdMs: Int) {
            taps.add(x to y)
        }

        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
            swipes.add(listOf(x1, y1, x2, y2))
        }

        override fun key(keyCode: Int, holdMs: Int) {
            keys.add(keyCode)
        }

        val multi = ArrayList<List<List<Int>>>()

        override fun inputText(text: String) {
            texts.add(text)
        }

        override fun multiSwipe(
            paths: List<Pair<ZPosResolver.Hit, ZPosResolver.Hit>>,
            durationMs: Int
        ) {
            // 记下每根手指的起终点，断言"几根手指、各自去哪"
            multi.add(paths.map { listOf(it.first.x, it.first.y, it.second.x, it.second.y) })
        }
    }

    /** 可配置的匹配器：默认找不到任何东西。 */
    private class FakeMatcher(
        private val imageHits: List<ZPosResolver.Hit> = emptyList()
    ) : ZExecutor.Matcher {
        override fun findImage(pos: Z.PosData, rect: IntArray) = imageHits
        override fun findText(pos: Z.PosData, rect: IntArray) = emptyList<ZPosResolver.Hit>()
        override fun findColor(pos: Z.PosData, rect: IntArray) = emptyList<ZPosResolver.Hit>()
        override fun findNode(pos: Z.PosData, rect: IntArray) = emptyList<ZPosResolver.Hit>()
    }

    private val screen = ZPosResolver.Screen(width = 1000, height = 500, density = 2f)

    private fun exec(
        json: String,
        inject: FakeInjector = FakeInjector(),
        matcher: ZExecutor.Matcher = FakeMatcher()
    ): Pair<ZExecutor.Result, FakeInjector> {
        val script = Z.Script.parse(JSONObject(json))
        val r = ZExecutor(inject, screen, matcher).run(script)
        return r to inject
    }

    // ------------------------------------------------------------ 基础

    @Test
    fun `点击百分比换算成像素`() {
        // 50% 宽 = 500，20% 高 = 100
        val (r, inj) = exec(
            """{"actions":[{"type":"点击","delayUnit":0,
                "posData":{"type":"location","x":"50%","y":"20%"}}]}"""
        )
        assertEquals(0, r.error?.length ?: 0)
        assertEquals(listOf(500 to 100), inj.taps)
    }

    @Test
    fun `点击像素坐标原样使用`() {
        val (_, inj) = exec(
            """{"actions":[{"type":"点击","delayUnit":0,
                "posData":{"type":"location","x":"300","y":"200"}}]}"""
        )
        assertEquals(listOf(300 to 200), inj.taps)
    }

    @Test
    fun `点击 dp 按密度换算`() {
        // density=2，100dp = 200px
        val (_, inj) = exec(
            """{"actions":[{"type":"点击","delayUnit":0,
                "posData":{"type":"location","x":"100dp","y":"50dp"}}]}"""
        )
        assertEquals(listOf(200 to 100), inj.taps)
    }

    @Test
    fun `滑动两端都换算`() {
        val (_, inj) = exec(
            """{"actions":[{"type":"滑动","delayUnit":0,"duration":"300",
                "startPos":{"type":"location","x":"10%","y":"20%"},
                "endPos":{"type":"location","x":"90%","y":"80%"}}]}"""
        )
        assertEquals(listOf(listOf(100, 100, 900, 400)), inj.swipes)
    }

    @Test
    fun `按键和输入内容`() {
        val (_, inj) = exec(
            """{"actions":[
                {"type":"按键","delayUnit":0,"keyCode":29},
                {"type":"输入内容","delayUnit":0,"inputText":"hello"}]}"""
        )
        assertEquals(listOf(29), inj.keys)
        assertEquals(listOf("hello"), inj.texts)
    }

    // ------------------------------------------------------------ 重复

    @Test
    fun `重复次数生效`() {
        val (r, inj) = exec(
            """{"actions":[{"type":"点击","delayUnit":0,"repeatCount":3,
                "posData":{"type":"location","x":"100","y":"100"}}]}"""
        )
        assertEquals(3, inj.taps.size)
        assertEquals(1, r.executed)  // 3 次点击算 1 个动作执行
    }

    @Test
    fun `重复次数 0 按 1 次执行而不是卡死`() {
        // 协议里 0 = 无限。但没有跳转的话无限重复会永远卡在这一条。
        val (_, inj) = exec(
            """{"actions":[{"type":"点击","delayUnit":0,"repeatCount":0,
                "posData":{"type":"location","x":"100","y":"100"}}]}"""
        )
        assertEquals(1, inj.taps.size)
    }

    // ------------------------------------------------------------ 跳转

    @Test
    fun `跳转到指定 jumpId`() {
        // 第 0 条跳到 third，应该只点 third 一次
        val (r, inj) = exec(
            """{"actions":[
                {"type":"控制执行","controlRunType":"jumpTo","delayUnit":0,"jumpToPosition":"third"},
                {"type":"点击","delayUnit":0,"desc":"second","posData":{"type":"location","x":"1","y":"1"}},
                {"type":"点击","delayUnit":0,"desc":"third","jumpId":"third","posData":{"type":"location","x":"3","y":"3"}}]}"""
        )
        assertEquals(1, inj.taps.size)
        assertEquals(listOf(3 to 3), inj.taps)
        assertNull(r.error)
    }

    @Test
    fun `跳转目标不存在时记一条并结束`() {
        val (r, inj) = exec(
            """{"actions":[
                {"type":"控制执行","controlRunType":"jumpTo","delayUnit":0,"jumpToPosition":"nowhere"}]}"""
        )
        assertEquals(0, inj.taps.size)
        assertTrue("应记录跳转失败：${r.skipped}", r.skipped.any { it.reason.contains("不存在") })
    }

    @Test
    fun `控制停止会终止脚本`() {
        val (r, inj) = exec(
            """{"actions":[
                {"type":"控制执行","controlRunType":"stop","delayUnit":0},
                {"type":"点击","delayUnit":0,"posData":{"type":"location","x":"1","y":"1"}}]}"""
        )
        assertEquals(0, inj.taps.size)
        assertNull(r.error)
    }

    @Test
    fun `死循环会被步数上限拦住`() {
        // 无限重复 + 无条件跳过：这是最容易把应用卡死的写法
        val (r, _) = exec(
            """{"baseInfo":{"repeatCount":0},"actions":[
                {"type":"控制执行","controlRunType":"jumpTo","delayUnit":0,"jumpToPosition":"top","jumpId":"top"}]}""",
        )
        assertTrue("应报死循环：${r.error}", r.error?.contains("死循环") == true)
    }

    // ------------------------------------------------------------ 跳过

    @Test
    fun `禁用的动作跳过并记原因`() {
        val (r, inj) = exec(
            """{"actions":[{"type":"点击","delayUnit":0,"disabled":true,
                "posData":{"type":"location","x":"1","y":"1"}}]}"""
        )
        assertEquals(0, inj.taps.size)
        assertTrue(r.skipped.any { it.reason.contains("禁用") })
    }

    @Test
    fun `安卓不支持的动作跳过并记原因`() {
        val (r, inj) = exec("""{"actions":[{"type":"设置变量","delayUnit":0}]}""")
        assertEquals(0, inj.taps.size)
        assertTrue("应说明不支持：${r.skipped}", r.skipped.any { it.reason.contains("暂不支持") })
    }

    @Test
    fun `图片没匹配到时记原因且不点击`() {
        // 这是最要紧的一条：找不到目标绝不能退化成点 (0,0)
        val (r, inj) = exec(
            """{"actions":[{"type":"点击图片","delayUnit":0,
                "posData":{"type":"image","imageData":{"data":"AA"}}}]}"""
        )
        assertEquals(0, inj.taps.size)
        assertTrue("应说明没匹配到：${r.skipped}", r.skipped.any { it.reason.contains("没有匹配") })
    }

    @Test
    fun `图片匹配到就点击命中点`() {
        val matcher = FakeMatcher(imageHits = listOf(ZPosResolver.Hit(400, 300)))
        val (_, inj) = exec(
            """{"actions":[{"type":"点击图片","delayUnit":0,
                "posData":{"type":"image","imageData":{"data":"AA"}}}]}""",
            matcher = matcher
        )
        assertEquals(listOf(400 to 300), inj.taps)
    }

    // ------------------------------------------------------------ 条件

    @Test
    fun `条件不满足时跳过动作`() {
        // percent=0 的随机条件永远不成立
        val (r, inj) = exec(
            """{"actions":[{"type":"点击","delayUnit":0,
                "condition":{"type":"random","percent":0},
                "posData":{"type":"location","x":"1","y":"1"}}]}"""
        )
        assertEquals(0, inj.taps.size)
        assertTrue(r.skipped.any { it.reason.contains("条件不满足") })
    }

    @Test
    fun `条件满足时执行`() {
        val (_, inj) = exec(
            """{"actions":[{"type":"点击","delayUnit":0,
                "condition":{"type":"random","percent":100},
                "posData":{"type":"location","x":"1","y":"1"}}]}"""
        )
        assertEquals(1, inj.taps.size)
    }

    @Test
    fun `取反条件反转结果`() {
        // percent=0 + 取反 → 条件成立 → 应该执行
        val (_, inj) = exec(
            """{"actions":[{"type":"点击","delayUnit":0,
                "condition":{"type":"random","percent":0,"runWhenFalse":true},
                "posData":{"type":"location","x":"1","y":"1"}}]}"""
        )
        assertEquals(1, inj.taps.size)
    }

    @Test
    fun `判不出来的条件按不成立处理`() {
        // 按成立会乱点，按不成立只是不动 —— 后者用户能查，前者查不出来
        val (r, inj) = exec(
            """{"actions":[{"type":"点击","delayUnit":0,
                "condition":{"type":"jsExpression","expression":"1+1==2"},
                "posData":{"type":"location","x":"1","y":"1"}}]}"""
        )
        assertEquals(0, inj.taps.size)
        assertTrue(r.skipped.any { it.reason.contains("条件不满足") })
    }

    @Test
    fun `空脚本给出明确错误`() {
        val (r, _) = exec("""{"actions":[]}""")
        assertEquals("脚本里没有动作", r.error)
    }
    // ------------------------------------------------------------ 多指滑动

    @Test
    fun `多指滑动把每根手指分别传下去`() {
        val (r, inj) = exec(
            """{"actions":[{"type":"多指滑动","delayUnit":0,"duration":"300",
                "multiSwipe":[
                  {"startPos":{"type":"location","x":"100","y":"400"},
                   "endPos":{"type":"location","x":"100","y":"100"}},
                  {"startPos":{"type":"location","x":"900","y":"400"},
                   "endPos":{"type":"location","x":"900","y":"100"}}]}]}"""
        )
        assertEquals(1, inj.multi.size)
        assertEquals(2, inj.multi[0].size)
        // 两根手指各自的位置，不能串
        assertEquals(listOf(100, 400, 100, 100), inj.multi[0][0])
        assertEquals(listOf(900, 400, 900, 100), inj.multi[0][1])
        assertTrue("不该有跳过：${r.skipped}", r.skipped.isEmpty())
    }

    @Test
    fun `多指滑动没有手指时记原因`() {
        val (r, inj) = exec("""{"actions":[{"type":"多指滑动","delayUnit":0}]}""")
        assertTrue(inj.multi.isEmpty())
        assertTrue(r.skipped.any { it.reason.contains("没有手指") })
    }

    @Test
    fun `多指滑动缺一端时整条跳过而不是滑一半`() {
        // 只滑一根手指和"两指缩放"是完全不同的操作，宁可不动
        val (r, inj) = exec(
            """{"actions":[{"type":"多指滑动","delayUnit":0,
                "multiSwipe":[
                  {"startPos":{"type":"location","x":"100","y":"400"},
                   "endPos":{"type":"location","x":"100","y":"100"}},
                  {"startPos":{"type":"location","x":"900","y":"400"}}]}]}"""
        )
        assertTrue("不该注入", inj.multi.isEmpty())
        assertTrue(r.skipped.any { it.reason.contains("缺起点或终点") })
    }
}