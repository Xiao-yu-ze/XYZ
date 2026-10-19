package com.pvz2.auto.shizuku

import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import android.view.Surface
import com.pvz2.auto.IPvz2Service
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The privileged side of the app.
 *
 * Shizuku instantiates this class inside its own `app_process`, running with shell
 * (uid 2000) or root (uid 0) identity, and drives it through `IPvz2Service`. It is
 * therefore **not** an `android.app.Service`, is never declared in the manifest, and must
 * not touch framework APIs that need a real application Context.
 *
 * It must also keep a public no-argument constructor: that is what Shizuku calls.
 */
class Pvz2UserService : IPvz2Service.Stub() {

    /** Read end of the most recent screenshot pipe, closed on the next call / on destroy. */
    @Volatile
    private var previousCaptureRead: ParcelFileDescriptor? = null

    override fun screencap(): ParcelFileDescriptor {
        // The Binder transaction that handed the previous descriptor to the caller has
        // already completed (and duplicated the fd), so dropping our copy is safe and keeps
        // the process from accumulating one descriptor per frame.
        closePreviousCapture()

        val pipe = ParcelFileDescriptor.createPipe()
        val read = pipe[0]
        val write = pipe[1]
        previousCaptureRead = read

        // Copying must happen off this Binder thread: `screencap` emits ~1-3 MB while a pipe
        // buffer holds only 64 KB, so an inline copy would block here and deadlock the
        // caller before it ever gets the descriptor back.
        Thread({
            try {
                // `2>/dev/null` matters - the child's stderr is a pipe nobody drains, and a
                // chatty failure would otherwise block it forever.
                val process = ProcessBuilder("sh", "-c", "screencap -p 2>/dev/null").start()
                ParcelFileDescriptor.AutoCloseOutputStream(write).use { output ->
                    process.inputStream.use { input -> input.copyTo(output) }
                }
                process.waitFor()
            } catch (_: Throwable) {
                // The pipe just closes early; the caller fails to decode and skips the frame.
            } finally {
                runCatching { write.close() }
            }
        }, "pvz2-screencap").start()

        return read
    }

    override fun tap(x: Int, y: Int) {
        runCatching { runShell("input tap $x $y") }
    }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
        runCatching { runShell("input swipe $x1 $y1 $x2 $y2 $durationMs") }
    }

    // ------------------------------------------------------- 定向注入（副屏）

    /**
     * 在指定 display 上点一下。
     *
     * 实现放在 [DisplayInputInjector] 里，与诊断工具 [VdProbeMain] 共用同一份逻辑 ——
     * 诊断工具如果和线上走的不是同一条代码路径，它验出来的结论就不算数。
     */
    override fun tapOnDisplay(displayId: Int, x: Int, y: Int, holdMs: Int): Boolean =
        DisplayInputInjector.tap(displayId, x, y, holdMs)

    /**
     * 在指定 display 上滑动。
     *
     * 这条用 shell 的 `input -d <displayId> swipe ...` 而不是自己拼 MOVE 序列：
     * `input` 命令本身就支持 `-d`，而手写 MOVE 序列要自己算插值和时间戳，
     * 收益不大、出错面更大。等以后真需要精细控制（压感/多点）再换。
     */
    override fun swipeOnDisplay(
        displayId: Int,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int
    ): Boolean = runCatching {
        val output = runShell("input -d $displayId swipe $x1 $y1 $x2 $y2 $durationMs")
        // `input` 失败时会往 stderr 写东西（已合并进 stdout）而不是返回非零，只能看输出。
        if (output.isNotBlank()) {
            Log.w(TAG, "swipeOnDisplay 输出：$output")
        }
        true
    }.getOrElse {
        Log.e(TAG, "swipeOnDisplay 失败", it)
        false
    }

    override fun exec(command: String): String =
        runCatching { runShell(command) }.getOrElse { "ERROR: ${it.message}" }

    override fun getDisplaySize(): String {
        // `wm size` prints "Physical size: 1080x2340" and, when an override exists,
        // "Override size: 720x1560". The override is the effective size, so take the last.
        val output = runCatching { runShell("wm size") }.getOrDefault("")
        return SIZE_PATTERN.findAll(output).lastOrNull()?.value.orEmpty()
    }

    // ------------------------------------------------------------ 虚拟屏

    /** `android.hardware.display.VirtualDisplay` 实例。它是 @hide 类，只能按 Any 持有。 */
    @Volatile
    private var virtualDisplay: Any? = null

    /** 建屏时用的那块 Surface（Binder 传进来的副本），释放屏时一并归还。 */
    @Volatile
    private var virtualDisplaySurface: Surface? = null

    @Volatile
    private var virtualDisplayId: Int = -1

    /**
     * 系统 Context 的缓存。
     *
     * `DisplayManager` 的构造函数要一个 Context，而我们在 app_process 里没有应用 Context。
     * 特权进程的标准做法是先 `ActivityThread.systemMain()` 拿到一个系统侧 ActivityThread，
     * 再 `getSystemContext()`。
     *
     * ## ★ 这里踩过一个坑，别再踩回去
     *
     * `systemMain()` 内部会 `new ActivityThread.H()`，而 **Handler 必须在已经
     * `Looper.prepare()` 过的线程上构造**。Binder 回调线程没有 Looper，直接调会抛：
     *
     * ```
     * RuntimeException: Can't create handler inside thread Thread[Binder:xxx] that has
     * not called Looper.prepare()
     *     at android.app.ActivityThread.systemMain(ActivityThread.java:7842)
     * ```
     *
     * 所以初始化必须挪到一个自己 prepare 过 Looper 的专用线程上跑。我们只要 Context
     * 这个对象本身，不需要那个 Looper 派发消息，所以线程拿到 Context 就可以退出。
     */
    @Volatile
    private var cachedSystemContext: Context? = null

    /** 已经尝试过且失败 —— 避免每次建屏都重试一遍同样的失败。 */
    @Volatile
    private var systemContextAttempted = false

    private fun systemContext(): Context? {
        cachedSystemContext?.let { return it }
        synchronized(this) {
            cachedSystemContext?.let { return it }
            if (systemContextAttempted) return null
            systemContextAttempted = true
            cachedSystemContext = runCatching { createSystemContextOnLooperThread() }
                .onFailure { Log.e(TAG, "取系统 Context 失败，虚拟屏建不出来", it) }
                .getOrNull()
            return cachedSystemContext
        }
    }

    private fun createSystemContextOnLooperThread(): Context? {
        val latch = CountDownLatch(1)
        var result: Context? = null
        var failure: Throwable? = null

        val worker = Thread({
            try {
                // 关键的一行：没有它，systemMain() 里 new Handler 会抛。
                Looper.prepare()
                val activityThreadClass = Class.forName("android.app.ActivityThread")
                val activityThread = activityThreadClass
                    .getDeclaredMethod("systemMain")
                    .apply { isAccessible = true }
                    .invoke(null)
                result = activityThreadClass
                    .getDeclaredMethod("getSystemContext")
                    .apply { isAccessible = true }
                    .invoke(activityThread) as? Context
            } catch (t: Throwable) {
                failure = t
            } finally {
                latch.countDown()
            }
        }, "pvz2-system-context")
        worker.isDaemon = true
        worker.start()

        if (!latch.await(CONTEXT_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            Log.e(TAG, "取系统 Context 超时（${CONTEXT_TIMEOUT_SEC}s）")
            return null
        }
        failure?.let { throw it }
        return result
    }

    /**
     * 解析出「属于本进程 uid」的包名，供 `createVirtualDisplay` 的 packageName 校验使用。
     *
     * 两个来源：
     * 1. uid 2000 = adb shell 进程 → 固定 `com.android.shell`（各版本一致，不必查表）；
     * 2. 其它身份（含 root）→ 向 PackageManager 问这个 uid 名下有哪些包，取第一个。
     *
     * 返回空串表示「这个 uid 名下没有任何包」。**那种情况下无论传什么名字都会被拒**，
     * 所以调用方应当把原因讲清楚，而不是反复重试不同名字。
     */
    private fun resolvePackageNameForSelf(): String {
        val uid = Process.myUid()
        if (uid == SHELL_UID) return SHELL_PACKAGE

        val packages = runCatching {
            systemContext()?.packageManager?.getPackagesForUid(uid)
        }.getOrNull()
        return packages?.firstOrNull().orEmpty()
    }

    /**
     * 只为了给 `DisplayManager` 提供一个**包名正确**的 Context。
     *
     * 继承 `ContextWrapper(null)` 而不是实现整个 `Context`：后者有 40 多个抽象方法，
     * 而 `createVirtualDisplay` 这条路径只读包名（AOSP 里是
     * `DisplayManagerGlobal.createVirtualDisplay(..., context.getPackageName(), ...)`）。
     *
     * 其余方法一旦被调用会 NPE —— 这是**刻意的**：真被调用说明我的假设错了，
     * 应该立刻炸在明处，而不是静默返回假数据让错误漂到别处。
     */
    private class CallerContext(private val packageName: String) : ContextWrapper(null) {
        override fun getPackageName(): String = packageName
        override fun getOpPackageName(): String = packageName
    }

    /**
     * 建虚拟屏。见 [IPvz2Service.createVirtualDisplay] 的注释。
     *
     * 全程反射，因为 `createVirtualDisplay` 是 @hide。但这在 app_process 里没问题：
     * 隐藏 API 限制针对的是普通应用进程，而我们是 shell 身份起的 app_process。
     */
    override fun createVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface?,
        flags: Int
    ): Int {
        releaseVirtualDisplay()

        if (surface == null || !surface.isValid) {
            Log.e(TAG, "建虚拟屏失败：传进来的 Surface 无效")
            return -1
        }

        // ★ 这里只能用「包名属于本进程 uid」的 Context，不能用系统 Context。
        //   系统 Context 的 getPackageName() 是 "android"，而服务端会用
        //   validatePackageName(Binder.getCallingUid(), packageName) 校验
        //   「这个名字必须挂在调用者 uid 名下」，不匹配就抛
        //   SecurityException: packageName must match the calling uid。
        //   scrcpy 的 FakeContext 存在的唯一理由就是这件事。
        val packageName = resolvePackageNameForSelf()
        if (packageName.isEmpty()) {
            Log.e(
                TAG,
                "本进程 uid=${Process.myUid()} 名下没有任何包，无法通过 " +
                    "createVirtualDisplay 的 packageName 校验。" +
                    "根因：Shizuku 以 root 身份运行（uid 0），而 uid 0 不拥有任何包。" +
                    "解决：改用 ADB/无线调试方式启动 Shizuku，使 UserService 变成 uid 2000(shell)。"
            )
            return -1
        }
        val context: Context = CallerContext(packageName)

        Log.i(
            TAG,
            "建虚拟屏 name=$name ${width}x$height @${dpi}dpi " +
                "uid=${Process.myUid()} package=$packageName " +
                "flags=${VirtualDisplayFlags.describe(flags)}"
        )

        return try {
            val created = VirtualDisplayCreator.create(
                callerContext = context,
                name = name,
                width = width,
                height = height,
                dpi = dpi,
                surface = surface,
                flags = flags
            )

            virtualDisplay = created.virtualDisplay
            virtualDisplaySurface = surface
            virtualDisplayId = created.displayId

            // 用 mode 而不是 Display.getWidth()/getHeight()：后者从 API 17 起就废弃了。
            val mode = created.display.mode
            Log.i(
                TAG,
                "虚拟屏就绪 displayId=${created.displayId}，" +
                    "有效显示尺寸 ${mode.physicalWidth}x${mode.physicalHeight}"
            )
            created.displayId
        } catch (t: Throwable) {
            // 常见原因：SecurityException（packageName 与 uid 不匹配 / flags 里混进了
            // AUTO_MIRROR / 缺少 TRUSTED）、NoSuchMethodException（ROM 改了签名）。
            // 把原始异常全打出来 —— 真机排查全靠它。
            Log.e(TAG, "建虚拟屏抛异常", t)
            releaseVirtualDisplay()
            -1
        }
    }

    override fun releaseVirtualDisplay() {
        val display = virtualDisplay
        val surface = virtualDisplaySurface
        val id = virtualDisplayId
        virtualDisplay = null
        virtualDisplaySurface = null
        virtualDisplayId = -1

        if (display == null) return
        // 顺序要紧：先释放屏，再释放 Surface。反过来的话屏还挂在一块已经没了的
        // buffer 队列上，某些 ROM 会直接崩在 native 层。
        runCatching { display.javaClass.getMethod("release").invoke(display) }
            .onFailure { Log.w(TAG, "释放虚拟屏失败", it) }
        runCatching { surface?.release() }
        Log.i(TAG, "虚拟屏已释放 displayId=$id")
    }

    /** 当前虚拟屏的 displayId，没有则 -1。调试用。 */
    override fun getVirtualDisplayId(): Int = virtualDisplayId

    override fun destroy() {
        runCatching { releaseVirtualDisplay() }
        closePreviousCapture()
        System.exit(0)
    }

    /**
     * Runs a command through `sh -c`.
     *
     * `redirectErrorStream(true)` merges stderr into stdout so a command that writes to
     * stderr can never fill its pipe buffer and hang.
     */
    private fun runShell(command: String): String {
        val process = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        return output
    }

    private fun closePreviousCapture() {
        runCatching { previousCaptureRead?.close() }
        previousCaptureRead = null
    }

    private companion object {
        const val TAG = "Pvz2UserService"
        val SIZE_PATTERN = Regex("""\d+x\d+""")

        /** 初始化系统 Context 的等待上限。正常是毫秒级，5 秒只是防死等。 */
        const val CONTEXT_TIMEOUT_SEC = 5L

        /** adb shell 进程的 uid。createVirtualDisplay 的包名校验要用到它。 */
        const val SHELL_UID = 2000

        /** uid 2000 名下唯一（且各 Android 版本都一致）的包名。 */
        const val SHELL_PACKAGE = "com.android.shell"
    }
}
