package com.pvz2.auto.zjs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/**
 * 三条链：单次 / 循环 / 收尾。
 *
 * 链只在**编辑期**存在，写回脚本时编译成平铺节点（循环链包一个
 * 「运行多个动作」）。所以这里断言的是"编出来的节点长什么样"，
 * 顺带钉住两条容易出事的规则：非游戏动作不许被吃掉、顺序不许乱。
 */
class PvzChainModelTest {

    private fun plant(slot: Int, row: Int, col: Int, chain: String? = null): Z.Action =
        Z.Action(
            type = Z.ActionType.PVZ_PLANT,
            common = Z.ActionCommon(delay = "100"),
            extra = HashMap<String, Any?>().apply {
                put("pvzSlot", slot.toString())
                put("pvzRow", row.toString())
                put("pvzCol", col.toString())
                if (chain != null) put("pvzChain", chain)
            }
        )

    private fun tapCell(row: Int, col: Int, chain: String? = null): Z.Action =
        Z.Action(
            type = Z.ActionType.PVZ_TAP_CELL,
            extra = HashMap<String, Any?>().apply {
                put("pvzRow", row.toString())
                put("pvzCol", col.toString())
                if (chain != null) put("pvzChain", chain)
            }
        )

    private fun toast(desc: String): Z.Action = Z.Action(
        type = Z.ActionType.PROMPT,
        extra = HashMap<String, Any?>().apply { put("promptText", desc); put("desc", desc) }
    )

    @Test
    fun `没写链的默认算单次`() {
        val a = plant(1, 2, 3)
        assertEquals(PvzChainModel.ONCE, PvzChainModel.chainOf(a))
        assertEquals("单次", PvzChainModel.label(PvzChainModel.chainOf(a)))
    }

    @Test
    fun `分组按链派到三栏且保持相对顺序`() {
        val list = listOf(
            plant(1, 1, 1, PvzChainModel.ONCE),
            plant(2, 1, 2, PvzChainModel.LOOP),
            tapCell(3, 3, PvzChainModel.END),
            plant(3, 1, 3, PvzChainModel.LOOP)
        )
        val g = PvzChainModel.group(list)
        assertEquals(1, g[PvzChainModel.ONCE]!!.size)
        assertEquals(2, g[PvzChainModel.LOOP]!!.size)
        assertEquals(1, g[PvzChainModel.END]!!.size)
        // 循环链内部顺序保持原样（先 (1,2) 再 (1,3)）
        assertEquals(
            listOf("1", "1"),
            g[PvzChainModel.LOOP]!!.map { it.extra["pvzRow"]?.toString() }
        )
    }

    @Test
    fun `非游戏动作原样保留且位置不乱`() {
        val before = toast("开头")
        val after = toast("结尾")
        val list = listOf(before, plant(1, 2, 3), after)
        val rebuilt = PvzChainModel.rebuild(
            list,
            mapOf(
                PvzChainModel.ONCE to listOf(plant(1, 2, 3)),
                PvzChainModel.LOOP to emptyList(),
                PvzChainModel.END to emptyList()
            )
        )
        // 三条链编译出来的节点插回原来第一个游戏动作的位置
        assertEquals(Z.ActionType.PROMPT, rebuilt[0].type)
        assertEquals(Z.ActionType.PVZ_PLANT, rebuilt[1].type)
        assertEquals(Z.ActionType.PROMPT, rebuilt[2].type)
        assertEquals(3, rebuilt.size)
    }

    @Test
    fun `循环链被包进运行多个动作并带上重复次数`() {
        val list = listOf(plant(1, 1, 1))
        val rebuilt = PvzChainModel.rebuild(
            list,
            mapOf(
                PvzChainModel.ONCE to listOf(tapCell(1, 1)),
                PvzChainModel.LOOP to listOf(plant(2, 2, 2), tapCell(3, 3)),
                PvzChainModel.END to listOf(tapCell(5, 9))
            ),
            loopRepeat = 0
        )
        // 单次 1 条 + 循环容器 1 条 + 收尾 1 条
        assertEquals(3, rebuilt.size)
        assertEquals(Z.ActionType.PVZ_TAP_CELL, rebuilt[0].type)
        assertEquals(Z.ActionType.RUN_MULTI, rebuilt[1].type)
        assertEquals(0, rebuilt[1].common.repeatCount)
        assertEquals(2, rebuilt[1].scriptSet.size)
        assertEquals(Z.ActionType.PVZ_TAP_CELL, rebuilt[2].type)
        // 链标记要落到节点上，否则再打开面板就分不出是哪条链
        assertEquals(PvzChainModel.LOOP, rebuilt[1].scriptSet[0].extra["pvzChain"])
        assertEquals(PvzChainModel.END, rebuilt[2].extra["pvzChain"])
    }

    @Test
    fun `循环链为空时不生成容器节点`() {
        val rebuilt = PvzChainModel.rebuild(
            listOf(plant(1, 1, 1)),
            mapOf(
                PvzChainModel.ONCE to listOf(tapCell(1, 1)),
                PvzChainModel.LOOP to emptyList(),
                PvzChainModel.END to emptyList()
            )
        )
        assertEquals(1, rebuilt.size)
        assertEquals(Z.ActionType.PVZ_TAP_CELL, rebuilt[0].type)
    }

    @Test
    fun `循环次数能读回来`() {
        val rebuilt = PvzChainModel.rebuild(
            listOf(plant(1, 1, 1)),
            mapOf(
                PvzChainModel.ONCE to emptyList(),
                PvzChainModel.LOOP to listOf(tapCell(2, 2)),
                PvzChainModel.END to emptyList()
            ),
            loopRepeat = 7
        )
        assertEquals(7, PvzChainModel.loopRepeatOf(rebuilt))
    }

    @Test
    fun `链标记能落盘并读回`() {
        val a = Z.Action.parse(
            JSONObject("""{"type":"喂豆","pvzRow":"2","pvzCol":"3","pvzChain":"loop"}""")
        )!!
        assertEquals(PvzChainModel.LOOP, PvzChainModel.chainOf(a))
        assertEquals("loop", a.toJson().getString("pvzChain"))
    }

    @Test
    fun `重建后整份脚本仍能被解析`() {
        val rebuilt = PvzChainModel.rebuild(
            listOf(toast("x"), plant(1, 2, 3)),
            mapOf(
                PvzChainModel.ONCE to listOf(plant(1, 2, 3)),
                PvzChainModel.LOOP to listOf(plant(2, 3, 4)),
                PvzChainModel.END to emptyList()
            )
        )
        val text = rebuilt.joinToString("\r\n") { it.toJson().toString() }
        val base = """{"repeatCount":1,"delay":"0","delayUnit":0,"screenWidth":1280,"screenHeight":720}"""
        val parsed = ZjsFileFormat.parse(base + "\r\n" + text + "\r\n").script
        assertTrue("重新解析失败", parsed != null)
        assertEquals(rebuilt.size, parsed!!.actions.size)
    }

    // ---------------------------------------------------------- 真跑一遍

    private class Rec : ZExecutor.Injector {
        val calls = ArrayList<String>()
        override fun tap(x: Int, y: Int, holdMs: Int) { calls.add("tap($x,$y)") }
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
            calls.add("swipe($x1,$y1→$x2,$y2)")
        }
        override fun key(keyCode: Int, holdMs: Int) {}
        override fun inputText(text: String) {}
    }

    private fun runAll(actions: List<Z.Action>): List<String> {
        val rec = Rec()
        val ex = ZExecutor(rec, ZPosResolver.Screen(1280, 720, 1.5f), object : ZExecutor.Matcher {
            override fun findImage(pos: Z.PosData, rect: IntArray) = emptyList<ZPosResolver.Hit>()
            override fun findText(pos: Z.PosData, rect: IntArray) = emptyList<ZPosResolver.Hit>()
            override fun findColor(pos: Z.PosData, rect: IntArray) = emptyList<ZPosResolver.Hit>()
            override fun findNode(pos: Z.PosData, rect: IntArray) = emptyList<ZPosResolver.Hit>()
            override fun lastReason() = "本用例不用识别"
        }) {}
        val script = Z.Script(actions = actions + Z.Action(type = Z.ActionType.CONTROL_STOP))
        ex.run(script)
        return rec.calls
    }

    @Test
    fun `三条链编译后按 单次-循环xN-收尾 的顺序真的跑出来`() {
        val rebuilt = PvzChainModel.rebuild(
            listOf(plant(1, 1, 1)),
            mapOf(
                // 单次：卡槽1 种到 1行1列 → 卡槽(57,120) 滑到 格子(469,152)
                PvzChainModel.ONCE to listOf(plant(1, 1, 1)),
                // 循环：点 2行2列 两次 → 每个循环 2 个 tap
                PvzChainModel.LOOP to listOf(tapCell(2, 2), tapCell(2, 2)),
                // 收尾：点 5行9列
                PvzChainModel.END to listOf(tapCell(5, 9))
            ),
            loopRepeat = 2
        )
        val calls = runAll(rebuilt)
        // 1 次种植 + 2 轮 × 2 次点击 + 1 次收尾点击
        assertEquals(6, calls.size)
        assertEquals("swipe(57,120→469,152)", calls[0])
        assertEquals("tap(560,260)", calls[1])
        assertEquals("tap(560,260)", calls[2])
        assertEquals("tap(560,260)", calls[3])
        assertEquals("tap(560,260)", calls[4])
        assertEquals("tap(1197,584)", calls[5])
    }
}
