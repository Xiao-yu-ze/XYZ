package com.pvz2.auto.shizuku

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * 在**指定 display** 上启动应用。
 *
 * ## 为什么必须"启动时指定"，而不是"启动后搬"
 *
 * 实测（MuMu / Android 12）：
 *  · `am start --display <id>`：命令成功，但任务落在 Display #0。
 *  · `moveRootTaskToDisplay(taskId, <id>)`：**任务记录**确实改到了副屏
 *    （`dumpsys activity activities` 里能看到 `Display #11` 下有这个 task），
 *    但 `dumpsys window windows` 里**没有任何窗口属于 display 11** ——
 *    窗口没跟着搬，于是 InputDispatcher 报「这块屏上没有可触摸窗口」，
 *    所有注入都被丢弃。搬家搬的是壳，窗口留在原地。
 *
 * 正解是 MaaFwApp 的做法：用 `ActivityOptions.launchDisplayId` 在**启动那一刻**
 * 就告诉系统「这个 Activity 要起在那块屏上」，由系统在建窗口时就挂对 display。
 *
 * ## 为什么走 Binder 而不是 `am` 命令
 *
 * `am start --display` 是 `ActivityManagerShellCommand` 里的一套逻辑，与
 * `IActivityManager.startActivity` 的调用语义不完全一致（实测它不吃 display）。
 * 直接调 Binder：
 *
 * ```
 * ActivityOptions.makeBasic()
 *   .setLaunchDisplayId(displayId)      // @hide，反射
 *   .toBundle()
 * IActivityManager.startActivity(..., options)
 * ```
 *
 * 注意 `ActivityManager` 的 binder 服务名是 `"activity"`（不是 `"activity_task"`，
 * 后者是 `IActivityTaskManager`，只管任务列表与搬运）。
 */
internal object DisplayAppLauncher {

    private const val TAG = "DisplayAppLauncher"

    /** `android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN` */
    private const val WINDOWING_MODE_FULLSCREEN = 1

    /** 调用者包名。uid 2000 名下只有它，且 startActivityAsUser 会校验包名归属。 */
    private const val SHELL_PACKAGE = "com.android.shell"

    /**
     * 在 [displayId] 上启动 [packageName]。
     *
     * @return true 表示 Binder 调用被系统接受（**不保证窗口已经建好**，
     *   调用方仍需用 [DisplayTaskMover] 或窗口列表确认）
     */
    fun launchOnDisplay(packageName: String, displayId: Int): Boolean {
        val am = activityManager()
        if (am == null) {
            Log.e(TAG, "拿不到 IActivityManager")
            return false
        }

        val intent = buildLaunchIntent(packageName)
        if (intent == null) {
            Log.e(TAG, "为 $packageName 造不出 launch intent")
            return false
        }

        val options = runCatching {
            val opts = ActivityOptions.makeBasic()
            // setLaunchDisplayId 是 @hide，反射设。它就是「起在那块屏上」的唯一开关。
            opts.javaClass
                .getMethod("setLaunchDisplayId", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(opts, displayId)
            // 副屏上强制全屏：否则可能以自由窗口/分屏形态起，坐标换算全乱。
            runCatching {
                opts.javaClass
                    .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }
                    .invoke(opts, WINDOWING_MODE_FULLSCREEN)
            }.onFailure { Log.w(TAG, "setLaunchWindowingMode 失败（可忽略）", it) }
            opts.toBundle()
        }.getOrElse {
            Log.e(TAG, "构造 ActivityOptions 失败", it)
            null
        }

        return runCatching {
            val method = am.javaClass.methods.firstOrNull { it.name == "startActivity" }
                ?: error("找不到 startActivity")
            // IActivityManager.startActivity 的参数随版本变，这里只打日志把签名露出来，
            // 真正的调用交给 startActivityAsUser 的更通用重载（见下）。
            Log.i(TAG, "startActivity 候选签名：${method.parameterTypes.joinToString { it.simpleName }}")
            startActivityInternal(am, intent, options)
        }.getOrElse {
            Log.e(TAG, "startActivity Binder 调用失败", it)
            false
        }
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 调 `IActivityManager.startActivityAsUser`。
     *
     * 这个方法在所有目标版本上签名一致（AOSP 8~14 都是这一套），
     * 比 `startActivity` 那一堆重载稳：
     * ```
     * startActivityAsUser(IApplicationThread caller, String callingPackage,
     *   String callingFeatureId, Intent intent, String resolvedType,
     *   IBinder resultTo, String resultWho, int requestCode,
     *   int startFlags, ProfilerInfo profilerInfo, Bundle options, int userId)
     * ```
     * 但参数类型里有 `IApplicationThread` / `ProfilerInfo` 这类 @hide 类型，
     * 反射时按「参数个数 + 已知类名子串」挑，避免硬编码类名在不同版本上失配。
     */
    private fun startActivityInternal(
        am: Any,
        intent: Intent,
        options: android.os.Bundle?
    ): Boolean {
        val candidates = am.javaClass.methods.filter {
            it.name == "startActivityAsUser" && it.parameterTypes.size >= 8
        }
        if (candidates.isEmpty()) {
            Log.e(TAG, "没有可用的 startActivityAsUser 重载，改用 am 命令兜底")
            return launchViaAm(intent, displayId = -1)
        }
        // 参数最多的那个就是完整版
        val method = candidates.maxByOrNull { it.parameterTypes.size }!!
        val types = method.parameterTypes
        Log.i(TAG, "选用 startActivityAsUser，参数数=${types.size}")

        // 按位置填：已知位置填真实值，其余类型用 null（Binder 侧接受 null 的引用类型）
        val args = arrayOfNulls<Any?>(types.size)
        var intentPlaced = false
        var optionsPlaced = false
        var packagePlaced = false
        for (i in types.indices) {
            val t = types[i]
            if (!intentPlaced && t == Intent::class.java) {
                args[i] = intent
                intentPlaced = true
            } else if (!optionsPlaced && t == android.os.Bundle::class.java) {
                args[i] = options
                optionsPlaced = true
            } else if (!packagePlaced && t == String::class.java) {
                // ★ callingPackage 必须填，且**必须属于调用者 uid**。
                //   填 null 会撞 assertPackageMatchesCallingUid：
                //   `Permission Denial: package=null does not belong to uid=2000`。
                //   这里是第一个 String 参数，位置对应 callingPackage。
                args[i] = SHELL_PACKAGE
                packagePlaced = true
            } else if (t.isPrimitive) {
                // requestCode / startFlags → 0；userId → 当前用户(0)
                args[i] = if (t == Int::class.javaPrimitiveType) 0 else false
            } else {
                args[i] = null
            }
        }
        return runCatching {
            method.isAccessible = true
            val ret = method.invoke(am, *args)
            Log.i(TAG, "startActivityAsUser 返回 $ret")
            true
        }.getOrElse {
            Log.e(TAG, "startActivityAsUser 反射调用失败", it)
            false
        }
    }

    /** 最后兜底：走 am 命令（--display 实测不可靠，但总比什么都不做强）。 */
    private fun launchViaAm(intent: Intent, displayId: Int): Boolean = runCatching {
        val spec = intent.toUri(Intent.URI_INTENT_SCHEME)
        val cmd = if (displayId >= 0) "am start --display $displayId $spec" else "am start $spec"
        Log.i(TAG, "兜底命令：$cmd")
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        Log.i(TAG, "am 输出：${out.trim().take(300)}")
        true
    }.getOrDefault(false)

    /**
     * 造标准 launch intent（不带任何 display 设置 —— display 由 ActivityOptions 负责）。
     *
     * 支持两种写法：`com.foo` 纯包名，或 `com.foo/.MainActivity` 组件全名。
     * 纯包名走 PackageManager；在 app_process 里拿不到应用自己的 PackageManager 时，
     * 直接放弃（返回 null），由调用方报错 —— 比悄悄用一个缺包过滤的系统 PM 更安全。
     */
    private fun buildLaunchIntent(packageName: String): Intent? {
        val intent: Intent = if (packageName.contains('/')) {
            val cn = ComponentName.unflattenFromString(packageName) ?: return null
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(cn)
        } else {
            val pm = packageManager() ?: return null
            runCatching {
                pm.getLaunchIntentForPackage(packageName)
                    ?: pm.getLeanbackLaunchIntentForPackage(packageName)
            }.getOrNull() ?: return null
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        return intent
    }

    /**
     * 取 PackageManager。
     *
     * 三级回退，因为 `app_process` 里不一定有 Application：
     *  1. `AppGlobals.getInitialApplication()` —— Shizuku starter 建过 Application 时可用；
     *  2. `ActivityThread.currentApplication()`；
     *  3. **系统 Context 的 PM** —— `getLaunchIntentForPackage` 不需要按 uid 过滤包名，
     *     所以拿到系统 PM 也够用。实测前两级在纯 `app_process` 里都是 null，
     *     真正起作用的是这一级。
     */
    private fun packageManager(): android.content.pm.PackageManager? {
        for (getter in arrayOf("getInitialApplication", "currentApplication")) {
            val pm = runCatching {
                val app = when (getter) {
                    "getInitialApplication" -> Class.forName("android.app.AppGlobals")
                        .getMethod(getter).apply { isAccessible = true }.invoke(null)

                    else -> Class.forName("android.app.ActivityThread")
                        .getMethod(getter).apply { isAccessible = true }.invoke(null)
                } ?: return@runCatching null
                app.javaClass.getMethod("getPackageManager").invoke(app)
                    as? android.content.pm.PackageManager
            }.getOrNull()
            if (pm != null) {
                Log.i(TAG, "PackageManager 来自 $getter")
                return pm
            }
        }

        // 第三级：系统 Context。ActivityThread.systemMain() 需要 Looper，这里本线程已 prepare。
        val systemPm = runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val thread = activityThreadClass
                .getDeclaredMethod("systemMain")
                .apply { isAccessible = true }
                .invoke(null)
            val ctx = activityThreadClass
                .getDeclaredMethod("getSystemContext")
                .apply { isAccessible = true }
                .invoke(thread) as? android.content.Context
            ctx?.packageManager
        }.onFailure { Log.w(TAG, "取系统 PackageManager 失败", it) }
            .getOrNull()
        if (systemPm != null) Log.i(TAG, "PackageManager 来自系统 Context")
        return systemPm
    }

    private fun activityManager(): Any? = runCatching {
        val binder = Class.forName("android.os.ServiceManager")
            .getDeclaredMethod("getService", String::class.java)
            .invoke(null, "activity") as IBinder
        Class.forName("android.app.IActivityManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
    }.onFailure { Log.e(TAG, "取 IActivityManager 失败", it) }
        .getOrNull()
}
