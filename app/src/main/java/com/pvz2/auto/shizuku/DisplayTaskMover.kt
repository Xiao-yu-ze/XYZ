package com.pvz2.auto.shizuku

import android.app.ActivityManager
import android.content.ComponentName
import android.os.IBinder
import android.util.Log

/**
 * 把某个应用的 task 搬到指定 display。
 *
 * ## 实现参照 MaaFwApp（`remote/internal/ActivityUtils.kt`）
 *
 * 这不是我拍脑袋写的，是照着一份在真机上跑通的实现改的。三处关键差别，
 * 恰好就是我先前自己写时踩的三个坑：
 *
 * ### ① 取 IActivityTaskManager 的方式
 *
 * 我原来用 `ActivityTaskManager.getService()` 反射 —— 拿不到（`getTasks` 之后
 * 直接返回空，而且一条日志都没有，行为很怪）。MaaFwApp 的做法是走 ServiceManager：
 *
 * ```
 * ServiceManager.getService("activity_task")  ->  IBinder
 * IActivityTaskManager$Stub.asInterface(binder)  ->  IActivityTaskManager
 * ```
 *
 * 这样拿到的就是活的 Binder 代理，`getTasks` / `moveRootTaskToDisplay` 都能调。
 *
 * ### ② 方法名随版本不同
 *
 * - API 31+ 是 `moveRootTaskToDisplay(taskId, displayId)`
 * - API 29/30 是 `moveStackToDisplay(taskId, displayId)`
 *
 * 两个都试，别只认一个。
 *
 * ### ③ task 的 displayId 是隐藏字段
 *
 * `ActivityManager.RunningTaskInfo.displayId` 是 @hide，而且**不在当前类上**，
 * 要沿着父类链 `getDeclaredField("displayId")` 找。拿来校验「到底搬成功了没有」——
 * 只发个 move 命令就宣布成功，是这里最容易骗自己的地方。
 */
internal object DisplayTaskMover {

    private const val TAG = "DisplayTaskMover"

    /** 一次查多少条最近任务。 */
    private const val MAX_TASKS = 100

    /** 包里没有任何运行中任务时的返回值。 */
    /** 包里没有任何运行中任务时的返回值。 */
    const val NO_TASK = -2

    /** getTasks 的 displayId 参数：-1 = 不按屏过滤。 */
    private const val DEFAULT_DISPLAY = -1

    /** 拿不到（API 不支持 / 反射失败 / 异常）。 */
    const val UNKNOWN = -1

    /**
     * 把 [packageName] 的**最近**任务搬到 [displayId]。
     *
     * 取最近任务而不是任意任务：漂移时新任务落在主屏，副屏上可能还留着旧任务，
     * 随便挑一个会挑错。
     *
     * @return 搬动成功的 taskId；失败返回 [UNKNOWN] / [NO_TASK]
     */
    fun moveToDisplay(packageName: String, displayId: Int): Int {
        val atm = activityTaskManager()
        if (atm == null) {
            Log.e(TAG, "拿不到 IActivityTaskManager")
            return UNKNOWN
        }

        val taskId = findTaskId(packageName)
        if (taskId <= 0) {
            Log.w(TAG, "找不到 $packageName 的运行中任务（taskId=$taskId）")
            return if (taskId == NO_TASK) NO_TASK else UNKNOWN
        }

        // 方法名随版本不同，按顺序试。
        for (name in arrayOf("moveRootTaskToDisplay", "moveStackToDisplay")) {
            val moved = runCatching {
                atm.javaClass
                    .getMethod(name, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }
                    .invoke(atm, taskId, displayId)
                Log.i(TAG, "$name($taskId, $displayId) 调用成功")
                true
            }.getOrElse {
                Log.w(TAG, "$name 不可用或失败：${it.javaClass.simpleName}", it)
                false
            }
            if (moved) return taskId
        }

        Log.e(TAG, "两个搬运方法都失败，taskId=$taskId")
        return UNKNOWN
    }

    /**
     * 查 [packageName] 最近任务所在的 displayId。
     *
     * @return displayId；[NO_TASK] 表示没有运行中任务；[UNKNOWN] 表示判断不了
     *   （API < Q / 反射失败）。**调用方必须区分这三者** —— 把「判断不了」当成
     *   「不在副屏」会误触发搬移，把「没有任务」当成「在副屏」会误判成功。
     */
    fun taskDisplayId(packageName: String): Int {
        val task = findRecentTask(packageName) ?: return NO_TASK
        val field = displayIdField() ?: return UNKNOWN
        return runCatching { field.getInt(task) }.getOrDefault(UNKNOWN)
    }

    /** 列出所有任务及其 displayId，给诊断用（排查"到底在不在副屏"最直接）。 */
    fun dumpTasks(limit: Int = 12): String {
        val atm = activityTaskManager() ?: return "（拿不到 IActivityTaskManager）"
        val tasks = tasksOf(atm) ?: return "（getTasks 返回空或失败）"
        val field = displayIdField()
        return tasks.take(limit).joinToString("\n") { info ->
            val base = componentPackage(info, "baseActivity") ?: "?"
            val top = componentPackage(info, "topActivity") ?: "?"
            val taskId = runCatching { info.javaClass.getField("taskId").getInt(info) }.getOrDefault(-1)
            val disp = runCatching { field?.getInt(info) ?: -1 }.getOrDefault(-1)
            "  taskId=$taskId display=$disp $base / $top"
        }.ifEmpty { "（没有任务）" }
    }

    // ------------------------------------------------------------ 内部

    /** `ServiceManager.getService("activity_task")` -> `IActivityTaskManager$Stub.asInterface` */
    private fun activityTaskManager(): Any? = runCatching {
        val binder = Class.forName("android.os.ServiceManager")
            .getDeclaredMethod("getService", String::class.java)
            .invoke(null, "activity_task") as IBinder
        Class.forName("android.app.IActivityTaskManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
    }.onFailure { Log.e(TAG, "取 IActivityTaskManager 失败", it) }
        .getOrNull()

    /** 遍历最近任务，找属于该包的那一条（优先 topActivity，其次 baseActivity）。 */
    private fun findRecentTask(packageName: String): ActivityManager.RunningTaskInfo? {
        val atm = activityTaskManager() ?: return null
        val tasks = tasksOf(atm) ?: return null
        return tasks.firstOrNull { info ->
            componentPackage(info, "topActivity") == packageName ||
                componentPackage(info, "baseActivity") == packageName
        }
    }

    private fun findTaskId(packageName: String): Int {
        val task = findRecentTask(packageName) ?: return NO_TASK
        return runCatching { task.javaClass.getField("taskId").getInt(task) }.getOrDefault(UNKNOWN)
    }

    /**
     * 调 `getTasks` 取任务列表。
     *
     * ★ **签名随版本不同，必须按候选依次试。** `IActivityTaskManager` 上它是
     * `getTasks(int maxNum, boolean filterOnlyVisibleRecents, boolean keepIntentExtra, int displayId)`
     * —— 四个参数。我最初按 `ActivityManager.getRunningTasks(int)` 的记忆只传一个 int，
     * 报的却是 `NoSuchMethodException: ...$Proxy.getTasks [int]`，
     * 一眼看上去像「方法不存在」，其实是「这个重载不存在」。
     */
    private fun tasksOf(atm: Any): List<ActivityManager.RunningTaskInfo>? {
        // 从参数最多的开始试：4 参是 AOSP 11+ 的形态。
        val candidates: List<Array<Class<*>>> = listOf(
            arrayOf(Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
            arrayOf(Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!),
            arrayOf(Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!),
            arrayOf(Int::class.javaPrimitiveType!!),
        )
        val argSets: List<Array<Any>> = listOf(
            arrayOf(MAX_TASKS, false, true, DEFAULT_DISPLAY),
            arrayOf(MAX_TASKS, false, true),
            arrayOf(MAX_TASKS, false),
            arrayOf(MAX_TASKS),
        )

        for (i in candidates.indices) {
            val result = runCatching {
                @Suppress("UNCHECKED_CAST")
                atm.javaClass
                    .getMethod("getTasks", *candidates[i])
                    .apply { isAccessible = true }
                    .invoke(atm, *argSets[i]) as? List<ActivityManager.RunningTaskInfo>
            }
            val list = result.getOrNull()
            if (list != null) {
                Log.i(TAG, "getTasks 用 ${candidates[i].size} 个参数调用成功，返回 ${list.size} 条任务")
                return list
            }
            if (result.exceptionOrNull() !is NoSuchMethodException) {
                // 方法存在但调用失败（权限 / Binder）—— 这个才是真问题，打全。
                val e = result.exceptionOrNull()!!
                Log.w(TAG, "getTasks(${candidates[i].size}参) 调用失败：${e.javaClass.name}: ${e.message}", e)
                e.cause?.let { Log.w(TAG, "  cause: ${it.javaClass.name}: ${it.message}") }
                return null
            }
        }
        Log.e(TAG, "getTasks 的所有候选签名都不存在（ROM 改过 AIDL？）")
        return null
    }

    private fun componentPackage(info: Any, fieldName: String): String? = runCatching {
        (info.javaClass.getField(fieldName).get(info) as? ComponentName)?.packageName
    }.getOrNull()

    /**
     * `RunningTaskInfo.displayId` 是 @hide，且可能声明在父类上 —— 所以要沿父类链找。
     * 只查当前类会得到 null，然后所有任务都显示 display=-1，排查时会误以为字段不存在。
     */
    private fun displayIdField(): java.lang.reflect.Field? = runCatching {
        var cls: Class<*>? = ActivityManager.RunningTaskInfo::class.java
        var found: java.lang.reflect.Field? = null
        while (cls != null && found == null) {
            found = runCatching { cls!!.getDeclaredField("displayId") }.getOrNull()
            cls = cls.superclass
        }
        found?.also { it.isAccessible = true }
    }.getOrNull()
}
