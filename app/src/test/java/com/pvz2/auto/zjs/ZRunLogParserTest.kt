package com.pvz2.auto.zjs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志页的数据来源：把运行日志切成"一条动作一块"。
 *
 * 用户在日志里最想知道的是**顺序和状态**（跑到哪了、哪条卡住、哪条花了多久），
 * 而不是通篇细节。所以这里保证：每条节点的边界、用时、结束方式都能切出来。
 */
class ZRunLogParserTest {

    private val sample = """
        # PVZ2 自动化 脚本运行日志
        # 脚本: 塔香蕉_无尽.zjs
        # 开始: 2026-10-25 13:08:39.290
        [2026-10-25 13:08:39.290] 运行请求：/x/塔香蕉_无尽.zjs
        [2026-10-25 13:08:39.313] ── [1/76] 点击文字 · 点击文字
        [2026-10-25 13:08:51.068] [动作 0] 点击文字 还在等（第 10 轮，已 10s，不设上限） | 文字 最佳0% 认出=«C»
        [2026-10-25 13:09:10.895]    识别：文字"开始战斗" 相似度75% → 命中 1 个 | 本次识别 98ms
        [2026-10-25 13:09:11.097]    点击 (1163,674) 按住 100ms
        [2026-10-25 13:09:11.110]    ↳ 完成 [1] 用时 31797ms（识别 3291ms × 29 次）
        [2026-10-25 13:09:11.111] ── [2/76] 等待候选 · tower_banana_过渡1 分叉
        [2026-10-25 13:09:15.928] [等待候选] 第 1 轮命中候选 3/3 → tower_banana_香蕉1
        [2026-10-25 13:09:15.929]    ↳ 完成 [2] 用时 818ms（识别 12ms × 1 次）→ 跳转
        [2026-10-25 13:09:15.930] ── [40/76] 滑动 · 滑动
        [2026-10-25 13:09:16.140]    ↳ 完成 [40] 用时 212ms（识别 0ms × 0 次）
        [2026-10-25 13:09:16.141] ── [41/76] 点击文字 · 点击文字
        [2026-10-25 13:09:40.000] [动作 40] 点击文字 — 没有匹配到目标：没识别出任何文字
        # 结束: 执行 3 条，跳过 1 条
    """.trimIndent()

    @Test
    fun `能切出每个节点并保留顺序`() {
        val r = ZRunLogParser.parse(sample)
        assertEquals("塔香蕉_无尽.zjs", r.scriptName)
        assertEquals("2026-10-25 13:08:39.290", r.startedAt)
        assertEquals(listOf(1, 2, 40, 41), r.nodes.map { it.index })
        assertEquals(listOf("点击文字", "等待候选", "滑动", "点击文字"), r.nodes.map { it.typeName })
    }

    @Test
    fun `用时和识别耗时都解析出来`() {
        val r = ZRunLogParser.parse(sample)
        val n1 = r.nodes.first { it.index == 1 }
        assertEquals(31797L, n1.costMs)
        assertEquals(3291L, n1.recognizeMs)
        assertEquals(29, n1.recognizeCount)
        // 列表上显示的就是这个
        assertEquals("31797ms", n1.summary())
    }

    @Test
    fun `结束方式分得清成功 跳转 未完成`() {
        val r = ZRunLogParser.parse(sample)
        assertEquals(ZRunLogParser.Outcome.OK, r.nodes.first { it.index == 1 }.outcome)
        assertEquals(ZRunLogParser.Outcome.JUMPED, r.nodes.first { it.index == 2 }.outcome)
        assertEquals(ZRunLogParser.Outcome.FAILED, r.nodes.first { it.index == 41 }.outcome)
        // 41 号没有完成行
        assertNull(r.nodes.first { it.index == 41 }.costMs)
        assertEquals("未完成", r.nodes.first { it.index == 41 }.summary())
    }

    @Test
    fun `每个节点只收自己的行`() {
        val r = ZRunLogParser.parse(sample)
        val n1 = r.nodes.first { it.index == 1 }
        assertTrue("第 1 条应当带自己的识别与点击行：${n1.lines}", n1.lines.any { it.contains("识别：") })
        assertTrue(n1.lines.any { it.contains("点击 (1163,674)") })
        assertTrue("不该混进第 2 条的行：${n1.lines}", n1.lines.none { it.contains("命中候选") })
    }

    @Test
    fun `空日志不会崩`() {
        val r = ZRunLogParser.parse("")
        assertTrue(r.nodes.isEmpty())
    }
}
