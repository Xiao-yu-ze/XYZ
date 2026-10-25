package com.pvz2.auto.zjs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「等待候选」的候选能不能改。
 *
 * 真机反馈：点「添加候选」没反应，3 个候选都改不了。
 * 根因是 ZFieldEditor.apply 把 `cand.*` 当成"位置前缀"给挡掉了
 * （走到 POS_PREFIXES 查不到就 return null），一条都进不来。
 */
class WaitCandidateEditTest {

    private fun waiter(vararg cands: Z.Action.Candidate) = Z.Action(
        type = Z.ActionType.WAIT_CANDIDATES,
        candidates = cands.toList(),
        waitTimeoutMs = 20000,
        waitIntervalMs = 1000
    )

    @Test
    fun `添加候选`() {
        val a = waiter()
        val b = ZFieldEditor.apply(a, "cand.add", "add") ?: error("添加失败")
        assertEquals(1, b.candidates.size)
    }

    @Test
    fun `改候选的条件类型`() {
        val a = waiter(Z.Action.Candidate())
        val b = ZFieldEditor.apply(a, "cand.0.cond", "出现颜色") ?: error("写回失败")
        assertEquals(Z.ConditionType.COLOR, b.candidates[0].condition?.type)
        // 再改回无条件
        val c = ZFieldEditor.apply(b, "cand.0.cond", "（无条件）") ?: error("写回失败")
        assertNull(c.candidates[0].condition)
    }

    @Test
    fun `改候选的颜色和跳转目标`() {
        val a = waiter(Z.Action.Candidate(condition = Z.Condition(
            type = Z.ConditionType.COLOR,
            posData = Z.PosData(type = Z.PosType.COLOR)
        )))
        val b = ZFieldEditor.apply(a, "cand.0.color", "#ff0000") ?: error("颜色写回失败")
        assertEquals("#ff0000", b.candidates[0].condition?.posData?.color)
        val c = ZFieldEditor.apply(b, "cand.0.jumpTo", "目标节点") ?: error("跳转写回失败")
        assertEquals("目标节点", c.candidates[0].jumpTo)
    }

    @Test
    fun `删除候选`() {
        val a = waiter(Z.Action.Candidate(jumpTo = "a"), Z.Action.Candidate(jumpTo = "b"))
        val b = ZFieldEditor.apply(a, "cand.0.del", "删除") ?: error("删除失败")
        assertEquals(1, b.candidates.size)
        assertEquals("b", b.candidates[0].jumpTo)
        // 选「保留」不该动
        val c = ZFieldEditor.apply(a, "cand.1.del", "保留") ?: error("保留失败")
        assertEquals(2, c.candidates.size)
    }

    @Test
    fun `超时行为三种`() {
        val a = waiter(Z.Action.Candidate())
        val stop = ZFieldEditor.apply(a, "onTimeoutMode", "停止任务") ?: error("写回失败")
        assertEquals(true, stop.extra["onTimeoutStop"])
        val jump = ZFieldEditor.apply(a, "onTimeoutMode", "跳到标识") ?: error("写回失败")
        assertTrue(jump.extra.containsKey("onTimeoutJump"))
        assertNull(jump.extra["onTimeoutStop"])
        val fall = ZFieldEditor.apply(jump, "onTimeoutMode", "往下走") ?: error("写回失败")
        assertNull(fall.extra["onTimeoutStop"])
        assertNull(fall.extra["onTimeoutJump"])
    }
}
