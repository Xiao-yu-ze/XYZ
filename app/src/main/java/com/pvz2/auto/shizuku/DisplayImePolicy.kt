package com.pvz2.auto.shizuku

import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * 设置/读取某块 display 的 IME（输入法）策略。
 *
 * ## 为什么必须做这件事（这是让窗口真正落到副屏的关键一环）
 *
 * 做法参照 ShadowAuto 的 `WindowManagerBridge`。虚拟屏默认没有 IME 策略，
 * 于是 WMS 不把它当成一块"能接收输入的焦点屏" —— 表现就是我们之前反复遇到的：
 *
 *  - 任务 `displayId` 正确，但窗口 `mDisplayId` 仍是 0；
 *  - `InputDispatcher: no touchable window ... in display N`，注入全被丢。
 *
 * 把它设成 [POLICY_LOCAL]（输入法跟着这块屏走）之后，屏才是个完整的交互屏。
 *
 * ## 版本差异
 *
 * - **Android 12 (S)** 起：`setDisplayImePolicy(int displayId, int policy)`
 * - **Android 10/11**：方法叫 `setShouldShowIme(int displayId, boolean show)`
 *
 * 两套都试，别只认一套。
 */
internal object DisplayImePolicy {

    private const val TAG = "DisplayImePolicy"

    /** 输入法显示在这块屏上（我们要的）。 */
    const val POLICY_LOCAL = 0

    /** 输入法退回默认屏。 */
    const val POLICY_FALLBACK_DISPLAY = 1

    /** 这块屏上不显示输入法。 */
    const val POLICY_HIDE = 2

    /** 读失败。 */
    const val UNKNOWN = -1

    /**
     * 把 [displayId] 的 IME 策略设成 [policy]。
     *
     * @return true 表示调用被接受
     */
    fun set(displayId: Int, policy: Int): Boolean = runCatching {
        val manager = windowManager() ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // ★ 必须 isAccessible = true。
            //
            //   manager 是 `IWindowManager$Stub$Proxy`，那个类是 **private static**
            //   的（@hide）。方法本身是 public，但声明类不可访问 ——
            //   `Method.invoke` 会抛 IllegalAccessException，然后被
            //   runCatching 吞成一句"不致命"的警告。
            //
            //   结果是：IME 策略**从来没设上过**，而日志只说"不致命"。
            //   这块屏因此拿不到本地 IME / 焦点语义，很可能就是
            //   InputDispatcher 报 "no touchable window ... in display N" 的真因。
            manager.javaClass
                .getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(manager, displayId, policy)
        } else {
            // Android 10/11 只有布尔版；HIDE 概念在那时不存在，直接跳过。
            if (policy == POLICY_HIDE) return true
            manager.javaClass
                .getMethod("setShouldShowIme", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(manager, displayId, policy == POLICY_LOCAL)
        }
        Log.i(TAG, "display $displayId 的 IME 策略已设为 $policy")
        true
    }.onFailure {
        // 设不上不算致命：屏还是能建、能出帧，只是交互可能有问题。所以只警告。
        Log.w(TAG, "设置 display $displayId 的 IME 策略失败（不致命）", it)
    }.getOrDefault(false)

    /**
     * 读 [displayId] 当前的 IME 策略；失败返回 [UNKNOWN]。
     *
     * 释放虚拟屏前要把策略还原，否则会在系统里留下改过的状态。
     */
    fun get(displayId: Int): Int = runCatching {
        val manager = windowManager() ?: return UNKNOWN
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager.javaClass
                .getMethod("getDisplayImePolicy", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(manager, displayId) as Int
        } else {
            val show = manager.javaClass
                .getMethod("shouldShowIme", Int::class.javaPrimitiveType)
                .invoke(manager, displayId) as Boolean
            if (show) POLICY_LOCAL else POLICY_FALLBACK_DISPLAY
        }
    }.getOrDefault(UNKNOWN)

    /** `ServiceManager.getService("window")` + `IWindowManager$Stub.asInterface`。 */
    private fun windowManager(): Any? = runCatching {
        val binder = Class.forName("android.os.ServiceManager")
            .getDeclaredMethod("getService", String::class.java)
            .invoke(null, "window") as IBinder
        Class.forName("android.view.IWindowManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
    }.onFailure { Log.e(TAG, "拿不到 IWindowManager", it) }
        .getOrNull()
}
