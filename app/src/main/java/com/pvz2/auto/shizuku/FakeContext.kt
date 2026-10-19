package com.pvz2.auto.shizuku

import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process

/**
 * 一个"假"的 Context，**专门用来骗过系统服务对调用者权限的检查**。
 *
 * ## 这个类是整条虚拟屏链路能不能成立的关键
 *
 * 做法照抄 MaaFwApp 的 `third/FakeContext.java`（它又源自 scrcpy）。
 *
 * 为什么必须这样：`DisplayManager.createVirtualDisplay()` 在系统侧会做这些检查：
 *
 * ```
 * if ((flags & FLAG_TRUSTED) != 0) {
 *     if (!checkCallingPermission(ADD_TRUSTED_DISPLAY)) throw SecurityException
 *     if (!canProjectVideo(...)) throw SecurityException
 * }
 * if ((flags & FLAG_AUTO_MIRROR) != 0) {
 *     if (!canProjectVideo(...)) throw SecurityException
 * }
 * ```
 *
 * `canProjectVideo()` 最终要看 `CAPTURE_VIDEO_OUTPUT` —— **shell 身份没有这个权限**，
 * 于是带 TRUSTED 的建屏请求一律被拒（我们实测连续三次
 * `InvocationTargetException` 就是这个）。
 *
 * 而 `checkCallingPermission()` 走的是**调用方 Context 的实现**。
 * 这里重写它、直接返回 `PERMISSION_GRANTED`，那些检查就会通过。
 *
 * ⚠️ 注意：这不是"提升权限"，我们仍然以 shell 身份运行。它只是让 Java 侧的
 * 权限查询返回"有"，而真正的系统调用仍然由 binder 上的真实 uid 决定。
 * 之所以能成立，是因为系统的这几处检查是**在进程内**问 Context 的。
 *
 * ## 其余几个覆写的作用
 *
 * - `getPackageName()` = `com.android.shell` —— 系统会校验
 *   `packageName must match the calling uid`，uid 2000 名下只有它；
 * - `getAttributionSource()` —— Android 12+ 的调用链会带归因来源，
 *   不覆写会拿到宿主（应用）的，从而被判定成"应用在伪造 shell 身份"；
 * - `getApplicationContext()` 返回自身 —— 避免系统拿它去问真正 Application 的东西。
 */
internal class FakeContext private constructor() : ContextWrapper(ShellContext.get()) {

    override fun checkCallingPermission(permission: String): Int = PackageManager.PERMISSION_GRANTED

    override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
        PackageManager.PERMISSION_GRANTED

    /**
     * 包名**必须属于当前真实 uid**，否则系统抛
     * `packageName must match the calling uid`。
     *
     * - shell(2000) → `com.android.shell`
     * - root(0)     → uid 0 名下没有任何包。但 uid 0 走的是 system_server 的
     *                 「拥有全部权限」分支，包名校验对它不作要求 ——
     *                 实测传 `com.android.shell` 会被拒，传 `android` 能过。
     */
    override fun getPackageName(): String = packageNameForUid()

    override fun getOpPackageName(): String = packageNameForUid()

    private fun packageNameForUid(): String =
        if (Process.myUid() == Process.ROOT_UID) ROOT_PACKAGE else PACKAGE_NAME

    override fun getApplicationContext(): Context = this

    /** Android 12+：不覆写的话系统会拿宿主的归因来源，判定身份不符。 */
    override fun getAttributionSource(): AttributionSource {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return super.getAttributionSource()
        }
        val builder = AttributionSource.Builder(Process.myUid())
        builder.setPackageName(packageNameForUid())
        return builder.build()
    }

    /**
     * ContentResolver。
     *
     * `IContentProvider` 是 @hide 类，编译期看不到，所以这里不去覆写
     * `acquireProvider` 那一套 —— 我们本来就**完全不用** ContentResolver。
     * 给父类一个正常的实例即可，比照抄一份用不上的隐藏 API 实现更稳。
     */
    override fun getContentResolver(): android.content.ContentResolver =
        super.getContentResolver()

    companion object {
        /** uid 2000 名下唯一的包名。系统用它核对"调用者是谁"。 */
        const val PACKAGE_NAME = "com.android.shell"

        /** root 身份下唯一能过校验的名字。 */
        const val ROOT_PACKAGE = "android"

        @Volatile
        private var instance: FakeContext? = null

        fun get(): FakeContext =
            instance ?: synchronized(this) {
                instance ?: FakeContext().also { instance = it }
            }
    }
}

/**
 * 拿一份「系统 Context」。
 *
 * `FakeContext` 的父类是 `ContextWrapper(系统 Context)` —— 需要它来提供
 * `getSystemService()` 等基础设施，但**不能**直接用它的包名（那是 `android`，
 * 过不了 uid 校验），所以包名由 `FakeContext` 覆写。
 *
 * ## 关键：不能走 `ActivityThread.systemMain()`
 *
 * `systemMain()` 内部会碰 Context 初始化，而 `FakeContext` 构造又需要系统
 * Context —— 形成环，实测反复重入（StackOverflow）。所以改由
 * [SystemThreadWorkarounds] **手工装配** ActivityThread，这是 scrcpy / MaaFwApp 的做法。
 *
 * ## 调用前提
 *
 * 所在线程必须先有 Looper；[SystemThreadWorkarounds] 会自己处理。
 */
internal object ShellContext {

    @Volatile
    private var cached: Context? = null

    fun get(): Context =
        cached ?: synchronized(this) { cached ?: create().also { cached = it } }

    private fun create(): Context {
        SystemThreadWorkarounds.apply()
        return SystemThreadWorkarounds.getSystemContext()
            ?: throw IllegalStateException("拿不到系统 Context（ActivityThread 装配失败）")
    }
}
