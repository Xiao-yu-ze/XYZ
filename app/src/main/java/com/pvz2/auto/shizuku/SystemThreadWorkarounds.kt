package com.pvz2.auto.shizuku

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Looper
import android.util.Log

/**
 * 在 `app_process` 里把 `ActivityThread` 手工"掰"成一个可用的系统线程。
 *
 * ## 为什么不能直接用 `ActivityThread.systemMain()`
 *
 * `systemMain()` 内部会构造 `ApplicationInfo`、注册各种服务、**并且会碰到
 * Context 相关的初始化** —— 而我们的 [FakeContext] 构造时又需要系统 Context，
 * 于是形成环：FakeContext → ShellContext → systemMain → FakeContext → …
 * 实测表现为 `StackOverflowError` / 反复重入。
 *
 * ## scrcpy / MaaFwApp 的做法（这里是照抄）
 *
 * 不走 `systemMain()`，而是**手动装配**：
 *
 * ```
 * 1. Looper.prepare()，并把 sMainLooper 指过去
 * 2. new ActivityThread()                 ← 用无参构造
 * 3. ActivityThread.sCurrentActivityThread = 它
 * 4. activityThread.mSystemThread = true
 * 5. 之后 getSystemContext() 就能正常返回了
 * ```
 *
 * 少了 `systemMain()` 里那些副作用，也就不会反过来依赖 Context。
 *
 * ## Android 12+ 的额外补丁
 *
 * 12 起 `DisplayManagerGlobal.getDisplayInfoLocked()` 会调
 * `ActivityThread.currentActivityThread().getConfiguration()`，
 * 而后者要求 `mConfigurationController` 非空 —— 不补会在取屏信息时 NPE。
 * 这是 scrcpy issue #4467 记录的问题，这里一并补上。
 *
 * ## 调用前提
 *
 * **必须在已经 `Looper.prepare()` 的线程上调用**（UserService 初始化时已保证）。
 */
internal object SystemThreadWorkarounds {

    private const val TAG = "SystemThreadWorkarounds"

    @Volatile
    private var activityThread: Any? = null

    /** 幂等：重复调用只装配一次。 */
    fun apply() {
        activityThread ?: synchronized(this) {
            activityThread ?: install().also { activityThread = it }
        }
    }

    private fun install(): Any {
        prepareMainLooper()

        val cls = Class.forName("android.app.ActivityThread")

        val thread = cls.getDeclaredConstructor()
            .apply { isAccessible = true }
            .newInstance()

        cls.getDeclaredField("sCurrentActivityThread")
            .apply { isAccessible = true }
            .set(null, thread)

        cls.getDeclaredField("mSystemThread")
            .apply { isAccessible = true }
            .setBoolean(thread, true)

        // Android 12+：补 ConfigurationController，否则取 DisplayInfo 会 NPE。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            fillConfigurationController(cls, thread)
        }
        fillAppInfo(cls, thread)
        fillAppContext(cls, thread)

        Log.i(TAG, "ActivityThread 已手工装配完成")
        return thread
    }

    /**
     * `Looper.prepare()` + 把 `sMainLooper` 指过去。
     *
     * 不能直接用 `Looper.prepareMainLooper()` —— 那个 quitAllowed=false，
     * 而且会抛异常阻止二次调用。
     */
    private fun prepareMainLooper() {
        if (Looper.myLooper() != null) return
        Looper.prepare()
        synchronized(Looper::class.java) {
            Looper::class.java
                .getDeclaredField("sMainLooper")
                .apply { isAccessible = true }
                .set(null, Looper.myLooper())
        }
    }

    /** `mConfigurationController = new ConfigurationController(activityThread)` */
    private fun fillConfigurationController(cls: Class<*>, thread: Any) {
        runCatching {
            val controllerCls = Class.forName("android.app.ConfigurationController")
            val threadInternalCls = Class.forName("android.app.ActivityThreadInternal")
            val controller = controllerCls
                .getDeclaredConstructor(threadInternalCls)
                .apply { isAccessible = true }
                .newInstance(thread)
            cls.getDeclaredField("mConfigurationController")
                .apply { isAccessible = true }
                .set(thread, controller)
        }.onFailure { Log.d(TAG, "补 ConfigurationController 失败（可忽略）: ${it.message}") }
    }

    /**
     * `mBoundApplication.appInfo = ApplicationInfo(packageName = com.android.shell)`
     *
     * 某些 ROM 会靠它判断"这个进程属于哪个包"，缺了会被当成匿名进程。
     */
    private fun fillAppInfo(cls: Class<*>, thread: Any) {
        runCatching {
            val bindDataCls = Class.forName("android.app.ActivityThread\$AppBindData")
            val bindData = bindDataCls.getDeclaredConstructor()
                .apply { isAccessible = true }
                .newInstance()

            val info = ApplicationInfo().apply { packageName = FakeContext.PACKAGE_NAME }
            bindDataCls.getDeclaredField("appInfo")
                .apply { isAccessible = true }
                .set(bindData, info)

            cls.getDeclaredField("mBoundApplication")
                .apply { isAccessible = true }
                .set(thread, bindData)
        }.onFailure { Log.d(TAG, "补 AppBindData 失败（可忽略）: ${it.message}") }
    }

    /** `mInitialApplication = new Application()` —— 有些服务会拿它当调用者。 */
    private fun fillAppContext(cls: Class<*>, thread: Any) {
        runCatching {
            val app: Application = Instrumentation.newApplication(
                Application::class.java,
                FakeContext.get()
            )
            cls.getDeclaredField("mInitialApplication")
                .apply { isAccessible = true }
                .set(thread, app)
        }.onFailure { Log.d(TAG, "补 initialApplication 失败（可忽略）: ${it.message}") }
    }

    /** 系统 Context —— `FakeContext` 的父类就是包在它外面的。 */
    fun getSystemContext(): Context? = runCatching {
        val cls = Class.forName("android.app.ActivityThread")
        val thread = activityThread ?: return null
        cls.getDeclaredMethod("getSystemContext")
            .apply { isAccessible = true }
            .invoke(thread) as? Context
    }.onFailure { Log.e(TAG, "取系统 Context 失败", it) }
        .getOrNull()
}
