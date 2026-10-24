package com.pvz2.auto.zjs

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守卫：**说明文字里不许出现 Markdown 标记**。
 *
 * 弹窗是纯文本，反引号和星号会原样显示 —— 用户看到的是
 * 「`.3. 第 3 条」这种，以为是乱码。我第一版就把 ` ` 和 ** ** 写进去了，
 * 在设备上一眼就露馅。这条测试把它钉住。
 */
class HelpTextPlainTest {

    private fun check(what: String, pairs: List<Pair<String, String>>): List<String> {
        val bad = ArrayList<String>()
        for ((k, v) in pairs) {
            if (v.contains("`") || v.contains("**")) bad.add("$what · $k：$v")
        }
        return bad
    }

    @Test
    fun `字段说明是纯文本`() {
        val pairs = ZFieldHelp.keys().map { it to (ZFieldHelp.of(it) ?: "") }
        val bad = check("字段", pairs)
        assertTrue("这些说明里有 Markdown 标记：\n  " + bad.joinToString("\n  "), bad.isEmpty())
    }

    @Test
    fun `动作类型说明是纯文本`() {
        val pairs = ZFieldEditor.addableActionTypes().mapNotNull { t ->
            val label = t.controlRunType?.let { c -> "${t.wire}·$c" } ?: t.wire
            ZFieldHelp.ofAction(label)?.let { label to it }
        }
        val bad = check("动作", pairs)
        assertTrue("这些说明里有 Markdown 标记：\n  " + bad.joinToString("\n  "), bad.isEmpty())
    }
}
