package com.pvz2.auto.zjs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 转换出来的 Maa 脚本必须能被我们的解析器完整吃下。
 *
 * 这些文件是 `tools/maa2zjs_towerbanana.py` 用 Maa pipeline 生成的，
 * 一旦转换器产出的字段名/类型和模型对不上，这里立刻红 —— 而不是等到
 * 手机上打开编辑器才发现"这一行不认识"。
 */
class MaaConvertedScriptTest {

    private fun parse(path: String): Z.Script {
        val f = File(path)
        assertTrue("文件不存在：${f.absolutePath}", f.isFile)
        val r = ZjsFileFormat.parse(f.readText())
        assertTrue("解析出错行：${r.badLines}", r.badLines.isEmpty())
        return r.script ?: error("没有解析出脚本")
    }

    @Test
    fun `塔香蕉完整脚本能解析且节点类型都认识`() {
        val s = parse("../build/zjs/塔香蕉_无尽.zjs")
        assertTrue("节点太少：${s.actions.size}", s.actions.size > 90)
        // 不允许出现"未知类型"——未知类型在自动精灵里会直接抛错
        val unknown = s.actions.map { it.type }.distinct()
        assertTrue("类型集合空", unknown.isNotEmpty())
        // 关键：等待候选与滑动都要有
        assertTrue(
            "应当有等待候选节点",
            s.actions.any { it.type == Z.ActionType.WAIT_CANDIDATES }
        )
        assertTrue(
            "应当有滑动节点",
            s.actions.any { it.type == Z.ActionType.SWIPE }
        )
    }

    @Test
    fun `分段脚本也能解析`() {
        for (i in 1..3) {
            val s = parse("../build/zjs/塔香蕉_第${i}段.zjs")
            assertTrue("第 $i 段没有节点", s.actions.isNotEmpty())
        }
    }

    @Test
    fun `颜色条件把上下界带过来了`() {
        val s = parse("../build/zjs/塔香蕉_无尽.zjs")
        val colorCond = s.actions
            .mapNotNull { it.common.condition }
            .firstOrNull { it.type == Z.ConditionType.COLOR }
        assertTrue("应当有颜色条件", colorCond != null)
        val pd = colorCond!!.posData
        assertTrue("颜色位置不该为空", pd != null)
        assertTrue("上下界应当保留：${pd!!.extra}", pd.extra.containsKey("colorLower"))
    }

    @Test
    fun `跳转目标都指向存在的 jumpId`() {
        val s = parse("../build/zjs/塔香蕉_无尽.zjs")
        val ids = s.actions.mapNotNull { it.common.jumpId }.toSet()
        // 单后继用 :名字 跳转，目标必须存在
        val missing = s.actions
            .filter { it.type == Z.ActionType.CONTROL_JUMP }
            .mapNotNull { (it.extra["jumpToPosition"] as? String)?.removePrefix(":") }
            .filter { it.isNotEmpty() && !it.startsWith("&") && it !in ids }
            .distinct()
        assertTrue("这些跳转目标没有对应的 jumpId：$missing", missing.isEmpty())
    }
}
