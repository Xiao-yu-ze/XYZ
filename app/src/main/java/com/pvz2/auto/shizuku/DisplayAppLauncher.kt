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

    /**
     * `ActivityManager.startActivity` 的返回码。
     *
     * 只有 `START_SUCCESS(0)` 才是"新起在目标屏"。
     * 2/3 表示**复用了已有任务**，此时系统会忽略 `launchDisplayId`，
     * 任务留在原来那块屏上 —— 这正是"游戏跑到主屏、整个物理屏被占满"
     * 的形态，而它返回的是非负数。
     */
    private const val START_TASK_TO_FRONT = 2
    private const val START_DELIVERED_TO_TOP = 3

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
        }.onFailure {
            Log.e(TAG, "构造 ActivityOptions 失败", it)
        }.getOrNull()

        // ★ options 为 null 时**不能**继续走 Binder 路径。
        //
        //   ActivityOptions 里装的就是 launchDisplayId；它没了，startActivityAsUser
        //   会老老实实在**主屏**启动，而且返回成功码。日志看起来一切正常，
        //   用户看到的却是"整个屏幕变成游戏"。这种静默降级最难查，所以直接改走
        //   `am start --display`，至少 displayId 还在。
        if (options == null) {
            Log.w(TAG, "ActivityOptions 构造失败，改用 am 命令（保留 displayId=$displayId）")
            return launchViaAm(intent, displayId)
        }

        return runCatching {
            val method = am.javaClass.methods.firstOrNull { it.name == "startActivity" }
                ?: error("找不到 startActivity")
            // IActivityManager.startActivity 的参数随版本变，这里只打日志把签名露出来，
            // 真正的调用交给 startActivityAsUser 的更通用重载（见下）。
            Log.i(TAG, "startActivity 候选签名：${method.parameterTypes.joinToString { it.simpleName }}")
            startActivityInternal(am, intent, options, displayId)
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
        options: android.os.Bundle?,
        displayId: Int
    ): Boolean {
        val all = am.javaClass.methods.filter { it.name == "startActivityAsUser" }
        all.forEach { m ->
            Log.i(TAG, "候选签名(${m.parameterTypes.size}): " + m.parameterTypes.joinToString { it.simpleName })
        }
        // 挑「带 Bundle 参数（能传 ActivityOptions）且参数最多」的那个。
        //
        // 不能简单取参数最多的：不同 ROM 上重载集合不同，取错了会把
        // callingPackage / options 填到错误位置 —— 而错的调用往往**返回成功码**，
        // 表现却是 launchDisplayId 没生效、窗口仍留在主屏。这种静默失败最难查。
        val method = all
            .filter { m -> m.parameterTypes.any { it == android.os.Bundle::class.java } }
            .maxByOrNull { it.parameterTypes.size }
            ?: all.filter { it.parameterTypes.size >= 8 }.maxByOrNull { it.parameterTypes.size }
            ?: run {
                Log.e(TAG, "没有可用的 startActivityAsUser 重载（带 Bundle 的也没有），改用 am 兜底")
                // ★ 这里必须把 displayId 传下去。之前硬编码 -1，
                //   于是兜底路径永远在主屏启动，而日志只会说"启动指令已发出"。
                return launchViaAm(intent, displayId)
            }
        val types = method.parameterTypes
        Log.i(TAG, "选用参数数=${types.size} 的重载")

        // 按位置填：已知位置填真实值，其余类型用 null（Binder 侧接受 null 的引用类型）
        val args = arrayOfNulls<Any?>(types.size)
        var intentPlaced = false
        var optionsPlaced = false
        var packagePlaced = false
        var userIdPlaced = false

        // ★ userId 是关键：它决定 Activity 起在哪个用户上下文里。
        //   填 0 看着"成功"（Binder 返回 0），但游戏进程根本没起来 ——
        //   因为系统按 userId 找/建进程，错的 userId 会让启动静默落空。
        //   这里取当前进程所在的 userId，而不是硬编码 0。
        val currentUserId = android.os.Process.myUid() / 100000

        // Binder 接口的 class 文件里带着参数名（AIDL 编译保留），能读到就直接按名字填。
        // 读不到（ROM 抹掉了）再退回下面的按位置策略。
        val names = runCatching { method.parameters.map { it.name } }.getOrDefault(emptyList())

        for (i in types.indices) {
            val t = types[i]
            val name = names.getOrNull(i)?.lowercase() ?: ""

            when {
                !intentPlaced && t == Intent::class.java -> {
                    args[i] = intent
                    intentPlaced = true
                }

                !optionsPlaced && t == android.os.Bundle::class.java -> {
                    args[i] = options
                    optionsPlaced = true
                }

                // callingPackage / callingFeatureId 都是 String，第一个填包名，
                // 后面那个（若存在）留 null 即可 —— 只有 callingPackage 参与归属校验。
                !packagePlaced && t == String::class.java -> {
                    args[i] = SHELL_PACKAGE
                    packagePlaced = true
                }

                !userIdPlaced && name == "userid" && t == Int::class.javaPrimitiveType -> {
                    args[i] = currentUserId
                    userIdPlaced = true
                }

                t.isPrimitive -> {
                    // requestCode / startFlags / 其它 int → 0；boolean → false
                    args[i] = if (t == Int::class.javaPrimitiveType) 0 else false
                }

                else -> args[i] = null
            }
        }

        // 若参数名读不到、userId 没被填上，就退回"最后一个 int 是 userId"的经验规则。
        if (!userIdPlaced) {
            val lastIntIndex = types.indices.lastOrNull { types[it] == Int::class.javaPrimitiveType }
            if (lastIntIndex != null) {
                args[lastIntIndex] = currentUserId
                Log.i(TAG, "参数名不可读，按「最后一个 int = userId」填 $currentUserId")
            }
        }

        Log.i(
            TAG,
            "startActivityAsUser 参数：${names.mapIndexed { i, n -> "$i:$n=${args[i]}" }.joinToString()}"
        )
        return runCatching {
            method.isAccessible = true
            val ret = method.invoke(am, *args)
            Log.i(TAG, "startActivityAsUser 返回 $ret")

            // ★ 必须检查返回码。
            //
            //   之前这里写死 `true` —— 只要反射没抛异常就当成功。但
            //   `startActivityAsUser` 的返回值是 **start result 码**：
            //
            //     < 0   失败（-97 START_NOT_VISIBLE、-94 START_CANCELED…）
            //     = 0   START_SUCCESS，新任务起在指定屏上 ✓
            //     = 2   START_TASK_TO_FRONT —— **复用了已有任务**
            //     = 3   START_DELIVERED_TO_TOP —— **复用了已有任务**
            //
            //   2 和 3 是关键：复用已有任务时系统**忽略 launchDisplayId**，
            //   任务留在原来那块屏上 —— 这正是用户反馈两次的"游戏跑到主屏、
            //   整个物理屏被占满"的形态。而它返回的是非负数，会被当成功。
            //
            //   所以：只有 0 才算真正的"起在目标屏"。2/3 交给调用方复核
            //   task 的实际 displayId，必要时 force-stop 重来。
            val code = (ret as? Number)?.toInt() ?: 0
            when {
                code < 0 -> {
                    Log.w(TAG, "startActivityAsUser 返回错误码 $code，改用 am 兜底")
                    launchViaAm(intent, displayId)
                }

                code == START_TASK_TO_FRONT || code == START_DELIVERED_TO_TOP -> {
                    Log.w(
                        TAG,
                        "startActivityAsUser 返回 $code（复用已有任务）—— " +
                            "launchDisplayId 会被忽略，任务可能仍在原屏"
                    )
                    // 不算成功：调用方会用 getTaskDisplayId 复核，
                    // 不在目标屏就 force-stop 重来。
                    false
                }

                else -> true
            }
        }.getOrElse {
            Log.e(TAG, "startActivityAsUser 反射调用失败", it)
            launchViaAm(intent, displayId)
        }
    }

    /**
     * 最后兜底：走 `am start`。
     *
     * ## 绝对不能拼成 `sh -c "..."` 字符串
     *
     * intent URI 长这样：
     * ```
     * intent:#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;component=com.foo/.Main;end
     * ```
     * 里面全是**分号**，而 `sh -c` 把分号当命令分隔符 —— 整条命令会被切成
     * 七八条互相独立的语句，`am` 只收到半截 URI，必然失败。
     *
     * 正确做法是 `ProcessBuilder` 传**参数数组**，由内核直接 exec，
     * 完全不经过 shell。MaaFwApp 的 `startViaAmCommand` 就是这么写的。
     */
    private fun launchViaAm(intent: Intent, displayId: Int): Boolean = runCatching {
        val spec = intent.toUri(Intent.URI_INTENT_SCHEME)
        val args = if (displayId >= 0) {
            arrayOf("am", "start", "--display", displayId.toString(), spec)
        } else {
            arrayOf("am", "start", spec)
        }
        Log.i(TAG, "兜底命令：${args.joinToString(" ")}")

        val p = ProcessBuilder(*args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        val exit = p.waitFor()
        Log.i(TAG, "am 输出：${out.trim().take(300)}")

        // ★ 不能无条件 true。
        //
        //   `am start` 失败时依然 exit 0，只在输出里打一行
        //   `Error: Activity not started...`。之前直接 return true，
        //   于是"启动失败"被当成"启动成功"，后面的轮询要白等一整轮。
        val failed = exit != 0 ||
            out.contains("Error:") ||
            out.contains("Exception") ||
            out.contains("Failure")
        if (failed) Log.w(TAG, "am 命令未成功启动（exit=$exit）")
        !failed
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
     * ## 做法照抄 MaaFwApp（`ActivityUtils.startAppOnDisplay`）
     *
     * ```kotlin
     * val pm = FakeContext.get().packageManager
     * ```
     *
     * ## 为什么不能用 `ActivityThread.systemMain()`
     *
     * Android 高版本 真机日志给出了确凿证据：
     *
     * ```
     * W/DisplayAppLauncher: 取系统 PackageManager 失败
     * Caused by: java.lang.RuntimeException: Can't create handler inside thread
     *   Thread[binder:29199_3,5,main] that has not called Looper.prepare()
     *     at android.app.ActivityThread.systemMain(ActivityThread.java:9631)
     * ```
     *
     * `systemMain()` 内部 `new ActivityThread.H()`，而 Handler 必须在有 Looper 的
     * 线程上构造。本方法是从 AIDL 的 Binder 线程调进来的 —— 那些线程没有 Looper。
     *
     * 后果是整条链断在第一步：造不出 launch intent → `launchOnDisplay` 返回 false
     * → 游戏从未启动 → 任务查不到（`getTaskDisplayId` 返回 -2）→ 搬移也无从谈起
     * → 屏上没内容 → 0 帧。日志里后面那一长串失败**全是这一个异常的下游**。
     *
     * `FakeContext` 的父类是 [ShellContext]，而后者由 [SystemThreadWorkarounds]
     * **手工装配** ActivityThread（初始化时在 Looper 线程上做一次），之后从任何
     * 线程读系统 Context 都不再需要 Looper。
     */
    private fun packageManager(): android.content.pm.PackageManager? {
        // 前两级在纯 app_process 里通常都是 null（initForAllUsers 没跑过），
        // 留着只是为了在宿主进程里调用时能走快路径。
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

        // 正解：FakeContext 的 PM。它背后是手工装配过的系统 Context，不碰 Looper。
        val fakePm = runCatching { FakeContext.get().packageManager }
            .onFailure { Log.w(TAG, "从 FakeContext 取 PackageManager 失败", it) }
            .getOrNull()
        if (fakePm != null) {
            Log.i(TAG, "PackageManager 来自 FakeContext")
            return fakePm
        }

        Log.e(TAG, "拿不到 PackageManager —— 无法为应用构造 launch intent")
        return null
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
