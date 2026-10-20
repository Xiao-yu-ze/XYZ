package com.pvz2.auto.shizuku

import android.util.Log

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import com.pvz2.auto.core.ScreenCapturer
import com.pvz2.auto.core.TouchInjector
import com.pvz2.auto.core.argb8888Options
import com.pvz2.auto.core.toMatAndRecycle
import org.opencv.core.Mat

/**
 * Screenshot through the privileged `screencap -p`, decoded straight from the returned
 * descriptor so no temporary file is ever written and nothing has to be cleaned up.
 */
class ShizukuScreenCapturer(
    private val client: ShizukuUserServiceClient
) : ScreenCapturer {

    override fun capture(): Mat? {
        val service = client.peek() ?: return null
        val descriptor = runCatching { service.screencap() }.getOrNull() ?: return null

        // AutoCloseInputStream owns the descriptor, so the read end of the pipe is closed
        // even when decoding throws - without this the remote copy-writer could block.
        val bitmap: Bitmap? = runCatching {
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
                BitmapFactory.decodeStream(stream, null, argb8888Options())
            }
        }.getOrNull()

        return bitmap?.toMatAndRecycle()
    }
}

/** Touch injection through the privileged `input` command. */
class ShizukuTouchInjector(
    private val client: ShizukuUserServiceClient
) : TouchInjector {

    /**
     * 注入一次点击。
     *
     * ## 为什么不再静默吞掉失败
     *
     * 之前是 `runCatching { service.tap(...) }` —— AIDL 的 `tap` 返回 Boolean，
     * 这里把它丢掉了，于是"点击根本没生效"和"点击成功"在上层看来一模一样。
     * 自动化最怕这种：脚本照常往下走，界面停在原地，最后表现成"卡在某一步"，
     * 而且没有任何日志能指出是哪一次点击丢了。
     *
     * 现在失败一律打日志。上层若需要感知，可读 [lastTapOk]。
     */
    override fun tap(x: Float, y: Float) {
        val service = client.peek()
        if (service == null) {
            Log.w(TAG, "点击 (${x.toInt()},${y.toInt()}) 失败：Shizuku 服务未连接")
            lastTapOk = false
            return
        }
        // 用 tapOnDisplay 而不是 tap：前者返回 boolean，能知道系统是否接受；
        // 后者是 void，注入失败也看不出来。displayId 传 0（默认屏）——
        // 走虚拟屏的注入在 DisplayInputInjector 那条路上，不经这里。
        val ok = runCatching { service.tapOnDisplay(0, x.toInt(), y.toInt(), DEFAULT_HOLD_MS) }
            .onFailure { Log.w(TAG, "点击 (${x.toInt()},${y.toInt()}) 抛异常", it) }
            .getOrDefault(false)
        lastTapOk = ok
        if (!ok) Log.w(TAG, "点击 (${x.toInt()},${y.toInt()}) 未被系统接受（通常是缺 INJECT_EVENTS）")
    }

    override fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val service = client.peek()
        if (service == null) {
            Log.w(TAG, "滑动失败：Shizuku 服务未连接")
            lastSwipeOk = false
            return
        }
        val ok = runCatching {
            service.swipeOnDisplay(0, x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt(), durationMs.toInt())
        }.onFailure { Log.w(TAG, "滑动 ($x1,$y1)->($x2,$y2) 抛异常", it) }
            .getOrDefault(false)
        lastSwipeOk = ok
        if (!ok) Log.w(TAG, "滑动 ($x1,$y1)->($x2,$y2) 未被系统接受")
    }

    /** 上一次点击是否被系统接受。调用方想感知失败时读它。 */
    @Volatile
    var lastTapOk: Boolean = true
        private set

    /** 上一次滑动是否被系统接受。 */
    @Volatile
    var lastSwipeOk: Boolean = true
        private set

    private companion object {
        const val TAG = "ShizukuTouch"

        /** 按下时长。太短低帧率下会丢点击（会话 7 已定位）。 */
        const val DEFAULT_HOLD_MS = 60
    }
}
