package com.pvz2.auto.shizuku

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent

/**
 * 往指定 display 注入触摸。
 *
 * ## 这份实现是照抄 scrcpy 的，不是推出来的
 *
 * 对照来源（都已读过源码）：
 *  - `server/.../wrappers/InputManager.java`
 *  - `server/.../device/Device.java` 的 `injectEvent()`
 *  - `server/.../control/Controller.java` 的 `injectTouch()`
 *
 * ## 之前为什么一直失败
 *
 * 真机四路对比的结果是：`input -d 65 tap` 能通，而自己的 `injectInputEvent`
 * **连 display 0 都被拒**（所以不是权限、也不是副屏路由）。和 scrcpy 逐行比对后，
 * 差在三处 —— 每一处都足以让 `injectInputEvent` 返回 false：
 *
 *  1. **取实例的方式错了**
 *     scrcpy: `context.getSystemService(Context.INPUT_SERVICE)`
 *     我之前: 反射 `InputManager.getInstance()`
 *     `getInstance()` 在新版本上不是可用的入口，反射拿到的对象调用不报错但恒返回 false。
 *
 *  2. **MotionEvent 的 source 必须在构造时给**
 *     scrcpy 用 14 参数的 `obtain(..., source, flags)`；
 *     我之前用 6 参数构造完再 `event.source = ...`。
 *     事后赋值不会重建内部的 pointer 数据。
 *
 *  3. **PointerProperties.toolType 必须显式设为 FINGER**
 *     scrcpy 的 `initPointers()` 里写了 `props.toolType = TOOL_TYPE_FINGER`。
 *     我之前完全没设，默认是 `TOOL_TYPE_UNKNOWN` ——
 *     InputDispatcher 无法判定指针类型，直接丢弃。
 *
 * ## 和 shell 路径的关系
 *
 * `input -d <id> tap` 也能通，但它每次 fork 一个进程（5~15ms）。
 * 自动化的动作要压到毫秒级、间隔要能到 0，fork 是明显瓶颈。
 * 所以主路径用这份直接注入，shell 只作为诊断对照保留在测试按钮里。
 */
internal object InputInjector {

    private const val TAG = "InputInjector"

    /** `InputManager.INJECT_INPUT_EVENT_MODE_ASYNC` */
    private const val MODE_ASYNC = 0

    /** `InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH` */
    private const val MODE_WAIT_FOR_FINISH = 2

    /** scrcpy 用的 deviceId 常量。0 = 虚拟设备，注入场景下的标准取值。 */
    private const val DEFAULT_DEVICE_ID = 0

    @Volatile
    private var manager: Any? = null

    @Volatile
    private var injectMethod: java.lang.reflect.Method? = null

    @Volatile
    private var setDisplayIdMethod: java.lang.reflect.Method? = null

    /**
     * 取 `android.hardware.input.InputManager`。
     *
     * ★ 必须走 `getSystemService(Context.INPUT_SERVICE)`。
     *   反射 `InputManager.getInstance()` 在 Android 12+ 上拿到的实例
     *   调用 `injectInputEvent` 恒返回 false —— 这正是之前"连主屏都注入不了"的原因。
     */
    private fun inputManager(): Any? {
        manager?.let { return it }
        return synchronized(this) {
            manager ?: runCatching {
                FakeContext.get().getSystemService(Context.INPUT_SERVICE)
            }.onFailure { Log.e(TAG, "取 InputManager 失败", it) }
                .getOrNull()?.also { manager = it }
        }
    }

    /** 复用 scrcpy 的 1 指布局：toolType 必须显式设为 FINGER。 */
    private val pointerProperties = Array(1) {
        MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
    }

    private val pointerCoords = Array(1) {
        MotionEvent.PointerCoords().apply {
            orientation = 0f
            size = 0f
            pressure = 1f
        }
    }

    /**
     * 构造一个触摸事件。
     *
     * 用 14 参数的重载，**source 在构造时传入** —— 这是 scrcpy 的做法。
     * 6 参数版本 + 事后 `event.source = ...` 不够：内部 pointer 数据
     * 不会跟着重建。
     */
    private fun buildEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float
    ): MotionEvent {
        pointerCoords[0].x = x
        pointerCoords[0].y = y
        return MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            1,                       // pointerCount
            pointerProperties,
            pointerCoords,
            0,                       // metaState
            0,                       // buttonState
            1f,                      // xPrecision
            1f,                      // yPrecision
            DEFAULT_DEVICE_ID,
            0,                       // edgeFlags
            InputDevice.SOURCE_TOUCHSCREEN,
            0                        // flags
        )
    }

    /** `InputEvent.setDisplayId(int)`。scrcpy 在 `InputEvent.class` 上取。 */
    private fun setDisplayId(event: InputEvent, displayId: Int): Boolean = runCatching {
        val method = setDisplayIdMethod
            ?: InputEvent::class.java
                .getMethod("setDisplayId", Int::class.javaPrimitiveType)
                .also { setDisplayIdMethod = it }
        method.invoke(event, displayId)
        true
    }.onFailure { Log.e(TAG, "setDisplayId 失败 display=$displayId", it) }
        .getOrDefault(false)

    /**
     * 注入一个事件。
     *
     * 顺序照 scrcpy 的 `Device.injectEvent()`：
     * **先 setDisplayId（非 0 时），再 injectInputEvent**。
     */
    private fun injectEvent(event: MotionEvent, displayId: Int, mode: Int): Boolean {
        try {
            // scrcpy 只在 displayId != 0 时才设；0 是默认屏，不需要设。
            if (displayId != 0 && !setDisplayId(event, displayId)) {
                return false
            }
            val mgr = inputManager() ?: return false
            val method = injectMethod
                ?: mgr.javaClass
                    .getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
                    .also { injectMethod = it }
            return method.invoke(mgr, event, mode) as? Boolean ?: false
        } catch (t: Throwable) {
            // 反射的目标方法里抛出的异常会被包一层 InvocationTargetException，
            // 真正的 SecurityException 在内层 —— 把 cause 打出来，否则只看得到外壳。
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            Log.e(TAG, "注入事件失败 display=$displayId", cause)
            return false
        } finally {
            event.recycle()
        }
    }

    /**
     * 在 [displayId] 上点一下。
     *
     * @param holdMs 按住时长。只体现在两个事件的 eventTime 差值上，不真睡 ——
     *   真睡会占住 Binder 线程，而 InputDispatcher 判速度用的是时间戳。
     */
    fun tap(displayId: Int, x: Int, y: Int, holdMs: Int): Boolean {
        val hold = holdMs.coerceIn(1, 2_000).toLong()
        val downTime = SystemClock.uptimeMillis()
        val fx = x.toFloat()
        val fy = y.toFloat()

        // DOWN 用 WAIT_FOR_FINISH：等它真的进管线，否则紧跟的 UP
        // 可能和它在同一个 vsync 里被合并掉。
        val downOk = injectEvent(
            buildEvent(downTime, downTime, MotionEvent.ACTION_DOWN, fx, fy),
            displayId,
            MODE_WAIT_FOR_FINISH
        )
        if (!downOk) {
            Log.w(TAG, "点击被拒（DOWN）display=$displayId ($x,$y)")
            return false
        }

        val upOk = injectEvent(
            buildEvent(downTime, downTime + hold, MotionEvent.ACTION_UP, fx, fy),
            displayId,
            MODE_ASYNC
        )
        if (!upOk) Log.w(TAG, "点击被拒（UP）display=$displayId ($x,$y)")
        return upOk
    }

    /**
     * 在 [displayId] 上从 (x1,y1) 滑到 (x2,y2)，耗时 [durationMs]。
     *
     * 中间插入若干 MOVE：太少会被识别成"跳变"而不是拖动，
     * 太多白占注入带宽。8 步在 300ms 的手势上是 37ms 一步。
     */
    fun swipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean {
        val duration = durationMs.coerceIn(1, 5_000)
        val downTime = SystemClock.uptimeMillis()

        val downOk = injectEvent(
            buildEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x1.toFloat(), y1.toFloat()),
            displayId,
            MODE_WAIT_FOR_FINISH
        )
        if (!downOk) {
            Log.w(TAG, "滑动起点被拒 display=$displayId ($x1,$y1)")
            return false
        }

        var allOk = true
        for (i in 1..MOVE_STEPS) {
            val fraction = i.toFloat() / MOVE_STEPS
            val mx = (x1 + (x2 - x1) * fraction).toFloat()
            val my = (y1 + (y2 - y1) * fraction).toFloat()
            // 时间戳按比例推进；最后一步留给 UP。
            val offset = duration.toLong() * i / (MOVE_STEPS + 1)
            val ok = injectEvent(
                buildEvent(downTime, downTime + offset, MotionEvent.ACTION_MOVE, mx, my),
                displayId,
                MODE_ASYNC
            )
            if (!ok) allOk = false
        }

        val upOk = injectEvent(
            buildEvent(downTime, downTime + duration, MotionEvent.ACTION_UP, x2.toFloat(), y2.toFloat()),
            displayId,
            MODE_ASYNC
        )
        if (!upOk) allOk = false

        if (!allOk) Log.w(TAG, "滑动有事件被拒 display=$displayId ($x1,$y1)->($x2,$y2)")
        return allOk
    }

    private const val MOVE_STEPS = 8
}
