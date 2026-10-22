package com.pvz2.auto.zjs

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 颜色字符串解析。
 *
 * 单独测它是因为出错时**没有任何提示**：把 #FF0000 读成蓝色，
 * 匹配照样跑，只是永远匹配不上，用户会去调阈值调到天亮。
 */
class ZRealMatcherColorTest {

    @Test
    fun `六位十六进制按 RGB 解析`() {
        assertArrayEquals(intArrayOf(255, 0, 0), ZRealMatcher.parseHexRgb("FF0000"))
        assertArrayEquals(intArrayOf(0, 255, 0), ZRealMatcher.parseHexRgb("00ff00"))
        assertArrayEquals(intArrayOf(0, 0, 255), ZRealMatcher.parseHexRgb("0000FF"))
        assertArrayEquals(intArrayOf(0x12, 0x34, 0x56), ZRealMatcher.parseHexRgb("123456"))
    }

    @Test
    fun `带井号或 0x 前缀都能认`() {
        assertArrayEquals(intArrayOf(255, 0, 0), ZRealMatcher.parseHexRgb("#FF0000"))
        assertArrayEquals(intArrayOf(255, 0, 0), ZRealMatcher.parseHexRgb("0xFF0000"))
        assertArrayEquals(intArrayOf(255, 0, 0), ZRealMatcher.parseHexRgb("  0XFF0000  "))
    }

    @Test
    fun `八位按 AARRGGBB 取低六位`() {
        // 带 alpha 的写法在别的地方很常见，别把 AA 当成红色
        assertArrayEquals(intArrayOf(0, 255, 0), ZRealMatcher.parseHexRgb("8000FF00"))
    }

    @Test
    fun `认不出的返回 null 而不是猜一个颜色`() {
        assertNull(ZRealMatcher.parseHexRgb(null))
        assertNull(ZRealMatcher.parseHexRgb(""))
        assertNull(ZRealMatcher.parseHexRgb("FFF"))
        assertNull(ZRealMatcher.parseHexRgb("GGGGGG"))
        assertNull(ZRealMatcher.parseHexRgb("红色"))
    }
}