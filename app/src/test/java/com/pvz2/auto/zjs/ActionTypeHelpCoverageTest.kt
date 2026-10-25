package com.pvz2.auto.zjs

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守卫：**每个能加进脚本的动作类型都要有说明**（「添加动作」列表里的「?」）。
 *
 * 用户的要求是"动作类型也加个问号，点开会解释" —— 少一条就等于那个类型
 * 在列表里没有问号，用户只能靠名字猜。
 */
class ActionTypeHelpCoverageTest {

    @Test
    fun `所有可添加的动作类型都有说明`() {
        val missing = ZFieldEditor.addableActionTypes()
            .map { t -> ZFieldEditor.actionTypeHelpKey(t) }
            .filter { ZFieldHelp.ofAction(it) == null }
        assertTrue("这些动作类型没有说明：\n  " + missing.joinToString("\n  "), missing.isEmpty())
    }

    /**
     * 卡片里「类型」那一行的问号必须解释**这个动作干什么**。
     *
     * 用户反馈过两次：先是"动作类型也加个问号，点击会解释说明"，
     * 后来说"节点类型的问号不见了，不知道每个节点是干嘛的" ——
     * 中间被我在删别的东西时弄丢过一次。这条守住它。
     */
    @Test
    fun `卡片里类型行的问号是动作说明`() {
        val bad = ZFieldEditor.addableActionTypes().filter { t ->
            val key = ZFieldEditor.actionTypeHelpKey(t)
            val row = ZActionDescriber.describe(Z.Action(type = t))
                .firstOrNull { it.label == "类型" }
            row?.help == null || row.help != ZFieldHelp.ofAction(key)
        }.map { ZFieldEditor.actionTypeHelpKey(it) }
        assertTrue("这些类型的「类型」行没有动作说明：$bad", bad.isEmpty())
    }

    @Test
    fun `说明里点明了本机不支持的类型`() {
        // 这几行必须如实说"不支持"，否则用户配了才发现
        val mustWarn = listOf("系统提示", "输入内容")
        val bad = mustWarn.filter { label ->
            val h = ZFieldHelp.ofAction(label) ?: return@filter true
            !(h.contains("不支持") || h.contains("未实现") || h.contains("拿不到") ||
                h.contains("只实现") || h.contains("只能"))
        }
        assertTrue("这些类型的说明没交代限制：$bad", bad.isEmpty())
    }
}
