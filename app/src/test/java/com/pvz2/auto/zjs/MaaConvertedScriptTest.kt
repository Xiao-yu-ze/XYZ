package com.pvz2.auto.zjs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 转换出来的 Maa 脚本必须能解析、并且结构与手写脚本一致。
 *
 * 手写脚本（常规四脚僵王无尽）的做法是：**动作自己带条件 + 反复检查**，
 * 一个节点干完，不额外开路由/等待/跳转节点。第一版转换器把每个节点拆成四个，
 * 181 个节点、149 条跳转 —— 用户一眼看出不对。这里是那次的回归线。
 */
class MaaConvertedScriptTest {

    private fun parse(path: String): Z.Script {
        val f = File(path)
        assertTrue("文件不存在：${f.absolutePath}", f.isFile)
        val r = ZjsFileFormat.parse(f.readText())
        assertTrue("解析出错行：${r.badLines}", r.badLines.isEmpty())
        return r.script ?: error("没有解析出脚本")
    }

    private fun many(): Z.Script = parse("../build/zjs/塔香蕉_无尽.zjs")

    @Test
    fun `能解析且节点类型都认识`() {
        val s = many()
        assertTrue("节点太少：${s.actions.size}", s.actions.size > 60)
        assertTrue("应当有滑动", s.actions.any { it.type == Z.ActionType.SWIPE })
        assertTrue("应当有分支（等待候选）", s.actions.any { it.type == Z.ActionType.WAIT_CANDIDATES })
    }

    /** 旧设计每个节点后面跟一个「收尾等待」节点 —— 现在 post_delay 折进下一个节点的 delay。 */
    @Test
    fun `不再生成独立的等待节点`() {
        val bad = many().actions.filter { a ->
            a.type == Z.ActionType.CONTROL_WAIT &&
                (a.extra["desc"] as? String ?: a.common.desc ?: "").contains("收尾等待")
        }
        assertTrue("不该再有收尾等待节点：${bad.size} 个", bad.isEmpty())
    }

    /** 线性节点的识别要变成**条件 + repeatWhenFalse**，而不是另开一个等待候选。 */
    @Test
    fun `线性等待用条件表达而不是等待候选`() {
        val s = many()
        val withRetry = s.actions.filter { a ->
            val c = a.common.condition
            c != null && c.repeatWhenFalse
        }
        assertTrue("应当有带反复检查的条件：${withRetry.size}", withRetry.isNotEmpty())
        // 分支节点只应出现在真正的分叉处（本脚本 7 处）
        val waits = s.actions.count { it.type == Z.ActionType.WAIT_CANDIDATES }
        assertTrue("分支节点太多，说明又在拆节点：$waits", waits <= 12)
    }

    /** 每个分支都要有超时去向，不能"超时就掉进下一个无关节点"。 */
    @Test
    fun `每个分支都有超时处理`() {
        val bad = many().actions
            .filter { it.type == Z.ActionType.WAIT_CANDIDATES }
            .filter { a ->
                val jump = (a.extra["onTimeoutJump"] as? String).orEmpty()
                val stop = a.extra["onTimeoutStop"]
                jump.isEmpty() && (stop == null || stop.toString() != "true")
            }
        assertTrue("这些分支超时后没有去处：${bad.size} 个", bad.isEmpty())
    }

    /** 颜色必须写成真实可显示的值，并且上下界要保留（Maa 的判定依据）。 */
    @Test
    fun `颜色写成十六进制且保留下界上界`() {
        val s = many()
        val colorCond = s.actions.mapNotNull { it.common.condition }
            .firstOrNull { it.type == Z.ConditionType.COLOR }
        assertTrue("应当有颜色条件", colorCond != null)
        val pd = colorCond!!.posData
        assertTrue("颜色位置不该为空", pd != null)
        val extra = pd!!.extra
        assertTrue("上下界应当保留：$extra", extra.containsKey("colorLower"))
        // 不能再出现"凭空编的整数色"：颜色应当是 #rrggbb 形式
        val c = pd.color.orEmpty()
        assertTrue("颜色应当写成 #rrggbb，实际：$c", c.startsWith("#"))
        /*
         * Maa 的颜色判定是「每通道落在 [lower,upper]」的范围判定。
         * 写 similarPercent=100 等于要求像素完全等于某个值 —— 游戏画面里基本必然认不出。
         * （用户指出：调到 100% 就识别不出来。）
         */
        assertTrue("相似度不该是 100：${pd.similarPercent}", (pd.similarPercent ?: 0) < 100)
        // 也不能只判 roi 中心一个像素：Maa 是整个 roi 里找
        assertTrue("不该合成单像素判定", !extra.containsKey("limitPosX"))
        assertTrue("应当保留 roi 作为搜索范围", pd.limitArea != null)
    }

    /** 条件弹窗里要能看到范围（Maa 的真实判定依据），否则用户会以为就一个色值。 */
    @Test
    fun `条件弹窗里有颜色范围行`() {
        val s = many()
        val cond = s.actions.mapNotNull { it.common.condition }
            .first { it.type == Z.ConditionType.COLOR }
        val rows = ZConditionEditor.describe(cond)
        val range = rows.firstOrNull { it.label.contains("颜色范围") }
        assertTrue("条件弹窗里没有「颜色范围」行：${rows.map { it.label }}", range != null)
        assertTrue("范围行内容不该是空的：${range!!.value}", range.value.contains("~"))
    }

    @Test
    fun `分段脚本也能解析`() {
        for (i in 1..3) {
            val s = parse("../build/zjs/塔香蕉_第${i}段.zjs")
            assertTrue("第 $i 段没有节点", s.actions.isNotEmpty())
        }
    }

    /** anchor 是"跳进共享节点、再跳回调用方"，回跳必须落在本任务的节点上。 */
    @Test
    fun `共享节点的 anchor 回跳到本任务的节点`() {
        val s = many()
        val waiter = s.actions.firstOrNull { a ->
            a.type == Z.ActionType.WAIT_CANDIDATES &&
                (a.common.desc ?: "").contains("神器补给_确定3")
        }
        assertTrue("没找到补给链的分支节点", waiter != null)
        val targets = waiter!!.candidates.mapNotNull { it.jumpTo }
        assertTrue("回跳应当是 tower_banana_boss_识别开始，实际：$targets",
            targets.contains("tower_banana_boss_识别开始"))
        assertTrue("不该串到框架管线：$targets",
            targets.none { it.startsWith("frame_wj") || it.startsWith("无尽框架") })
    }

    @Test
    fun `跳转目标都指向存在的 jumpId`() {
        val s = many()
        val ids = s.actions.mapNotNull { it.common.jumpId }.toSet()
        val missing = ArrayList<String>()
        for (a in s.actions) {
            for (c in a.candidates) {
                val t = c.jumpTo ?: continue
                if (t !in ids) missing.add("候选 → $t")
            }
            val jump = (a.extra["jumpToPosition"] as? String).orEmpty().removePrefix(":")
            if (jump.isNotEmpty() && !jump.startsWith("&") && jump !in ids) missing.add("jumpTo → $jump")
            val to = (a.extra["onTimeoutJump"] as? String).orEmpty()
            if (to.isNotEmpty() && to !in ids) missing.add("onTimeoutJump → $to")
        }
        assertTrue("这些目标没有对应 jumpId：$missing", missing.isEmpty())
    }
}
