package com.pvz2.auto.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 渠道包名的断言。
 *
 * 这些字符串抄错一个字母，表现就是「启动游戏」静默失败 —— 找不到应用、
 * 后面建屏搬移全都不发生，而错误信息里只有一句"没能搬到虚拟屏"。
 * 所以值得钉死。
 */
class Pvz2ChannelsTest {

    @Test
    fun `包名清单与 参考管线 的 jobset 一致`() {
        // 逐条对照 参考管线 里 StartApp 的 package 字段。
        val expected = mapOf(
            "com.popcap.pvz2cthdhwct" to "华为",
            "com.popcap.pvz2cthdlx" to "联想",
            "com.popcap.pvz2cthd360" to "360",
            "com.popcap.pvz2cthduc" to "九游",
            "com.popcap.pvz2cthdop" to "OPPO",
            "com.tencent.tmgp.pvz2hdtxyyb" to "应用宝",
            "com.popcap.pvz2cthd.bilibili" to "B 服",
            "com.popcap.pvz2cthdbbg" to "vivo",
            "com.popcap.pvz2cthd.honor" to "荣耀",
            "com.popcap.pvz2cthd.g.baidu" to "百度",
            "com.popcap.pvz2cthdbazhang" to "7723",
            "com.popcap.pvz2cthd4399" to "4399",
            "com.popcap.pvz2cthdxm" to "小米",
        )
        for ((pkg, label) in expected) {
            val channel = Pvz2Channels.ALL.firstOrNull { it.packageName == pkg }
            assertNotNull("缺少包名 $pkg（$label）", channel)
            assertEquals("包名 $pkg 的标签不对", label, channel!!.label)
        }
    }

    @Test
    fun `同一个包被多个渠道复用是预期行为`() {
        // 官服 / TapTap / 好游快报 用的是同一个安装包，只是登录入口不同。
        val shared = Pvz2Channels.ALL.filter { it.packageName == "com.popcap.pvz2cthdbk" }
        assertEquals(3, shared.size)
    }

    @Test
    fun `key 唯一`() {
        val keys = Pvz2Channels.ALL.map { it.key }
        assertEquals("有重复的 key: $keys", keys.size, keys.toSet().size)
    }

    @Test
    fun `byKey 能查到 查不到返回 null`() {
        assertEquals("com.popcap.pvz2cthdop", Pvz2Channels.byKey("oppo")?.packageName)
        assertNull(Pvz2Channels.byKey("不存在的渠道"))
    }

    @Test
    fun `detect 从已安装列表里认出渠道`() {
        val detected = Pvz2Channels.detect(
            listOf("com.android.chrome", "com.popcap.pvz2cthdbbg", "com.tencent.mm")
        )
        assertEquals("vivo", detected?.key)
    }

    @Test
    fun `detect 一个都没装时返回 null`() {
        assertNull(Pvz2Channels.detect(listOf("com.android.chrome", "com.tencent.mm")))
    }

    @Test
    fun `默认渠道是 OPPO`() {
        assertEquals("oppo", Pvz2Channels.DEFAULT.key)
        assertEquals("com.popcap.pvz2cthdop", Pvz2Channels.DEFAULT.packageName)
    }

    @Test
    fun `所有包名都符合 Android 包名格式`() {
        val pattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        for (channel in Pvz2Channels.ALL) {
            assertTrue(
                "${channel.label} 的包名不合法: ${channel.packageName}",
                pattern.matches(channel.packageName)
            )
        }
    }
}
