package com.pvz2.auto.zjs

import com.pvz2.auto.core.TouchInjector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 把协议的动作参数翻译成真实的注入调用。
 *
 * 这里断言的是**注入通道收到什么** —— 参数丢一个，游戏里就是另一种操作。
 */
class ZTouchInjectorAdapterTest {

    private class RecordingTouch : TouchInjector {
        val calls = ArrayList<String>()

        override fun tap(x: Float, y: Float) {
            calls.add("tap($x,$y)")
        }

        override fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
            calls.add("swipe($x1,$y1,$x2,$y2,$durationMs)")
        }
    }

    @Test
    fun `短点击走 tap 通道`() {
        val rec = RecordingTouch()
        ZTouchInjectorAdapter(rec).tap(100, 200, 100)
        assertEquals(listOf("tap(100.0,200.0)"), rec.calls)
    }

    @Test
    fun `长按不能退化成短按`() {
        // 协议里 duration 是按下时长。长按和短按在很多游戏里是两种操作 ——
        // 丢了时长用户会以为"长按没生效"。
        val rec = RecordingTouch()
        ZTouchInjectorAdapter(rec).tap(100, 200, 800)
        assertEquals(1, rec.calls.size)
        // 起点终点相同 = 按住不动
        assertEquals("swipe(100.0,200.0,100.0,200.0,800)", rec.calls[0])
    }

    @Test
    fun `滑动时长原样传下去`() {
        val rec = RecordingTouch()
        ZTouchInjectorAdapter(rec).swipe(10, 20, 30, 40, 1500)
        assertEquals(listOf("swipe(10.0,20.0,30.0,40.0,1500)"), rec.calls)
    }

    @Test
    fun `没配按键通道时明确抛错而不是静默丢弃`() {
        // 静默丢弃的话，用户看到脚本跑完了但游戏里什么都没发生
        val rec = RecordingTouch()
        val adapter = ZTouchInjectorAdapter(rec)
        assertNull(adapter.delegateKey)
        try {
            adapter.key(29, 100)
            assertTrue("应该抛异常", false)
        } catch (e: Exception) {
            assertTrue("应是空指针类错误", e is NullPointerException || e is IllegalStateException)
        }
    }

    @Test
    fun `配了按键通道就走通道`() {
        val got = ArrayList<Pair<Int, Int>>()
        val adapter = ZTouchInjectorAdapter(RecordingTouch())
        adapter.delegateKey = { code, hold -> got.add(code to hold) }
        adapter.key(29, 150)
        assertEquals(listOf(29 to 150), got)
    }

    @Test
    fun `文字输入走通道`() {
        val got = ArrayList<String>()
        val adapter = ZTouchInjectorAdapter(RecordingTouch())
        adapter.delegateText = { got.add(it) }
        adapter.inputText("hello")
        assertEquals(listOf("hello"), got)
    }
}
