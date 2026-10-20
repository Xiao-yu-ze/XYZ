package com.pvz2.auto.shizuku

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent

/**
 * 往**指定 display** 注入触摸。
 *
 * `input tap` 做不到两件事，所以必须走 `InputManager.injectInputEvent`：
 *  1. `input tap` 只能打到默认屏（display 0）—— 搬去副屏的游戏收不到；
 *  2. `input tap` 的按下时长极短，低帧率下会丢点击（会话 7 定位过）。
 *
 * 两个细节照 AOSP 的用法：
 *  · **DOWN 用 `WAIT_FOR_FINISH(2)`**：等按下真的进管线再返回，否则紧跟的 UP
 *    可能和它在同一个 vsync 里被合并掉；
 *  · **UP 用 `ASYNC(0)`**：不必等，省一帧延迟。
 *
 * 抽成单独对象是为了让生产路径（[Pvz2UserService]）和诊断路径（[VdProbeMain]）
 * 共用同一份实现 —— 诊断工具如果和线上代码不是同一套逻辑，它验出来的东西就不算数。
 */
internal object DisplayInputInjector {

    private const val TAG = "DisplayInputInjector"

    /** `InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH` */
    private const val MODE_WAIT_FOR_FINISH = 2

    /** `InputManager.INJECT_INPUT_EVENT_MODE_ASYNC` */
    private const val MODE_ASYNC = 0

    /**
     * 在 [displayId] 上点一下。
     *
     * @param holdMs 最短按住时长。低于 1 帧（约 16ms）的点击在低帧率设备上可能被丢弃，
     *   建议由调用方按目标帧率给出（会话 7 的推算：`max(50, 1000/fps * 1.5)`）。
     * @return true 表示系统接受了这次注入；false 通常是缺 `INJECT_EVENTS` 权限
     */
    fun tap(displayId: Int, x: Int, y: Int, holdMs: Int): Boolean {
        val hold = holdMs.coerceIn(1, 2_000).toLong()
        val downTime = SystemClock.uptimeMillis()
        // 按住时长的终点。**只体现在事件时间戳上**，不靠真睡眠。
        val upTime = downTime + hold

        val downOk = inject(
            displayId, downTime, downTime, MotionEvent.ACTION_DOWN, x, y, MODE_WAIT_FOR_FINISH
        )
        // ★ 这里**不能** Thread.sleep。
        //
        //   之前是 `Thread.sleep(holdMs)`（默认 60ms），而这是在 **Binder
        //   线程**上执行的 —— AIDL 是同步调用，一次点击就占住一个 Binder
        //   线程整整 60ms。UserService 的 Binder 池默认 16 个线程，
        //   连续点击点满 16 个之后，同进程的 acquireFrameFd（帧轮询）
        //   就拿不到线程，采集直接停摆。
        //
        //   更关键的是：它是"动作最快 1ms"这个需求的**架构级天花板** ——
        //   无论识别多快，一次打断的下限都是
        //       hold(60) + DOWN(5~15) + UP(5~15) ≈ 75~95ms。
        //
        //   而 InputDispatcher 判定"按住多久"用的是 MotionEvent 的
        //   **eventTime 差值**，不是两次调用之间真实隔了多久
        //   （`input swipe` 就是这么实现的：两个事件背靠背发出，
        //     时间戳相差 duration）。所以直接给 UP 一个未来的时间戳即可。
        val upOk = inject(
            displayId, downTime, upTime, MotionEvent.ACTION_UP, x, y, MODE_ASYNC
        )

        if (!downOk || !upOk) {
            Log.w(TAG, "注入被拒 display=$displayId ($x,$y) down=$downOk up=$upOk")
        }
        return downOk && upOk
    }

    private fun inject(
        displayId: Int,
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Int,
        y: Int,
        mode: Int
    ): Boolean {
        val event = MotionEvent.obtain(downTime, eventTime, action, x.toFloat(), y.toFloat(), 0)
        return try {
            // 必须声明来源是触摸屏。`MotionEvent.obtain()` 造出来的事件 source 是 0，
            // InputDispatcher 无法据此路由，注入会直接返回 false —— 这一条排查了很久，
            // 因为日志只说「没有可触摸窗口」，看着像窗口问题，其实是事件缺 source。
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            setDisplayId(event, displayId)
            injectInputEvent(event, mode)
        } catch (t: Throwable) {
            Log.e(TAG, "构造/注入事件失败", t)
            false
        } finally {
            // 注意是 recycle()，不是 release() —— InputEvent 上只有前者。
            // 不回收会泄漏 native 侧的 MotionEvent 池。
            event.recycle()
        }
    }

    /**
     * `InputEvent.setDisplayId(int)` 是 @hide，编译期看不到，只能反射。
     *
     * 在 app_process（shell/root 身份）里反射隐藏 API 不受限制；如果这段代码被拿到
     * 普通应用进程里跑，这里会失败并打日志 —— 那样注入也就打不到副屏了。
     */
    private fun setDisplayId(event: MotionEvent, displayId: Int) {
        runCatching {
            MotionEvent::class.java
                .getMethod("setDisplayId", Int::class.javaPrimitiveType)
                .invoke(event, displayId)
        }.onFailure {
            Log.w(TAG, "setDisplayId 失败：事件会打到默认屏", it)
        }
    }

    /** 反射拿 InputManager 单例。@hide，但在 shell/root 身份下可用。 */
    private fun inputManager(): Any? = runCatching {
        Class.forName("android.hardware.input.InputManager")
            .getDeclaredMethod("getInstance")
            .apply { isAccessible = true }
            .invoke(null)
    }.onFailure { Log.e(TAG, "取 InputManager 失败", it) }
        .getOrNull()

    private fun injectInputEvent(event: MotionEvent, mode: Int): Boolean = runCatching {
        val manager = inputManager() ?: return false
        val method = manager.javaClass
            .getDeclaredMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
            .apply { isAccessible = true }
        method.invoke(manager, event, mode) as? Boolean ?: false
    }.getOrElse {
        Log.e(TAG, "injectInputEvent 调用失败", it)
        false
    }
}
