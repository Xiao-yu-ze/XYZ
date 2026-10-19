package com.pvz2.auto.shizuku

import android.content.Context
import android.util.Log
import android.view.Display
import android.view.Surface

/**
 * 建虚拟屏的唯一入口。
 *
 * 抽成单独一个对象，是为了让**两条调用路径**共用同一份逻辑：
 *  - 正常运行：`Pvz2UserService`（Shizuku 拉起的特权进程）里调；
 *  - 排查故障：`VdProbeMain`（`adb shell` 直接跑 app_process）里调。
 *
 * 第二条第存在很实际的意义：当 Shizuku 那边的失败原因不明时，可以绕开 Shizuku
 * 单独验「系统到底允许不允许建这块屏」，把问题范围从三件事缩小到一件。
 *
 * ## 为什么全程反射
 *
 * `DisplayManager.createVirtualDisplay` 是 @hide。在 app_process 里反射没有问题
 * （隐藏 API 限制针对普通应用进程），所以我们不去依赖任何编译期符号。
 */
internal object VirtualDisplayCreator {

    /**
     * 建成功的结果。
     *
     * 注意这里**两个对象都要留着**，它们不是一回事：
     *  - [virtualDisplay] 是 @hide 的 `android.hardware.display.VirtualDisplay`，
     *    释放要靠它（`VirtualDisplay.release()`）；
     *  - [display] 是公开的 `android.view.Display`，读尺寸/displayId 靠它。
     *
     * 曾经只存了前者却按 `android.view.Display` 转型，结果
     * `ClassCastException: VirtualDisplay cannot be cast to Display` —— 屏建出来了，
     * 却在取 displayId 时炸掉，日志上还看不到 displayId。
     */
    class Created(
        val virtualDisplay: Any,
        val display: Display,
        val displayId: Int
    )

    /**
     * @param callerContext 必须是一个 `getPackageName()` 返回「属于本进程 uid 的包名」的
     *   Context。系统服务会校验 `packageName must match the calling uid`，给错了直接
     *   SecurityException（这一条踩过两次，见 Pvz2UserService 的注释）。
     * @return 建成的屏
     * @throws Throwable 反射链上任何一环失败都原样抛出，调用方负责把原因打清楚
     */
    /**
     * @param callerContext 必须是一个 `getPackageName()` 返回「属于本进程 uid 的包名」的
     *   Context。系统服务会校验 `packageName must match the calling uid`，给错了直接
     *   SecurityException（这一条踩过两次，见 Pvz2UserService 的注释）。
     * @return 建成的屏
     * @throws Throwable 反射链上任何一环失败都原样抛出，调用方负责把原因打清楚
     */
    fun create(
        callerContext: Context,
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
        flags: Int
    ): Created {
        val displayManagerClass = Class.forName("android.hardware.display.DisplayManager")
        // ★ 关键：用 FakeContext 造一个**新的 DisplayManager 实例**，再调它的**实例方法**。
        //
        // 这是 scrcpy / MaaFwApp 的做法，也是我们之前一直没走对的地方。
        // FakeContext 把 checkCallingPermission() 重写成恒返回 GRANTED，
        // 于是 createVirtualDisplay 里那几道 canProjectVideo() / TRUSTED 检查才过得去。
        // 用系统 Context 或自己的 ContextWrapper 都会在这里被拒。
        val displayManager = displayManagerClass
            .getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(FakeContext.get())

        val createMethod = displayManagerClass
            .getDeclaredMethod(
                "createVirtualDisplay",
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Surface::class.java,
                Int::class.javaPrimitiveType
            )
            .apply { isAccessible = true }

        // ★ flags 用**候选列表逐个降级试**，而不是只赌一种。
        //   做法照抄 ShadowAuto 的 VirtualDisplaySession：从"最全"试到"最简"，
        //   哪个能成用哪个。不同 ROM 对 OWEN_FOCUS / TRUSTED 的支持差异很大，
        //   只试一种的话，一旦那位不被认就直接整条路失败。
        val candidates = flagCandidates(flags)
        var created: Any? = null
        var lastError: Throwable? = null
        for ((index, candidate) in candidates.withIndex()) {
            try {
                created = createMethod.invoke(displayManager, name, width, height, dpi, surface, candidate)
                if (created != null) {
                    Log.i(
                        TAG,
                        "createVirtualDisplay 成功（候选 ${index + 1}/${candidates.size}）：" +
                            VirtualDisplayFlags.describe(candidate)
                    )
                    break
                }
            } catch (t: Throwable) {
                lastError = t
                // InvocationTargetException 的 message 恒为 null，真正的系统原话在 cause 里。
                // 不打出来就只能靠猜，这个坑我已经踩过很多次。
                val root = generateSequence(t) { it.cause }.last()
                Log.w(
                    TAG,
                    "候选 ${index + 1}/${candidates.size} 失败：${root.javaClass.name}: ${root.message}"
                )
            }
        }
        if (created == null) {
            error("createVirtualDisplay 所有 flags 候选都失败（多半是权限被拒）：$lastError")
        }

        // 返回类型是 @hide 的 VirtualDisplay，所以按 Any 接住，再反射取它内部的 Display。
        val display = created.javaClass
            .getMethod("getDisplay")
            .apply { isAccessible = true }
            .invoke(created) as? Display
            ?: error("VirtualDisplay.getDisplay() 返回 null")

        val displayId = display.displayId
        if (displayId <= 0) {
            runCatching { created.javaClass.getMethod("release").invoke(created) }
            error("虚拟屏建出来了但 displayId 无效：$displayId")
        }

        // ★ 设 IME 策略 = LOCAL。
        //   不设的话这块屏"不能接收输入焦点"，表现为：任务 displayId 对了但窗口
        //   仍留在 display 0、注入被 InputDispatcher 以 "no touchable window" 丢弃。
        //   这一步是让窗口真正落到副屏上的关键（来自 ShadowAuto 的做法）。
        DisplayImePolicy.set(displayId, DisplayImePolicy.POLICY_LOCAL)

        return Created(created, display, displayId)
    }

    /**
     * 生成 flags 候选列表，从"最全"到"最简"。
     *
     * 降级逻辑：先试额外位（OWN_FOCUS / TRUSTED），再逐个去掉。
     * 这样老的 ROM（比如 Android 12）会在第一位或第二位就成功，
     * 新的 ROM 则能用上全部特性。
     */
    private fun flagCandidates(baseFlags: Int): List<Int> {
        val ownContent = VirtualDisplayFlags.OWN_CONTENT_ONLY
        val ownFocus = VirtualDisplayFlags.OWN_FOCUS
        val trusted = VirtualDisplayFlags.TRUSTED
        // 把 OWN_CONTENT_ONLY 摘掉：实测它会让系统抹掉 FLAG_PUBLIC，
        // 于是非 owner uid（游戏）访问不到这块屏 —— 窗口挂不上、
        // IME 报 untrusted、注入被丢，全是同一个根因。
        val withoutOwnContent = baseFlags and ownContent.inv()
        return listOf(
            withoutOwnContent or trusted or ownFocus,
            withoutOwnContent or ownFocus,
            baseFlags or trusted or ownFocus,
            baseFlags,
        ).distinct()
    }

    /** 释放。失败只记日志，不抛 —— 清理路径上抛异常会盖住真正的错。 */
    fun release(created: Any?) {
        if (created == null) return
        runCatching { created.javaClass.getMethod("release").invoke(created) }
    }

    private const val TAG = "VirtualDisplayCreator"
}