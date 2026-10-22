package com.pvz2.auto.zjs

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文字相似度。
 *
 * 这个数直接对应界面上的「相似度」滑块：算错了用户会一直调滑块，
 * 而真正的问题在算法里。
 */
class ZRealMatcherTextTest {

    @Test
    fun `完全一样是满分`() {
        assertTrue(ZRealMatcher.textSimilarity("开始", "开始") > 0.99)
    }

    @Test
    fun `包含也算高分`() {
        // 游戏里常带前后缀：「第 3 关」里找「3 关」
        val s = ZRealMatcher.textSimilarity("3 关", "第 3 关")
        assertTrue("包含应 >= 0.7，实际 $s", s >= 0.7)
        // 但长短差太远要扣分，避免 "1" 匹配上 "123456"
        val loose = ZRealMatcher.textSimilarity("1", "1234567890")
        assertTrue("过短的包含不该是满分，实际 $loose", loose < 0.9)
    }

    @Test
    fun `一个字只差是高分不是零分`() {
        val s = ZRealMatcher.textSimilarity("开始游戏", "开始油戏")
        assertTrue("应 >= 0.7，实际 $s", s >= 0.7)
    }

    @Test
    fun `完全无关是低分`() {
        val s = ZRealMatcher.textSimilarity("开始", "返回主界面")
        assertTrue("应 < 0.5，实际 $s", s < 0.5)
    }

    @Test
    fun `大小写不敏感`() {
        assertTrue(ZRealMatcher.textSimilarity("Start", "start") > 0.99)
    }

    @Test
    fun `空串是零分不崩`() {
        assertTrue(ZRealMatcher.textSimilarity("", "abc") == 0.0)
        assertTrue(ZRealMatcher.textSimilarity("abc", "") == 0.0)
    }
}