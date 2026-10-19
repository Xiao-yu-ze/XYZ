package com.pvz2.auto.shizuku

import android.content.ComponentName
import android.util.Log

/**
 * 把某个应用的 task 搬到指定 display —— 走 `ActivityTaskManager` 反射，
 * 而不是 shell 的 `am display move-stack`。
 *
 * ## 为什么不用 `am`
 *
 * 实测（MuMu / Android 12）：
 *  · `am start --display <id>` **不会**把任务搬到那块屏 —— 它只是把应用重启了一遍，
 *    任务仍然落在 Display #0（dumpsys 里能直接看到）。
 *  · `am display move-stack <taskId> <id>` 要的是**真实 taskId**，传 0 会报
 *    `Unknown rootTaskId=0`；而 taskId 还得另外去解析，多一层易错的字符串处理。
 *
 * MaaFwApp 的做法是直接在特权进程里两步走：
 *
 * ```
 * ActivityTaskManager.getTasks(max)              -> 找到目标包的 taskId
 * ActivityTaskManager.moveRootTaskToDisplay(...) -> 搬
 * ```
 *
 * 两个方法都是 @hide，但我们的进程是 shell(uid 2000)，持有 `MANAGE_ACTIVITY_TASKS`
 * 与 `REAL_GET_TASKS`，所以能过。
 */
internal object DisplayTaskMover {

    private const val TAG = "DisplayTaskMover"

    /** 一次查多少条最近任务。100 足够覆盖普通使用。 */
    private const val MAX_TASKS = 100

    /**
     * 把 [packageName] 的任务搬到 [displayId]。
     *
     * @return 搬动成功的 taskId；失败返回 -1（原因见日志）
     */
    fun moveToDisplay(packageName: String, displayId: Int): Int {
        val atm = activityTaskManager()
        if (atm == null) {
            Log.e(TAG, "拿不到 ActivityTaskManager")
            return -1
        }

        val taskId = findTaskId(atm, packageName)
        if (taskId < 0) {
            Log.w(TAG, "没找到 $packageName 的任务（可能还没启动）")
            return -1
        }

        return runCatching {
            atm.javaClass
                .getMethod(
                    "moveRootTaskToDisplay",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
                .apply { isAccessible = true }
                .invoke(atm, taskId, displayId)
            Log.i(TAG, "已把 $packageName 的 task $taskId 搬到 display $displayId")
            taskId
        }.getOrElse {
            Log.e(TAG, "moveRootTaskToDisplay 失败", it)
            -1
        }
    }

    /** 遍历最近任务，找属于该包的那一条。 */
    private fun findTaskId(atm: Any, packageName: String): Int = runCatching {
        val tasks = atm.javaClass
            .getMethod("getTasks", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(atm, MAX_TASKS) as? List<*>
            ?: return -1

        val found = tasks.asSequence()
            .filterNotNull()
            .firstOrNull { info ->
                // RunningTaskInfo 的 baseActivity / topActivity 是 public 字段，
                // 直接读字段比调 getter 稳（getter 在不同版本里可能改名）。
                componentPackage(info, "baseActivity") == packageName ||
                    componentPackage(info, "topActivity") == packageName
            }

        found?.let { info -> info.javaClass.getField("taskId").getInt(info) } ?: -1
    }.onFailure { Log.e(TAG, "遍历任务失败", it) }
        .getOrDefault(-1)

    private fun componentPackage(info: Any, fieldName: String): String? = runCatching {
        (info.javaClass.getField(fieldName).get(info) as? ComponentName)?.packageName
    }.getOrNull()

    private fun activityTaskManager(): Any? = runCatching {
        Class.forName("android.app.ActivityTaskManager")
            .getMethod("getService")
            .apply { isAccessible = true }
            .invoke(null)
    }.onFailure { Log.e(TAG, "取 ActivityTaskManager 失败", it) }
        .getOrNull()
}
