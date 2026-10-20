package com.pvz2.auto.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 虚拟屏 flags 的位运算断言。
 *
 * 这些数字错了的表现是「整机性失败」：屏建出来了但游戏挂不上、或触摸进不去、
 * 或直接 SecurityException —— 而且错误信息不会告诉你是哪一位。所以用单测把
 * 加法钉死，真机上再用 logcat 里 `Pvz2UserService` 打出来的实际值复核一次。
 *
 * 期望值来源：AOSP android14-release 的 `DisplayManager.java` 常量定义。
 */
class VirtualDisplayFlagsTest {

    @Test
    fun `单个位的值与 AOSP 一致`() {
        // 逐位核对，防止以后有人"顺手改一个数"而没人发现。
        assertEquals(1, VirtualDisplayFlags.PUBLIC)
        assertEquals(2, VirtualDisplayFlags.PRESENTATION)
        assertEquals(8, VirtualDisplayFlags.OWN_CONTENT_ONLY)
        assertEquals(64, VirtualDisplayFlags.SUPPORTS_TOUCH)
        assertEquals(128, VirtualDisplayFlags.ROTATES_WITH_CONTENT)
        assertEquals(256, VirtualDisplayFlags.DESTROY_CONTENT_ON_REMOVAL)
        assertEquals(1024, VirtualDisplayFlags.TRUSTED)
        assertEquals(2048, VirtualDisplayFlags.OWN_DISPLAY_GROUP)
        assertEquals(4096, VirtualDisplayFlags.ALWAYS_UNLOCKED)
        assertEquals(8192, VirtualDisplayFlags.TOUCH_FEEDBACK_DISABLED)
        assertEquals(16384, VirtualDisplayFlags.OWN_FOCUS)
        assertEquals(32768, VirtualDisplayFlags.DEVICE_DISPLAY_GROUP)
    }

    @Test
    fun `三档组合的合计`() {
        // BASE 从 459 变成 971：多了 SHOULD_SHOW_SYSTEM_DECORATIONS(512)。
        // 加它是因为「有系统装饰的屏才被 WMS 当作可承载 Activity 的正常屏」。
        assertEquals(971, VirtualDisplayFlags.BASE)
        assertEquals(15360, VirtualDisplayFlags.API33_ADDED)
        assertEquals(16384, VirtualDisplayFlags.API34_ADDED)
    }

    @Test
    fun `按系统版本算出的 flags 符合阶梯`() {
        // Android 8~12：只有基础集
        assertEquals(971, VirtualDisplayFlags.forSdk(26))
        assertEquals(971, VirtualDisplayFlags.forSdk(30))
        assertEquals(971, VirtualDisplayFlags.forSdk(31))
        assertEquals(971, VirtualDisplayFlags.forSdk(32))

        // Android 13：加 TRUSTED 那一组
        assertEquals(16331, VirtualDisplayFlags.forSdk(33))

        // Android 14 及以后：再加 OWN_FOCUS + DEVICE_DISPLAY_GROUP
        assertEquals(32715, VirtualDisplayFlags.forSdk(34))
        assertEquals(32715, VirtualDisplayFlags.forSdk(35))
        assertEquals(32715, VirtualDisplayFlags.forSdk(36))
    }

    @Test
    fun `非法版本号退化为基础集 不会算出负数或溢出`() {
        assertEquals(971, VirtualDisplayFlags.forSdk(0))
        assertEquals(971, VirtualDisplayFlags.forSdk(-1))
        assertEquals(32715, VirtualDisplayFlags.forSdk(Int.MAX_VALUE))
    }

    @Test
    fun `任何版本都不含 AUTO_MIRROR 位`() {
        // 这一位是唯一会让 shell 身份被 SecurityException 拦下的开关（见类注释 ②）。
        // 它一旦出现，createVirtualDisplay 会落到 canProjectVideo() 检查分支。
        for (sdk in 26..40) {
            val flags = VirtualDisplayFlags.forSdk(sdk)
            assertFalse("SDK $sdk 的 flags 里混进了 AUTO_MIRROR(4)", (flags and 4) != 0)
        }
    }

    @Test
    fun `任何版本都带 PUBLIC 位`() {
        // 缺 PUBLIC → 屏被标 FLAG_PRIVATE → 非 owner uid（游戏）挂不上窗口。
        for (sdk in 26..40) {
            val flags = VirtualDisplayFlags.forSdk(sdk)
            assertTrue("SDK $sdk 的 flags 缺 PUBLIC(1)", (flags and VirtualDisplayFlags.PUBLIC) != 0)
        }
    }

    @Test
    fun `真机实测的那组位必须都在`() {
        // 来源：Lenovo TB323FU / Android 16 上建屏成功后，dumpsys display 报出的
        // DisplayDeviceInfo 里真实带有这些 FLAG。它们是「窗口能挂上副屏」的充分条件，
        // 少一个都可能退回 MuMu 那种 untrusted 状态，所以钉死。
        val flags = VirtualDisplayFlags.forSdk(36)
        for ((name, bit) in listOf(
            "TRUSTED" to VirtualDisplayFlags.TRUSTED,
            "OWN_FOCUS" to VirtualDisplayFlags.OWN_FOCUS,
            "ALWAYS_UNLOCKED" to VirtualDisplayFlags.ALWAYS_UNLOCKED,
            "TOUCH_FEEDBACK_DISABLED" to VirtualDisplayFlags.TOUCH_FEEDBACK_DISABLED,
            "SHOULD_SHOW_SYSTEM_DECORATIONS" to VirtualDisplayFlags.SHOULD_SHOW_SYSTEM_DECORATIONS,
            "DESTROY_CONTENT_ON_REMOVAL" to VirtualDisplayFlags.DESTROY_CONTENT_ON_REMOVAL,
        )) {
            assertTrue("Android 16 的 flags 缺 $name", (flags and bit) != 0)
        }
    }

    @Test
    fun `describe 把位拆成名字 且能指出未知位`() {
        val sdk34 = VirtualDisplayFlags.describe(VirtualDisplayFlags.forSdk(34))
        assertTrue(sdk34.contains("PUBLIC"))
        assertTrue(sdk34.contains("TRUSTED"))
        assertTrue(sdk34.contains("OWN_FOCUS"))
        assertFalse("已知位都认识，不该报未知位", sdk34.contains("未知位"))

        // 塞一个没人认识的位进去，describe 必须显式点出来 —— 排查 ROM 魔改时靠这个。
        val withUnknown = VirtualDisplayFlags.describe(VirtualDisplayFlags.BASE or 0x400000)
        assertTrue(withUnknown.contains("未知位"))
    }
}
