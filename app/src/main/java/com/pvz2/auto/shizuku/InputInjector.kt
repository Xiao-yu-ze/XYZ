package com.pvz2.auto.shizuku

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent

/**
 * 往指定 display 注入触摸。
 *
 * ## ⚠️ 这个类在 Android 16 上已经不能用于**指定副屏**
 *
 * 真机（SDK 36）实测：`InputEvent.setDisplayId(int)` **被移除了**。
 *
 * ```
 * java.lang.NoSuchMethodException: android.view.InputEvent.setDisplayId [int]
 * ```
 *
 * 于是每一个事件都在设置目标屏这一步抛异常，`injectInputEvent` 根本走不到。
 * 替代方案也都试过、都不成立：
 *
 * | 尝试 | 结果 |
 * |---|---|
 * | `InputEvent.setDisplayId(int)` | 方法不存在 |
 * | `MotionEvent.nativeSetDisplayId(long,int)` | 存在，但取不到 native 指针 |
 * | 反射字段 `mNativePtr` | 字段不存在 |
 *
 * **结论：SDK 36 上要往副屏注入，只能用 `input -d N motionevent`。**
 * 手势走的是那条路（见 `Pvz2UserService.GestureInjector`）。
 * 这个类的 `tap`/`swipe` 目前只对 display 0 有效（displayId 为 0 时不调
 * `setDisplayId`，整条链路才成立）。
 *
 * ## 这段历史值得留着，因为踩过两次
 *
 * 第一次是 `injectInputEvent` 连主屏都被拒，照 scrcpy 逐行比对后修好了三处（见下）。
 * 第二次是加手势时把触摸从 `input` 命令改到这条路上 —— 点击和拖动**同时失效**，
 * 而点击此前一直是好的，因为点击走的是 `input -d tap`，从来没经过这里。
 *
 * **教训：一条一直能用的路径，不要因为"新功能看起来更直接"就换掉它。**
 * 换之前先确认新路径在同一台设备上验证过。
 *
 * ## 这份实现是照抄 scrcpy 的，不是推出来的
 *
 * 对照来源（都已读过源码）：
 *  - `server/.../wrappers/InputManager.java`
 *  - `server/.../device/Device.java` 的 `injectEvent()`
 *  - `server/.../control/Controller.java` 的 `injectTouch()`
 *
 * ## 第一次为什么一直失败
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

    /** 缓存 MotionEvent 的 native 指针取值方法。 */
    private var nativePtrMethod: java.lang.reflect.Method? = null

    /**
     * 取 `android.hardware.input.InputManager`。
     *
     * ★ 必须走 `getSystemService(Context.INPUT_SERVICE)`。
     *   反射 `InputManager.getInstance()` 在 Android 12+ 上拿到的实例
     *   调用 `injectInputEvent` 恒返回 false —— 这正是之前"连主屏都注入不了"的原因。
     */
    internal fun inputManager(): Any? {
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
    internal fun buildEvent(
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
    internal fun setDisplayId(event: InputEvent, displayId: Int): Boolean = runCatching {
        // ★ Android 16（SDK 36）移除了 InputEvent.setDisplayId(int)。
        //
        //   真机日志：`NoSuchMethodException: android.view.InputEvent.setDisplayId [int]`
        //   —— 每个注入的事件都在这行失败，于是"点了没反应"。
        //
        //   现在只剩包内的 `MotionEvent.nativeSetDisplayId(long nativePtr, int)`。
        //   所以按版本分派：旧版用公有方法，新版走 native。
        if (Build.VERSION.SDK_INT >= 36 && event is MotionEvent) {
            nativeSetDisplayId(event, displayId)
        } else {
            val method = setDisplayIdMethod
                ?: InputEvent::class.java
                    .getMethod("setDisplayId", Int::class.javaPrimitiveType)
                    .also { setDisplayIdMethod = it }
            method.invoke(event, displayId)
        }
        true
    }.onFailure { Log.e(TAG, "setDisplayId 失败 display=$displayId", it) }
        .getOrDefault(false)

    /**
     * 走 `MotionEvent.nativeSetDisplayId(long, int)` 设置目标屏。
     *
     * 参数是 MotionEvent 的 native 指针，用 `nativeGetPtr` 那样的包内方法取 ——
     * 但那个方法本身也是隐藏的，所以先试 `getNativePtr`，再退回字段读取。
     */
    private fun nativeSetDisplayId(event: MotionEvent, displayId: Int) {
        val ptr = nativePtrOf(event)
        if (ptr == 0L) throw IllegalStateException("取不到 MotionEvent 的 native 指针")
        val m = MotionEvent::class.java.getDeclaredMethod(
            "nativeSetDisplayId", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType
        )
        m.isAccessible = true
        m.invoke(null, ptr, displayId)
    }

    /** 取 MotionEvent 的 native 指针。 */
    private fun nativePtrOf(event: MotionEvent): Long {
        val cached = nativePtrMethod
        if (cached != null) return cached.invoke(event) as Long
        // 先试包内方法（名字在各版本上是 nativeGetPtr / getNativePtr 之一）
        for (name in listOf("nativeGetPtr", "getNativePtr")) {
            val m = runCatching {
                MotionEvent::class.java.getDeclaredMethod(name).also { it.isAccessible = true }
            }.getOrNull()
            if (m != null) {
                nativePtrMethod = m
                return m.invoke(event) as Long
            }
        }
        // 退回读字段
        val f = MotionEvent::class.java.getDeclaredField("mNativePtr")
        f.isAccessible = true
        return f.getLong(event)
    }

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
        /*
         * ★ holdMs <= 0 = **极速模式**（对应「极速点击模式」）。
         *
         *   帮助文案：「开启后，会忽略默认的额外点击结束等待（5~30ms），
         *   但是会导致连续极速点击动作中少部分点击失效」。
         *   那"额外等待"就是下面 DOWN 的 MODE_WAIT_FOR_FINISH —— 等事件真的
         *   进管线再返回。极速模式下改成异步发，间隔压到 1ms，
         *   代价与协议一致：连点时可能丢一部分。
         */
        val fast = holdMs <= 0
        val hold = if (fast) 1L else holdMs.coerceIn(1, 2_000).toLong()
        val downTime = SystemClock.uptimeMillis()
        val fx = x.toFloat()
        val fy = y.toFloat()

        // DOWN 用 WAIT_FOR_FINISH：等它真的进管线，否则紧跟的 UP
        // 可能和它在同一个 vsync 里被合并掉。
        val downOk = injectEvent(
            buildEvent(downTime, downTime, MotionEvent.ACTION_DOWN, fx, fy),
            displayId,
            if (fast) MODE_ASYNC else MODE_WAIT_FOR_FINISH
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

        /*
         * ★ 中间移动必须**按真实时间**发，不能一口气发完。
         *
         *   原来只把 eventTime 摊在 duration 上，然后 MODE_ASYNC 连着送 ——
         *   系统收到的是"3 秒的位移记录、几毫秒送完"，游戏看来就是瞬移一下；
         *   调用方只好再额外 sleep(duration) 等它，表现成用户说的
         *   "一下就划完了，然后一直等了 3 秒"。
         *
         *   现在按 ~16ms 一步切成 steps 步，每步发完睡到该步的
         *   **真实时间点**，手指才是真的慢慢滑过去。
         */
        var allOk = true
        val steps = (duration / 16).coerceIn(MOVE_STEPS, 200)
        val stepSleep = duration.toLong() / steps
        for (i in 1..steps) {
            val fraction = i.toFloat() / steps
            val mx = (x1 + (x2 - x1) * fraction).toFloat()
            val my = (y1 + (y2 - y1) * fraction).toFloat()
            val offset = stepSleep * i
            val ok = injectEvent(
                buildEvent(downTime, downTime + offset, MotionEvent.ACTION_MOVE, mx, my),
                displayId,
                MODE_ASYNC
            )
            if (!ok) allOk = false
            // 真实节奏：这一步发完，等到它该到的时刻
            val now = SystemClock.uptimeMillis()
            val target = downTime + offset
            if (target > now) SystemClock.sleep(target - now)
        }

        val upOk = injectEvent(
            buildEvent(downTime, downTime + duration, MotionEvent.ACTION_UP, x2.toFloat(), y2.toFloat()),
            displayId,
            MODE_ASYNC
        )
        if (!upOk) allOk = false

        val spent = SystemClock.uptimeMillis() - downTime
        Log.i(TAG, "滑动实耗 ${spent}ms / 计划 ${duration}ms display=$displayId ($x1,$y1)->($x2,$y2) steps=$steps")
        if (!allOk) Log.w(TAG, "滑动有事件被拒 display=$displayId ($x1,$y1)->($x2,$y2)")
        return allOk
    }

    /** 开始一次由手指驱动的手势。 */
    fun beginGesture(displayId: Int, x: Float, y: Float): Gesture = Gesture(displayId, x, y)

    // ------------------------------------------------------------ 流式手势

    /**
     * 一次进行中的手势。**由用户的真实手指驱动**，不是预录的轨迹。
     *
     * ## 为什么需要它
     *
     * 之前只有 tap 和 swipe 两个一次性原语：手势的形状（起终点、步数）
     * 必须事先知道，于是一次完整的拖动只能在**抬手那一刻**才注入 ——
     * 用户看到的就是"手不动，松手后画面才滑一下"。
     *
     * 现在改成按下就注入 DOWN、手指每移动一次注入一个 MOVE、抬手注入 UP。
     * 游戏收到的是连续事件流，画面跟着手指实时动。
     *
     * ## 事件时间必须递增
     *
     * `eventTime` 用真实时刻。如果所有事件共用同一个时间戳，输入系统会认为
     * 它们同时发生 —— 滑动距离算出来是 0，或者速度无穷大导致惯性飞出去。
     */
    class Gesture(private val displayId: Int, x: Float, y: Float) {

        private val downTime = SystemClock.uptimeMillis()
        private val pointerProps = pointerProperties
        private val coords = pointerCoords

        /** 上一个已注入的坐标，用来跳过原地不动的 MOVE（省事件、也少一次 Binder）。 */
        private var lastX = x
        private var lastY = y

        private var finished = false

        init {
            inject(buildEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x, y))
        }

        /** 手指移动到 (x, y)。同一位置重复调用会被忽略。 */
        fun moveTo(x: Float, y: Float) {
            if (finished) return
            // 亚像素抖动也跳过：手指在屏幕上"按住"时总会抖 0.5px，
            // 每次都发一个 MOVE 会把 Binder 和输入队列占满。
            if (kotlin.math.abs(x - lastX) < MOVE_EPSILON && kotlin.math.abs(y - lastY) < MOVE_EPSILON) return
            lastX = x
            lastY = y
            inject(buildEvent(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x, y))
        }

        /** 抬手。必须调用，否则游戏里那个"按下"永远不释放。 */
        fun finish(x: Float, y: Float) {
            if (finished) return
            finished = true
            inject(buildEvent(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y))
        }

        /** 取消（例如手指划出画面）。用 ACTION_CANCEL 让游戏知道这不是一次正常点击。 */
        fun cancel() {
            if (finished) return
            finished = true
            inject(buildEvent(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, lastX, lastY))
        }

        private fun inject(event: MotionEvent) {
            try {
                injectEvent(event, displayId, MODE_ASYNC)
            } catch (t: Throwable) {
                Log.e(TAG, "手势事件注入失败 display=$displayId", t)
            } finally {
                event.recycle()
            }
        }
    }

    private const val MOVE_STEPS = 8

    /** 小于这个位移（虚拟屏像素）的 MOVE 不发。 */
    private const val MOVE_EPSILON = 0.8f
}
