package com.pvz2.auto.shizuku

import android.util.Log
import android.view.MotionEvent

/**
 * 多指并发注入。
 *
 * ## 为什么不能复用现成的 `input motionevent`
 *
 * 那条命令一个事件只能带**一个**指针。多指手势必须一个 `MotionEvent` 里
 * 同时带 N 个 PointerCoords —— 逐个发等于"先滑一根再滑另一根"，
 * 那不是多指，是两个单指动作。
 *
 * ## 为什么不用 `InputEvent.setDisplayId`
 *
 * 那个方法在 Android 16 被移除了（真机报 `NoSuchMethodException`）。
 * 但 `MotionEvent.obtain(...)` 有一个**直接接收 displayId 参数**的重载，
 * 从 API 29 起就有 —— 走它就绕开了 setDisplayId。
 *
 * ## 为什么列多条注入路径
 *
 * 历史上 `InputManager.getInstance().injectInputEvent` 在这台机器上
 * **连主屏都被拒**（见 Pvz2UserService.tapOnDisplay 的注释）。那个现象
 * 强烈提示不是权限问题，而是 Android 13 起 `InputManager` 被
 * `InputManagerGlobal` 取代、旧反射拿到 null。
 *
 * 所以这里按顺序试多条路径，**每条都记日志**。哪条成了由真机决定，
 * 不靠推测 —— 一次失败就换下一条，全失败才返回 false 并说明原因。
 */
internal object MultiTouchInjector {

    private const val TAG = "MultiTouch"

    /**
     * 注入完成即返回，不等待事件被消费。
     *
     * 用于 MOVE 帧：一串 MOVE 如果每个都等结果，注入耗时会叠加，
     * 手势会变涩。而 MOVE 丢了不影响"手势有没有被接受"，
     * 那个由第一帧的 DOWN 判定。
     */
    private const val MODE_ASYNC = 0

    /**
     * 同步等结果，**被拒绝会抛异常**。
     *
     * 只用于第一帧 DOWN —— 这是整条路径能不能用的判据。
     *
     * 真机实测（`_probe/MTProbe`，app_process 以 shell 身份跑）：
     *
     * ```
     * InputManagerGlobal.injectInputEvent(InputEvent,int) mode=RESULT  成功
     * InputManager.injectInputEvent(...)                    InvocationTargetException
     * ```
     *
     * 所以 Android 16 上可用的是 `InputManagerGlobal`；历史上
     * 「injectInputEvent 连主屏都被拒」是因为用的是已被取代的 `InputManager`。
     */
    private const val MODE_RESULT = 2

    /** 插值步长。16ms ≈ 60Hz，比 30fps 的采集更细，不至于让滑动发涩。 */
    private const val STEP_MS = 16L

    /** 步数上限，防止 duration 特别大时生成几千个事件。 */
    private const val MAX_STEPS = 60

    /** 上一次成功的路径下标。成功后优先走它，省掉反复试错。 */
    private var workingStrategy = -1

    /**
     * 注入一次多指滑动。
     *
     * @return null 表示成功；否则返回失败原因（给上层记日志）
     */
    fun swipe(
        displayId: Int,
        startXs: FloatArray,
        startYs: FloatArray,
        endXs: FloatArray,
        endYs: FloatArray,
        durationMs: Int
    ): String? {
        val n = minOf(startXs.size, startYs.size, endXs.size, endYs.size)
        if (n <= 0) return "没有手指"

        val steps = ((durationMs.toLong() / STEP_MS).toInt()).coerceIn(1, MAX_STEPS)
        val downTime = android.os.SystemClock.uptimeMillis()

        // 先构造整串事件，再交给某条注入路径批量发。
        // 一起构造的好处：时间戳连续、downTime 一致，系统不会把后半个手势
        // 当成新手势。
        val frames = ArrayList<MotionEvent>(steps + n * 2 + 2)
        try {
            buildFrames(frames, displayId, downTime, startXs, startYs, endXs, endYs, n, steps)

            val err = injectAll(frames)
            return err
        } finally {
            frames.forEach { runCatching { it.recycle() } }
        }
    }

    // ------------------------------------------------------------ 事件构造

    private fun buildFrames(
        out: MutableList<MotionEvent>,
        displayId: Int,
        downTime: Long,
        startXs: FloatArray,
        startYs: FloatArray,
        endXs: FloatArray,
        endYs: FloatArray,
        n: Int,
        steps: Int
    ) {
        val now = downTime

        // 当前每根手指的坐标，随插值更新
        val xs = FloatArray(n) { startXs[it] }
        val ys = FloatArray(n) { startYs[it] }

        // ---- 第一根手指按下
        out.add(obtain(displayId, downTime, now, MotionEvent.ACTION_DOWN, xs, ys, 1))

        // ---- 其余手指依次按下（ACTION_POINTER_DOWN）
        for (i in 1 until n) {
            out.add(
                obtain(
                    displayId, downTime, now,
                    MotionEvent.ACTION_POINTER_DOWN or
                        (i shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    xs, ys, i + 1
                )
            )
        }

        // ---- 插值移动：所有手指一起走
        for (s in 1..steps) {
            val t = s.toFloat() / steps
            for (i in 0 until n) {
                xs[i] = startXs[i] + (endXs[i] - startXs[i]) * t
                ys[i] = startYs[i] + (endYs[i] - startYs[i]) * t
            }
            out.add(
                obtain(
                    displayId, downTime, now + s * STEP_MS,
                    MotionEvent.ACTION_MOVE, xs, ys, n
                )
            )
        }

        // ---- 手指依次抬起（倒序，和按下相反）
        for (i in n - 1 downTo 1) {
            out.add(
                obtain(
                    displayId, downTime, now + steps * STEP_MS,
                    MotionEvent.ACTION_POINTER_UP or
                        (i shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    xs, ys, i + 1
                )
            )
        }
        out.add(
            obtain(
                displayId, downTime, now + steps * STEP_MS,
                MotionEvent.ACTION_UP, xs, ys, 1
            )
        )
    }

    /**
     * 造一个带 [pointers] 个指针的 MotionEvent。
     *
     * ## 为什么整段用反射
     *
     * 需要的是 `obtain(downTime, eventTime, action, pointerCount,
     * pointerProperties, pointerCoords, metaState, buttonState, xPrecision,
     * yPrecision, deviceId, edgeFlags, source, displayId, flags)` ——
     * 它是 `@hide`，编译期不可见。
     *
     * 但**必须走这个重载**：它直接吃 displayId，绕开了 Android 16 已移除的
     * `InputEvent.setDisplayId`。公开的那个 14 参重载没有 displayId，
     * 造出来的事件进不了虚拟屏。
     *
     * 兜底：找不到 15 参重载时，先用公开重载造，再反射写 `mDisplayId` 字段。
     */
    private fun obtain(
        displayId: Int,
        downTime: Long,
        eventTime: Long,
        action: Int,
        xs: FloatArray,
        ys: FloatArray,
        pointers: Int
    ): MotionEvent {
        val props = Array(pointers) { i ->
            MotionEvent.PointerProperties().apply {
                id = i
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(pointers) { i ->
            MotionEvent.PointerCoords().apply {
                x = xs[i]
                y = ys[i]
                pressure = 1f
                size = 1f
            }
        }

        // 15 参重载：... deviceId, edgeFlags, source, displayId, flags
        val withDisplay = obtainWithDisplayMethod
        if (withDisplay != null) {
            return withDisplay.invoke(
                null,
                downTime, eventTime, action,
                pointers, props, coords,
                0,                      // metaState
                0,                      // buttonState
                1f, 1f,                 // xPrecision / yPrecision
                0,                      // deviceId
                0,                      // edgeFlags
                android.view.InputDevice.SOURCE_TOUCHSCREEN,
                displayId,
                0                       // flags
            ) as MotionEvent
        }

        // 兜底：公开重载 + 反射写 displayId
        val ev = MotionEvent.obtain(
            downTime, eventTime, action,
            pointers, props, coords,
            0, 0, 1f, 1f, 0, 0,
            android.view.InputDevice.SOURCE_TOUCHSCREEN,
            0
        )
        runCatching {
            val f = MotionEvent::class.java.getDeclaredField("mDisplayId")
            f.isAccessible = true
            f.setInt(ev, displayId)
        }.onFailure { Log.w(TAG, "写 mDisplayId 失败：${it.message}") }
        return ev
    }

    /**
     * 找那个带 displayId 的 `obtain` 重载。
     *
     * 判据：参数里有 2 个 `int[]`? 不 —— 用最稳的办法：**参数个数 15**
     * 且第 14 个（下标 13）是 int。其他重载都不是这个组合。
     */
    private val obtainWithDisplayMethod: java.lang.reflect.Method? by lazy {
        runCatching {
            MotionEvent::class.java.methods.firstOrNull { m ->
                m.name == "obtain" &&
                    m.parameterTypes.size == 15 &&
                    m.parameterTypes[13] == Int::class.javaPrimitiveType &&
                    m.parameterTypes[0] == Long::class.javaPrimitiveType
            }
        }.getOrNull().also {
            if (it == null) {
                Log.w(TAG, "找不到带 displayId 的 obtain 重载，改用反射写字段")
            } else {
                Log.i(TAG, "obtain 重载已找到：${it.parameterTypes.joinToString(",") { p -> p.simpleName }}")
            }
        }
    }

    // ------------------------------------------------------------ 注入路径

    /**
     * 把整串事件按顺序注入。
     *
     * 已经知道哪条路能用就直接用它；否则逐条试并记住。
     */
    private fun injectAll(frames: List<MotionEvent>): String? {
        if (workingStrategy >= 0) {
            val e = runStrategy(workingStrategy, frames)
            if (e == null) return null
            // 之前能用现在不能用了（换了系统/权限变了），重新探测
            Log.w(TAG, "原路径 $workingStrategy 失效：$e，重新探测")
            workingStrategy = -1
        }

        val errors = ArrayList<String>()
        for (s in STRATEGIES.indices) {
            val e = runStrategy(s, frames)
            if (e == null) {
                workingStrategy = s
                Log.i(TAG, "多指注入可用路径：${STRATEGIES[s].name}")
                return null
            }
            errors.add("${STRATEGIES[s].name}: $e")
        }
        Log.e(TAG, "多指注入全部路径失败：${errors.joinToString(" | ")}")
        return errors.joinToString(" | ")
    }

    private fun runStrategy(index: Int, frames: List<MotionEvent>): String? =
        try {
            STRATEGIES[index].run(frames)
        } catch (t: Throwable) {
            "${t.javaClass.simpleName}: ${t.message}"
        }

    /** 一条注入路径。 */
    private class Strategy(val name: String, val run: (List<MotionEvent>) -> String?)

    /**
     * 候选路径，按真机实测的可用性排序。
     *
     * `InputManagerGlobal` 是 Android 13 起取代 `InputManager` 的入口。
     * 探针（`_probe/MTProbe`）在这台 Android 16 上的结果：
     *
     * ```
     * InputManagerGlobal.getInstance()                      ok
     *   injectInputEvent(InputEvent,int)      RESULT 模式   成功
     * InputManager.getInstance()                            InvocationTargetException
     * ```
     *
     * 所以先试 Global，再退回旧的 —— 旧系统上没有 Global 类，
     * 那时 `InputManager` 才是对的。
     */
    private val STRATEGIES: List<Strategy> = listOf(
        Strategy("InputManagerGlobal") { frames ->
            reflect("android.hardware.input.InputManagerGlobal", frames)
        },
        Strategy("InputManager") { frames ->
            reflect("android.hardware.input.InputManager", frames)
        }
    )

    private fun reflect(className: String, frames: List<MotionEvent>): String? {
        val cls = try {
            Class.forName(className)
        } catch (t: Throwable) {
            return "没有这个类"
        }
        val inst = cls.getMethod("getInstance").invoke(null)
            ?: return "getInstance() 返回 null"
        return callInject(cls, inst, frames)
    }

    /**
     * 在 [cls] 上找 `injectInputEvent` 并调用。
     *
     * ## 第一帧用 RESULT，其余用 ASYNC
     *
     * RESULT 模式下被拒绝会**抛异常**，所以拿第一帧 DOWN 当探针：
     * 拒了立刻知道，可以换下一条路径。
     *
     * 后续帧用 ASYNC —— 每帧都等结果的话注入耗时会叠加，
     * 手势会变涩。而 MOVE 丢不丢不影响"手势有没有被接受"这件事，
     * 那已经由 DOWN 判定过了。
     *
     * ## 为什么逐个签名试
     *
     * 不同版本的重载不一样（2 参 / 3 参 / 4 参）。写死一个签名，
     * 换版本就整条路失效 —— 这也是历史上"突然点不动"的来源之一。
     */
    private fun callInject(cls: Class<*>, inst: Any, frames: List<MotionEvent>): String? {
        val candidates = cls.methods.filter {
            it.name == "injectInputEvent" &&
                it.parameterTypes.firstOrNull()?.let { p ->
                    p.isAssignableFrom(MotionEvent::class.java) ||
                        p == android.view.InputEvent::class.java
                } == true
        }
        if (candidates.isEmpty()) return "类 ${cls.simpleName} 上没有 injectInputEvent"

        val failures = ArrayList<String>()
        for (m in candidates) {
            val sig = "${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }})"

            // ---- 先用第一帧 + RESULT 探一下
            val probeErr = try {
                m.invoke(inst, *argsFor(m.parameterTypes, frames.first(), MODE_RESULT))
                null
            } catch (t: Throwable) {
                val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
                "${cause.javaClass.simpleName}: ${cause.message}"
            }
            if (probeErr != null) {
                failures.add("$sig → $probeErr")
                continue
            }

            // ---- 剩下的一次性发完，异步
            for (i in 1 until frames.size) {
                runCatching {
                    m.invoke(inst, *argsFor(m.parameterTypes, frames[i], MODE_ASYNC))
                }.onFailure {
                    Log.w(TAG, "$sig 第 $i 帧失败（不影响手势整体）：${it.message}")
                }
            }
            Log.i(TAG, "注入成功：${cls.simpleName}.$sig")
            return null
        }
        return failures.joinToString(" ; ")
    }

    /** 按方法签名决定除事件外还要填什么参数。 */
    private fun argsFor(
        params: Array<Class<*>>,
        ev: MotionEvent,
        mode: Int
    ): Array<Any?> = Array(params.size) { i ->
        when (i) {
            0 -> ev
            // 第 2 个参数是注入模式
            1 -> mode
            // targetUid：-1 = 不限定
            2 -> -1
            // 其余（syncMode / displayId 等）：0 是安全默认
            else -> 0
        }
    }
}
