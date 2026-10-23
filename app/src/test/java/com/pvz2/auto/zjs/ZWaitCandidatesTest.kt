package com.pvz2.auto.zjs

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「等待候选」—— 我们自己的动作类型，等价于 Maa 的 `next`。
 *
 * 单独测它是因为它是**跨项目翻译的唯一落点**：Maa 的候选链、优先级、
 * 超时全压在这一个节点上。它序列化错了，转出来的脚本就会静默跑错流程 ——
 * 不报错，只是走错分支，那种最难查。
 */
class ZWaitCandidatesTest {

    private fun imagePos(): Z.PosData = Z.PosData(
        type = Z.PosType.IMAGE,
        imageData = Z.ImageData(data = "QUJD", width = 8, height = 6),
        minSimilarPercent = 80
    )

    private fun imageCond(): Z.Condition = Z.Condition(
        type = Z.ConditionType.IMAGE,
        posData = imagePos()
    )

    @Test
    fun `候选列表能完整往返`() {
        val action = Z.Action(
            type = Z.ActionType.WAIT_CANDIDATES,
            common = Z.ActionCommon(desc = "等结算或继续"),
            candidates = listOf(
                // 第一个有识别条件 —— 对应 Maa 里排在前面、按优先级试的候选
                Z.Action.Candidate(condition = imageCond(), jumpTo = "结算"),
                // 第二个无条件 —— 对应 DirectHit 兜底
                Z.Action.Candidate(condition = null, jumpTo = "继续")
            ),
            waitTimeoutMs = 20000,
            waitIntervalMs = 500
        )

        val json = action.toJson()
        assertEquals("等待候选", json.getString("type"))
        assertEquals(20000, json.getInt("waitTimeoutMs"))
        assertEquals(500, json.getInt("waitIntervalMs"))

        val back = Z.Action.parse(json)
        assertNotNull(back)
        assertEquals(Z.ActionType.WAIT_CANDIDATES, back!!.type)
        assertEquals(2, back.candidates.size)
        assertEquals("结算", back.candidates[0].jumpTo)
        assertEquals(Z.ConditionType.IMAGE, back.candidates[0].condition?.type)
        assertEquals(80, back.candidates[0].condition?.posData?.minSimilarPercent)
        assertNull("第二个候选必须保持无条件", back.candidates[1].condition)
        assertEquals("继续", back.candidates[1].jumpTo)
        assertEquals(20000, back.waitTimeoutMs)
        assertEquals(500, back.waitIntervalMs)
    }

    @Test
    fun `候选顺序必须原样保留`() {
        // 顺序就是优先级。排序或去重都会改变流程走向。
        val names = listOf("c", "a", "b")
        val action = Z.Action(
            type = Z.ActionType.WAIT_CANDIDATES,
            candidates = names.map { Z.Action.Candidate(jumpTo = it) }
        )
        val back = Z.Action.parse(action.toJson())!!
        assertEquals(names, back.candidates.map { it.jumpTo })
    }

    @Test
    fun `没写超时和间隔时不落盘`() {
        // 缺省不写，文件才干净；读回来是 null，执行器按"一直等 / 200ms"处理
        val action = Z.Action(
            type = Z.ActionType.WAIT_CANDIDATES,
            candidates = listOf(Z.Action.Candidate(jumpTo = "x"))
        )
        val json = action.toJson()
        assertTrue(!json.has("waitTimeoutMs"))
        assertTrue(!json.has("waitIntervalMs"))
        val back = Z.Action.parse(json)!!
        assertNull(back.waitTimeoutMs)
        assertNull(back.waitIntervalMs)
    }

    @Test
    fun `坏掉一个候选不影响整条动作`() {
        // 一个候选解析失败不该让整条动作消失 —— 用户会看到"这条没了"
        val json = JSONObject(
            """{"type":"等待候选","candidates":[{"jumpTo":"a"},"不是对象",{"jumpTo":"b"}]}"""
        )
        val back = Z.Action.parse(json)
        assertNotNull(back)
        assertEquals(2, back!!.candidates.size)
        assertEquals(listOf("a", "b"), back.candidates.map { it.jumpTo })
    }

    @Test
    fun `这个是可执行的动作类型`() {
        // 不可执行的话运行时会记"安卓上暂不支持"，转出来的脚本等于白转
        assertTrue(Z.ActionType.WAIT_CANDIDATES.executable)
    }
}
