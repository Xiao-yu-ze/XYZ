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

/**
 * 通过特权 shell 注入触摸。
 *
 * ## displayId 为什么是构造参数
 *
 * 虚拟屏是**另一块屏**。注入到主屏（display 0）的事件，虚拟屏上的游戏
 * 一个字都收不到 —— 表现就是"点了完全没反应"。
 *
 * 之前这里写死了 `tapOnDisplay(0, ...)`，于是整条识别→点击链路一直在点主屏。
 * 用户说"虚拟屏点不了、控制不了游戏"，根因就是这个：不是注入坏了，
 * 是**从来没往虚拟屏发过事件**。
 *
 * 所以要由创建者告诉它"打到哪块屏上"。`displayId = 0` 仍然合法
 * （直接在主屏跑、没建虚拟屏时）。
 *
 * ## 坐标是**虚拟屏自己的坐标系**
 *
 * 1280×720 虚拟屏上的 (640, 360)，就传 (640, 360) —— 不需要换算到物理屏。
 * 识别侧给的也是这套坐标，两边天然对齐。
 */
class ShizukuTouchInjector(
    private val client: ShizukuUserServiceClient,
    /** 目标显示器。0 = 主屏；虚拟屏刚建好时由会话提供。 */
    private val displayIdProvider: () -> Int = { 0 }
) : TouchInjector {

    /** 当前打到哪块屏。日志里用得到。 */
    val displayId: Int get() = displayIdProvider()

    /**
     * 注入一次点击。
     *
     * ## 为什么不再静默吞掉失败
     *
     * 之前是 `runCatching { service.tap(...) }` —— AIDL 的 `tap` 是 void，
     * 成败根本无从判断。自动化最怕这种：脚本照常往下走，界面停在原地，
     * 最后表现成"卡在某一步"，而且没有任何日志能指出是哪一次点击丢了。
     *
     * 现在用 `tapOnDisplay`（返回 Boolean）并一律打日志。
     * 上层若需要感知，可读 [lastTapOk]。
     */
    /**
     * 带按下时长的点击。[holdMs] <= 0 = 极速模式（不等事件结束）。
     *
     * 以前这里固定传 `DEFAULT_HOLD_MS`，于是动作里的 `duration` 对点击**完全无效**：
     * 长按被压成 60ms、极速模式也无从实现。
     */
    override fun tap(x: Float, y: Float, holdMs: Int) {
        val service = client.peek()
        val target = displayIdProvider()
        if (service == null) {
            Log.w(TAG, "点击 (${x.toInt()},${y.toInt()}) 失败：Shizuku 服务未连接")
            lastTapOk = false
            return
        }
        val ok = runCatching { service.tapOnDisplay(target, x.toInt(), y.toInt(), holdMs) }
            .onFailure { Log.w(TAG, "点击 (${x.toInt()},${y.toInt()}) 抛异常", it) }
            .getOrDefault(false)
        lastTapOk = ok
        if (!ok) Log.w(TAG, "点击 (${x.toInt()},${y.toInt()}) 未被系统接受 display=$target")
    }

    override fun tap(x: Float, y: Float) {
        val service = client.peek()
        val target = displayIdProvider()
        if (service == null) {
            Log.w(TAG, "点击 (${x.toInt()},${y.toInt()}) 失败：Shizuku 服务未连接")
            lastTapOk = false
            return
        }
        val ok = runCatching { service.tapOnDisplay(target, x.toInt(), y.toInt(), DEFAULT_HOLD_MS) }
            .onFailure { Log.w(TAG, "点击 (${x.toInt()},${y.toInt()}) 抛异常", it) }
            .getOrDefault(false)
        lastTapOk = ok
        if (!ok) {
            // 这条日志是排查"点不动"的第一现场。常见原因按可能性排序：
            //  1. 游戏窗口不在这块屏上（任务搬失败 / 启动落回主屏）
            //  2. 这块屏不 trusted / 没有可聚焦窗口
            //  3. 游戏窗口自己设了 FLAG_NOT_TOUCHABLE
            //  4. 缺 INJECT_EVENTS（会抛 SecurityException，不是返回 false）
            Log.w(TAG, "点击 (${x.toInt()},${y.toInt()}) 未被系统接受 display=$target")
        }
    }

    /**
     * 开始一次跟随手指的手势。
     *
     * ## 为什么在本进程注入，不走 AIDL
     *
     * 之前写成转发到 shell 进程、那边再用 `InputInjector` 注入 —— 但
     * `InputInjector` 靠 `FakeContext` 取 `InputManager`，而 FakeContext
     * 只在 App 进程里构造得出来。放到 shell 进程里必然失败，
     * `gestureDown` 恒返回 false，界面表现成"点了没反应"。
     *
     * 直接在本进程调 `InputInjector`：既能用，每个 MOVE 还省一次 AIDL 往返。
     */
    override fun beginGesture(x: Float, y: Float): com.pvz2.auto.core.StreamingGesture? {
        // 走 AIDL 到 shell 进程，用 `input -d N motionevent` 注入。
        //
        //   本地 `InputInjector` 那条路走不通：它要 `InputEvent.setDisplayId(int)`，
        //   而 Android 16 把这个方法删了，每个事件都抛 NoSuchMethodException。
        //   `input` 命令是系统自带的，和一直能用的 `input -d N tap` 同一套权限。
        val service = client.peek() ?: run {
            Log.w(TAG, "手势失败：Shizuku 服务未连接")
            return null
        }
        val target = displayIdProvider()
        if (target <= 0) {
            Log.w(TAG, "手势失败：displayId=$target 无效")
            return null
        }
        val ok = runCatching { service.gestureDown(target, x, y) }
            .onFailure { Log.e(TAG, "gestureDown 异常", it) }
            .getOrDefault(false)
        if (!ok) {
            Log.w(TAG, "手势按下未被接受 display=$target ($x,$y)")
            return null
        }
        return object : com.pvz2.auto.core.StreamingGesture {
            override fun moveTo(x: Float, y: Float) {
                runCatching { service.gestureMove(x, y) }
            }

            override fun finish(x: Float, y: Float) {
                runCatching { service.gestureUp(x, y) }
            }

            override fun cancel() {
                runCatching { service.gestureCancel() }
            }
        }
    }

    override fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val service = client.peek()
        val target = displayIdProvider()
        if (service == null) {
            Log.w(TAG, "滑动失败：Shizuku 服务未连接")
            lastSwipeOk = false
            return
        }
        val ok = runCatching {
            service.swipeOnDisplay(
                target, x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt(), durationMs.toInt()
            )
        }.onFailure { Log.w(TAG, "滑动 ($x1,$y1)->($x2,$y2) 抛异常", it) }
            .getOrDefault(false)
        lastSwipeOk = ok
        if (!ok) Log.w(TAG, "滑动 ($x1,$y1)->($x2,$y2) 未被系统接受 display=$target")
    }

    /**
     * 多指真并发。
     *
     * 走服务端的 `multiSwipeOnDisplay` —— 那里自己构造带 N 个指针的
     * MotionEvent。不能用 `input motionevent`：那条命令一个事件只带一个
     * 指针，连发两次是两个先后动作，不是多指。
     */
    override fun multiSwipe(paths: List<FloatArray>, durationMs: Long) {
        if (paths.isEmpty()) return
        val service = client.peek()
        val target = displayIdProvider()
        if (service == null) {
            Log.w(TAG, "多指滑动失败：Shizuku 服务未连接")
            return
        }

        val n = paths.size
        val sxs = FloatArray(n) { paths[it].getOrElse(0) { 0f } }
        val sys = FloatArray(n) { paths[it].getOrElse(1) { 0f } }
        val exs = FloatArray(n) { paths[it].getOrElse(2) { 0f } }
        val eys = FloatArray(n) { paths[it].getOrElse(3) { 0f } }

        val ok = runCatching {
            service.multiSwipeOnDisplay(
                target, sxs, sys, exs, eys, durationMs.toInt().coerceAtLeast(1)
            )
        }.onFailure { Log.w(TAG, "多指滑动 n=$n 抛异常", it) }
            .getOrDefault(false)

        if (!ok) Log.w(TAG, "多指滑动 n=$n 未被系统接受 display=$target")
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
