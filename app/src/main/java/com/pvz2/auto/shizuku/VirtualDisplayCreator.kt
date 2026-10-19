package com.pvz2.auto.shizuku

import android.content.Context
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
        val displayManager = displayManagerClass
            .getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(callerContext)

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

        // 返回类型是 @hide 的 VirtualDisplay，所以按 Any 接住，再反射取它内部的 Display。
        val created = createMethod.invoke(displayManager, name, width, height, dpi, surface, flags)
            ?: error("createVirtualDisplay 返回 null（多半是 flags 或权限被拒）")

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
        return Created(created, display, displayId)
    }

    /** 释放。失败只记日志，不抛 —— 清理路径上抛异常会盖住真正的错。 */
    fun release(created: Any?) {
        if (created == null) return
        runCatching { created.javaClass.getMethod("release").invoke(created) }
    }
}
