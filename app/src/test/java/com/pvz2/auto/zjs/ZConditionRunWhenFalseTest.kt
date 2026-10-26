package com.pvz2.auto.zjs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「条件不成立时执行」（`runWhenFalse`，界面上叫**取反**）。
 *
 * ## 为什么单独立一条
 *
 * 用户真机脚本「切换编队.zjs」里，滑动节点的条件是
 * `textFount(找 "4") + runWhenFalse: true` —— 语义是"没看到 4 就滑动"。
 * 真机日志却是：
 *
 * ```text
 * ── [2/3] 滑动 · 查找编队
 *   条件 textFount: 成立（命中 1 处）      ← 上一轮残留
 *   [任务结束] 第 2 条「查找编队」条件不满足（出现文字）：条件不成立
 * ```
 *
 * 也就是**取反没生效，动作被跳过**，用户看到的"滑动一下就没了"其实是没滑。
 * 这条用例直接跑一个带取反条件的点击，验证它确实会执行。
 */
class ZConditionRunWhenFalseTest {

    private class Rec : ZExecutor.Injector {
        val calls = ArrayList<String>()
        override fun tap(x: Int, y: Int, holdMs: Int) { calls.add("tap($x,$y)") }
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
            calls.add("swipe($x1,$y1→$x2,$y2,dur=$durationMs)")
        }
        override fun key(keyCode: Int, holdMs: Int) {}
        override fun inputText(text: String) {}
    }

    /** 一个永远认不出任何东西的匹配器 —— 模拟"屏幕上没有那个文字"。 */
    private class BlindMatcher : ZExecutor.Matcher {
        override fun findImage(pos: Z.PosData, rect: IntArray) = emptyList<ZPosResolver.Hit>()
        override fun findText(pos: Z.PosData, rect: IntArray) = emptyList<ZPosResolver.Hit>()
        override fun findColor(pos: Z.PosData, rect: IntArray) = emptyList<ZPosResolver.Hit>()
        override fun findNode(pos: Z.PosData, rect: IntArray) = emptyList<ZPosResolver.Hit>()
        override fun lastReason() = "这一块里没识别出任何文字"
    }

    private fun runScript(vararg actions: Z.Action): Pair<ZExecutor.Result, Rec> {
        val rec = Rec()
        val ex = ZExecutor(rec, ZPosResolver.Screen(1280, 720, 1.5f), BlindMatcher()) {}
        val script = Z.Script(actions = actions.toList() + Z.Action(type = Z.ActionType.CONTROL_STOP))
        return ex.run(script) to rec
    }

    private fun click(cond: Z.Condition?): Z.Action = Z.Action(
        type = Z.ActionType.CLICK,
        posData = Z.PosData(
            type = Z.PosType.LOCATION,
            x = ZValue.Px("100", 100.0),
            y = ZValue.Px("200", 200.0)
        ),
        common = Z.ActionCommon(delay = "0", condition = cond)
    )

    private fun textCond(runWhenFalse: Boolean): Z.Condition = Z.Condition(
        type = Z.ConditionType.TEXT,
        posData = Z.PosData(type = Z.PosType.TEXT, text = "4"),
        runWhenFalse = runWhenFalse
    )

    @Test
    fun `取反条件在认不出时应执行动作`() {
        val (r, rec) = runScript(click(textCond(runWhenFalse = true)))
        assertEquals("取反条件不成立(认不出)时应当执行点击", listOf("tap(100,200)"), rec.calls)
        assertTrue(
            "不应因为条件跳过：${r.skipped.map { it.reason }}",
            r.skipped.none { it.reason.contains("条件不满足") }
        )
    }

    @Test
    fun `不取反时认不出应跳过动作`() {
        val (r, rec) = runScript(click(textCond(runWhenFalse = false)))
        assertEquals(0, rec.calls.size)
        assertTrue(
            "应记录条件不满足：${r.skipped.map { it.reason }}",
            r.skipped.any { it.reason.contains("条件不满足") }
        )
    }

    @Test
    fun `带取反的滑动要真的滑 并且用 duration 当滑动时长`() {
        val swipe = Z.Action(
            type = Z.ActionType.SWIPE,
            startPos = Z.PosData(
                type = Z.PosType.LOCATION,
                x = ZValue.Px("200", 200.0),
                y = ZValue.Px("900", 900.0)
            ),
            endPos = Z.PosData(
                type = Z.PosType.LOCATION,
                x = ZValue.Px("200", 200.0),
                y = ZValue.Px("300", 300.0)
            ),
            common = Z.ActionCommon(
                duration = "3000",
                delay = "0",
                repeatCount = 1,
                condition = textCond(runWhenFalse = true)
            )
        )
        val (r, rec) = runScript(swipe)
        assertEquals("取反条件不满足时必须真的滑：${r.skipped.map { it.reason }}", 1, rec.calls.size)
        assertTrue("滑动时长必须是 duration=3000，实际 ${rec.calls}", rec.calls[0].contains("dur=3000"))
    }

    /** 认不出前 N 次、之后能认出来的匹配器：用来验证"滑到认出来为止"。 */
    private class LateMatcher(private val succeedAfter: Int) : ZExecutor.Matcher {
        var calls = 0
        override fun findImage(pos: Z.PosData, rect: IntArray) = emptyList<ZPosResolver.Hit>()
        override fun findText(pos: Z.PosData, rect: IntArray): List<ZPosResolver.Hit> {
            calls++
            return if (calls > succeedAfter) {
                listOf(ZPosResolver.Hit(100, 100, score = 1.0))
            } else emptyList()
        }
        override fun findColor(pos: Z.PosData, rect: IntArray) = emptyList<ZPosResolver.Hit>()
        override fun findNode(pos: Z.PosData, rect: IntArray) = emptyList<ZPosResolver.Hit>()
        override fun lastReason() = "还没出现"
    }

    @Test
    fun `repeatCount0 加取反条件要滑到认出来为止`() {
        val rec = Rec()
        val matcher = LateMatcher(succeedAfter = 2)
        val logs = ArrayList<String>()
        val ex = ZExecutor(rec, ZPosResolver.Screen(1280, 720, 1.5f), matcher) { logs.add(it) }
        val swipe = Z.Action(
            type = Z.ActionType.SWIPE,
            startPos = Z.PosData(
                type = Z.PosType.LOCATION,
                x = ZValue.Px("200", 200.0),
                y = ZValue.Px("900", 900.0)
            ),
            endPos = Z.PosData(
                type = Z.PosType.LOCATION,
                x = ZValue.Px("200", 200.0),
                y = ZValue.Px("300", 300.0)
            ),
            common = Z.ActionCommon(
                duration = "0",
                delay = "0",
                repeatCount = 0,
                condition = textCond(runWhenFalse = true)
            )
        )
        val r = ex.run(Z.Script(actions = listOf(swipe, Z.Action(type = Z.ActionType.CONTROL_STOP))))
        // 前 2 次认不出 → 滑；第 3 次认出 → 退出。所以至少滑 2 次、并且真的退出了（不是死循环）
        assertTrue("应重复滑动，实际 ${rec.calls.size} 次：$logs", rec.calls.size >= 2)
        // 认出 4 之后按**条件失败**收场（用户预期：提示条件失败 → 跑监听条件失败后）
        assertTrue("应记条件不满足：$logs", logs.any { it.contains("条件不满足") })
        assertTrue("应说明重复了几次：$logs", logs.any { it.contains("已经重复") })
    }
}
