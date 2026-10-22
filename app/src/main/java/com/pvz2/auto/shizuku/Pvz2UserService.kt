package com.pvz2.auto.shizuku

import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
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
     * ## 为什么是 shell 命令而不是 injectInputEvent
     *
     * 真机自检的四路对比（四路同时打，结果才可判读）：
     *
     *   A  injectInputEvent + display 65 : ✗ 被拒
     *   B  injectInputEvent + display 0  : ✗ 被拒   ← 连主屏都拒
     *   C  input -d 65 tap               : ✓ 接受
     *   D  input tap                     : ✓ 接受
     *   shell 的 INJECT_EVENTS            : granted=true
     *
     * 权限有、副屏对，**连 display 0 都注入失败** —— 问题在
     * `injectInputEvent` 那条反射路径本身，不在屏或权限。
     * 而 `input` 是系统自己的客户端，走官方路径，已验证可用。
     *
     * 代价是每次点击 fork 一个进程（约 5~15ms）。相比"零 fork 但永远失败"，
     * 这个交换是明确划算的。
     */
    override fun tapOnDisplay(displayId: Int, x: Int, y: Int, holdMs: Int): Boolean {
        val out = runCatching {
            runShell("input -d $displayId tap $x $y")
        }.getOrElse {
            Log.e(TAG, "input tap 执行失败 display=$displayId ($x,$y)", it)
            return false
        }
        // `input` 失败时往 stderr 写东西（已合并进 stdout），退出码仍是 0 ——
        // 只能看输出内容。空输出 = 成功。
        if (out.isNotBlank()) {
            Log.w(TAG, "input tap 输出 display=$displayId ($x,$y)：${out.trim().take(200)}")
            return false
        }
        return true
    }

    /**
     * 在指定 display 上滑动。
     *
     * 与点击走同一条 `input -d` 路径 —— 自检已确认它有 `-d` 且能落到副屏上。
     *
     * `input` 失败时往 stderr 写东西、退出码仍是 0，所以判据是**输出为空**。
     * 之前这里是无条件 `return true`，滑动静默失败而上层毫无察觉。
     */
    override fun swipeOnDisplay(
        displayId: Int,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int
    ): Boolean = InputInjector.swipe(displayId, x1, y1, x2, y2, durationMs)

    override fun exec(command: String): String =
        runCatching { runShell(command) }.getOrElse { "ERROR: ${it.message}" }

    // ------------------------------------------------- 任务与显示（本进程执行）
    //
    // 这几件事放在这里而不是 App 里，原因是**隐藏 API 限制**：
    // App 进程反射 `IActivityTaskManager$Stub.asInterface` 会抛
    // `NoSuchMethodException: ...asInterface [interface android.os.IBinder]`
    // —— 不是权限问题，是隐藏 API 被从反射结果里滤掉了。
    // 而 app_process（本进程）不受该限制。详见 IPvz2Service 里这几个方法的注释。

    override fun moveTaskToDisplay(packageName: String, displayId: Int): Int =
        DisplayTaskMover.moveToDisplay(packageName, displayId)

    override fun getTaskDisplayId(packageName: String): Int =
        DisplayTaskMover.taskDisplayId(packageName)

    override fun dumpTasks(): String = DisplayTaskMover.dumpTasks()

    override fun launchAppOnDisplay(packageName: String, displayId: Int): Boolean =
        DisplayAppLauncher.launchOnDisplay(packageName, displayId)

    /**
     * 抓 logcat。
     *
     * 本进程是 shell(uid 2000)，能读全部日志 —— 这一点普通应用做不到
     * （Android 4.1 起只允许读自己进程的）。排查虚拟屏问题时必须要看
     * system_server 侧那些 `Permission Denial` / `Display device added`。
     */
    override fun grabLogcat(tagFilter: String, maxLines: Int): String {
        val filter = tagFilter.trim()
        // 先取全量再在本地截断：logcat 的 -t 是"最后 N 行"，但配合 grep 时
        // 过滤后的行数会少于 N，容易漏掉关键日志。这里取多一点再截。
        val take = maxLines.coerceIn(50, 20_000)
        val command = if (filter.isEmpty()) {
            "logcat -d -v brief -t $take"
        } else {
            // 用单引号包住过滤式，避免 shell 解释其中的特殊字符。
            "logcat -d -v brief -t 8000 | grep -E '$filter' | tail -n $take"
        }
        return runCatching { runShell(command) }
            .getOrElse { "ERROR: ${it.message}" }
    }

    /**
     * 一块屏的 flags 摘要。
     *
     * 只认 `dumpsys display` 里 `DisplayDeviceInfo{...}` 那一段 —— 它是唯一
     * 权威来源。`mViewports` 段里的 `displayId` 在某些 ROM 上会报错值（见过报 0），
     * 拿它判断会得出完全相反的结论。
     */
    override fun describeDisplay(displayId: Int): String {
        val dump = runCatching { runShell("dumpsys display") }.getOrDefault("")
        if (dump.isEmpty()) return "ERROR: dumpsys display 无输出"

        // 按 `DisplayDeviceInfo{...}` 逐个解析，**不靠 mDisplayId 定位**。
        //
        // 之前的做法是「往上找最近的 mDisplayId」来配对，但那一段的排版在
        // Android 16 上变了 —— 结果永远匹配不上，日志里一直是
        // 「display=47 未找到」，而系统明明报了 FLAG_TRUSTED。
        //
        // 更可靠的做法：每个 DisplayDeviceInfo 里都带 uniqueId，而我们的屏
        // 名固定是 VD_NAME，直接按名字找。
        val blocks = dump.split("DisplayDeviceInfo{")
        for (block in blocks.drop(1)) {
            val head = block.substringBefore("}\n")
            if (!head.contains(VIRTUAL_DISPLAY_NAME)) continue

            val trusted = head.contains("FLAG_TRUSTED")
            val autoMirror = head.contains("FLAG_AUTO_MIRROR")
            val private = head.contains("FLAG_PRIVATE")
            val type = Regex("type ([A-Z]+)").find(head)?.groupValues?.get(1) ?: "?"
            val owner = Regex("owner ([^,\\s]+)").find(head)?.groupValues?.get(1) ?: "?"
            val size = Regex("(\\d+) x (\\d+)").find(head)
                ?.let { "${it.groupValues[1]}x${it.groupValues[2]}" } ?: "?"

            // AUTO_MIRROR 是"镜像屏"的标志 —— 有它就无法承载 Activity，
            // launchDisplayId 会完全不起作用。这一行值得单独点出来。
            val verdict = when {
                autoMirror -> "镜像屏（应用挂不上去）"
                private -> "私有屏（别的 uid 访问不到）"
                trusted -> "可承载应用 ✓"
                else -> "无 TRUSTED（可能挂不上）"
            }
            return buildString {
                append("display=$displayId type=$type size=$size trusted=$trusted ")
                append("autoMirror=$autoMirror private=$private owner=$owner ")
                append("判定=$verdict")
            }
        }
        return "display=$displayId 未找到（屏可能没建成或已释放）"
    }

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

    /** 采集缓冲。会话期内复用，见 acquireFrameFd 的注释。 */
    private var frameBuffer: java.nio.ByteBuffer? = null
    private var frameBufferSize = 0

    /** 写文件用的中转块。每帧新建 64KB 也没必要。 */
    private val writeChunk = ByteArray(64 * 1024)

    private fun ensureFrameBuffer(size: Int): java.nio.ByteBuffer? {
        if (size <= 0) return null
        val cur = frameBuffer
        if (cur != null && frameBufferSize == size) return cur
        frameBuffer = java.nio.ByteBuffer.allocateDirect(size)
        frameBufferSize = size
        return frameBuffer
    }

    /** 采集缓冲是堆外内存，必须显式断开引用让 Cleaner 回收。 */
    private fun releaseFrameBuffer() {
        frameBuffer = null
        frameBufferSize = 0
    }

    /** 采集尺寸。建屏时记下，取帧按它分配缓冲。 */
    @Volatile
    private var captureWidth = 0
    @Volatile
    private var captureHeight = 0

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
                // 关键的一行：没有它，后面 new Handler 与 Looper 相关初始化都会抛。
                Looper.prepare()
                // 走手工装配而不是 systemMain()：systemMain 内部会碰 Context 初始化，
                // 而 FakeContext 构造又需要系统 Context —— 直接 systemMain 会形成环、
                // 反复重入直到 StackOverflow（实测过）。
                // 这里用 scrcpy/MaaFwApp 的装配方式，见 SystemThreadWorkarounds。
                SystemThreadWorkarounds.apply()
                result = SystemThreadWorkarounds.getSystemContext()
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
    @Synchronized
    override fun createVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface?,
        flags: Int
    ): Int = doCreate(name, width, height, dpi, surface, flags, 0f)

    /**
     * 带刷新率建屏。
     *
     * 游戏在虚拟屏里的帧率**由虚拟屏刷新率决定** —— App 读
     * `Display.getRefreshRate()` 来决定渲染节奏。所以"把游戏限制到 30/60/120 帧"
     * 在这块屏上就等于"把虚拟屏刷新率设成 30/60/120"。
     *
     * @param refreshRate <= 0 表示用系统默认。
     */
    override fun createVirtualDisplayWithRate(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface?,
        flags: Int,
        refreshRate: Float
    ): Int = doCreate(name, width, height, dpi, surface, flags, refreshRate)

    private fun doCreate(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface?,
        flags: Int,
        refreshRate: Float
    ): Int {
        // ★ 只放屏，**不能**放采集端。
        //
        //   createDisplayWithCapture 会先 NativeCapture.setup() 拿到 surface，
        //   再进来建屏。如果这里放采集端，就会把刚建好、正要用的那块 surface
        //   连同 AImageReader 一起销毁 —— 随后拿一块已经没了的 Surface 去
        //   createVirtualDisplay，native 层直接 SIGSEGV，UserService 进程崩溃，
        //   App 侧只看到 DeadObjectException。
        //
        //   这正是把 NativeCapture.release() 移出提前 return 之后踩到的坑。
        releaseDisplay()

        if (surface == null || !surface.isValid) {
            Log.e(TAG, "建虚拟屏失败：传进来的 Surface 无效")
            return -1
        }

        // ★ 用 FakeContext，**不能**用系统 Context，也不能用只有包名的裸 ContextWrapper。
        //
        //   两个检查都要过：
        //   1. validatePackageName(callingUid, packageName) —— 包名必须挂在 uid 2000 名下
        //      系统 Context 报 "android"（uid 1000），直接 SecurityException；
        //   2. canProjectVideo() —— 建 TRUSTED 屏时会查 CAPTURE_VIDEO_OUTPUT，
        //      shell 身份没有这个权限。FakeContext 把 checkCallingPermission()
        //      重写成恒返回 GRANTED，这一关才过得去。
        //
        //   只满足 1 的 CallerContext 会让屏变成「无 TRUSTED」→ 窗口挂不上、
        //   IME 设不上、注入全被丢。这是整条链最关键的一处。
        val context: Context = FakeContext.get()

        Log.i(
            TAG,
            "建虚拟屏 name=$name ${width}x$height @${dpi}dpi " +
                "uid=${Process.myUid()} package=${context.packageName} " +
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
                flags = flags,
                refreshRate = refreshRate
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
            // 只放屏。
            //
            // 这个方法可能被 createDisplayWithCapture 调（此时采集端归它管），
            // 也可能被 AIDL 单独调（此时没有采集端）。用 releaseVirtualDisplay()
            // 会把别人会话的采集端一起拆掉，所以这里只碰屏。
            // 采集端的清理由 createDisplayWithCapture 的调用方或 AIDL
            // releaseVirtualDisplay 负责。
            releaseDisplay()
            -1
        }
    }

    // ------------------------------------------------- 建屏 + 采集（同进程）

    /**
     * 建采集端并**同时**建虚拟屏。
     *
     * ## 为什么必须一次调用完成两件事
     *
     * 虚拟屏的 `AImageReader` 只能在**创建它的进程**里读。之前把采集端放在
     * App 进程、建屏放在本进程，两边各自持有一个 `g_capturer`：
     * 屏接的是本进程那块 surface，App 的 `acquireFrame` 读的是它自己那块空的 ——
     * 于是 App 里的预览永远是黑的。用户看到的就是「软件里那块屏一直没反应」。
     *
     * 所以：在本进程建 reader、拿 surface、建屏，三者天然一致。
     *
     * ## 为什么整个方法要加锁
     *
     * 这些是 AIDL 方法，跑在 **Binder 线程池**（不止一个线程）。建屏本身
     * 要花一两百毫秒，用户完全可能在这期间又点一次启动，于是两个线程同时
     * 进来：
     *   T1: releaseVirtualDisplay() → setup() 拿到 reader A
     *   T2: releaseVirtualDisplay() → 把 reader A 删了 → setup() 拿到 reader B
     *   T1: 拿**已销毁的** surface 去建屏 → native SIGSEGV
     *
     * 这和真机上刚踩到的那次崩溃是**同一个签名**，而且 native 侧的 mutex
     * 拦不住 —— 竞争发生在 Kotlin 层（谁先调 release、谁先调 setup）。
     * 所以建屏 / 释放 / 单独建屏这三个入口必须串行化。
     */
    @Synchronized
    override fun createDisplayWithCapture(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        flags: Int
    ): Int {
        // 这是"重建整条链路"，所以屏和采集端都要先清干净。
        releaseVirtualDisplay()

        // ★ 共享区必须**先建好**再让 native 映射。
        //   App 侧负责建文件（它是客户），这里只把 fd 交给 native。
        //   真正的共享区文件由 App 在 VirtualDisplaySession 里创建并传路径进来，
        //   所以这里用 pendingFramePath 里那个路径打开。
        val path = pendingFramePath
        if (path.isNullOrBlank()) {
            Log.e(TAG, "createDisplayWithCapture: 没有指定共享区路径")
            return -1
        }
        val raf = runCatching { java.io.RandomAccessFile(java.io.File(path), "rw") }.getOrNull()
        if (raf == null) {
            Log.e(TAG, "createDisplayWithCapture: 打不开共享区 $path")
            return -1
        }

        // native 会自己 dup 一份 fd，所以这里关掉 raf 不影响它。
        val surface = NativeCapture.setup(width, height, raf.fd)
        runCatching { raf.close() }
        if (surface == null) {
            Log.e(TAG, "native 采集端创建失败")
            return -1
        }
        captureWidth = width
        captureHeight = height
        Log.i(TAG, "事件驱动采集已就绪：${width}x$height，共享区 $path")
        return doCreate(name, width, height, dpi, surface, flags, pendingRefreshRate)
    }

    /** 下一次建屏用的刷新率；<= 0 = 系统默认。 */
    @Volatile
    private var pendingRefreshRate = 0f

    /** 下一次建屏要用的共享区文件路径（App 侧已建好并放开权限）。 */
    @Volatile
    private var pendingFramePath: String? = null

    override fun setPendingFramePath(path: String?) {
        pendingFramePath = path
        Log.i(TAG, "下次建屏使用共享帧环：$path")
    }

    /**
     * 建共享帧环文件并放开权限。**必须由特权侧做。**
     *
     * `/data/local/tmp` 属于 shell，App 进程建不了目录 —— 真机上直接报
     * `ENOENT`。所以顺序必须是：服务端 mkdir + 定长 + chmod → 把路径给 App →
     * App 打开同一个文件做映射 → 建屏时 native 再 mmap 一次。
     *
     * 长度：头 48 字节 + 3 块 × w × h × 4。与 capture.cpp / FrameRing 一致。
     */
    override fun prepareFrameRing(width: Int, height: Int): String? {
        if (width <= 0 || height <= 0) return null
        return runCatching {
            val dir = java.io.File(FRAME_DIR)
            dir.mkdirs()
            dir.setReadable(true, false)
            dir.setExecutable(true, false)
            dir.setWritable(true, false)

            val file = java.io.File(dir, "frame.ring")
            val need = 48L + 3L * width * height * 4
            java.io.RandomAccessFile(file, "rw").use { raf ->
                if (raf.length() != need) raf.setLength(need)
            }
            file.setReadable(true, false)
            file.setWritable(true, false)
            Log.i(TAG, "共享帧环文件就绪：${file.absolutePath}（$need 字节）")
            file.absolutePath
        }.onFailure { Log.e(TAG, "prepareFrameRing 失败", it) }.getOrNull()
    }

    override fun setPendingRefreshRate(refreshRate: Float) {
        pendingRefreshRate = refreshRate
        Log.i(TAG, "下次建屏刷新率设为 ${if (refreshRate <= 0f) "系统默认" else "${refreshRate}Hz"}")
    }

    // ---------------------------------------------------------- 流式手势

    /**
     * 手势状态机。
     *
     * 一次拖动会产生几十个 MOVE。如果 App 每个 MOVE 都传完整状态（downTime、
     * 上一个坐标）过来，光是序列化就够把手指动作拖成阶梯。所以状态留在这边，
     * App 只传坐标。
     */
    private val gesture = GestureInjector()

    override fun gestureDown(displayId: Int, x: Float, y: Float): Boolean =
        gesture.start(displayId, x, y)

    override fun gestureMove(x: Float, y: Float) = gesture.moveTo(x, y)

    override fun gestureUp(x: Float, y: Float) = gesture.finish(x, y)

    override fun gestureCancel() = gesture.cancel()

    private class GestureInjector {

        private var active: InputInjector.Gesture? = null
        private var lastX = 0f
        private var lastY = 0f

        fun start(displayId: Int, x: Float, y: Float): Boolean {
            lastX = x
            lastY = y
            active = runCatching { InputInjector.beginGesture(displayId, x, y) }
                .onFailure { Log.e(TAG, "手势开始失败", it) }
                .getOrNull()
            return active != null
        }

        fun moveTo(x: Float, y: Float) {
            val g = active ?: return
            // 亚像素抖动过滤：手指"按住"时总在抖，每次都发会塞满输入队列。
            if (kotlin.math.abs(x - lastX) < 0.8f && kotlin.math.abs(y - lastY) < 0.8f) return
            lastX = x
            lastY = y
            g.moveTo(x, y)
        }

        fun finish(x: Float, y: Float) {
            val g = active ?: return
            active = null
            g.finish(x, y)
        }

        fun cancel() {
            val g = active ?: return
            active = null
            g.cancel()
        }
    }


    /**
     * 取一帧写进共享缓冲。
     *
     * 用 seqlock：写前把序号改成奇数，写完改回偶数。App 读到奇数说明正在写，
     * 丢掉这一帧等下一次 —— 比加锁便宜得多，而且不会让写端等读端。
     */
    @Volatile
    private var frameDirReady = false

    private fun ensureDirReadable(dir: File) {
        if (frameDirReady) return
        runCatching {
            // 0777：让 App 进程（不同 uid）能进入目录并读文件。
            // 目录里只有帧文件，不含任何敏感信息。
            dir.setExecutable(true, false)
            dir.setReadable(true, false)
            dir.setWritable(true, false)
        }
        frameDirReady = true
    }

    /** 采集端状态，一行摘要。导出日志时看这一行判断"到底有没有帧"。 */
    override fun captureStatus(): String =
        if (captureWidth > 0 && captureHeight > 0) {
            "采集端就绪 ${captureWidth}x$captureHeight（每帧 ${captureWidth * captureHeight * 4} 字节）"
        } else {
            "未建采集端"
        }

    /**
     * 只释放**屏**（虚拟显示 + 它用的那块 Surface），不碰采集端。
     *
     * 之所以必须拆开：`createDisplayWithCapture` 的流程是
     * 「先建采集端拿 surface → 再建屏」，而建屏函数开头要先清掉上一块屏。
     * 如果那里顺手把采集端也放了，就会销毁刚建好、正要用的 surface，
     * 随后用一块已销毁的 Surface 建屏 → native SIGSEGV，
     * UserService 整个进程崩掉，App 只看到 DeadObjectException。
     */
    private fun releaseDisplay() {
        val display = virtualDisplay
        val surface = virtualDisplaySurface
        val id = virtualDisplayId
        virtualDisplay = null
        virtualDisplaySurface = null
        virtualDisplayId = -1

        // ★ 屏和 Surface 要**各自判断**，不能用 `if (display == null) return`。
        //
        //   display 为 null 但 surface 不为 null 是真实存在的状态：
        //   采集端 setup 成功、建屏失败，此时 virtualDisplaySurface 已经被
        //   赋了值（createVirtualDisplay 里 L419-421 才一起写，但异常路径上
        //   可能只写了一半）。提前 return 会让那块 Surface 没人释放。
        if (display != null) {
            // 顺序要紧：先释放屏，再释放 Surface。反过来的话屏还挂在一块已经
            // 没了的 buffer 队列上，某些 ROM 会直接崩在 native 层。
            runCatching { display.javaClass.getMethod("release").invoke(display) }
                .onFailure { Log.w(TAG, "释放虚拟屏失败", it) }
        }
        runCatching { surface?.release() }
        if (display != null) Log.i(TAG, "虚拟屏已释放 displayId=$id")
    }

    /** 只释放**采集端**（native AImageReader + window）与堆外采集缓冲。 */
    private fun releaseCapturer() {
        runCatching { NativeCapture.release() }
        runCatching { releaseFrameBuffer() }
        // ★ 尺寸也要清。
        //
        //   否则建屏失败后 captureStatus() 还报"采集端就绪 1280x720"，
        //   而 native 那边 g_capturer 已经是 nullptr —— 导出日志时会看到
        //   一条彻底误导人的诊断。更糟的是 acquireFrameFd 会继续按旧尺寸
        //   走，拿不到帧还每帧白分配 3.7MB 堆外内存。
        captureWidth = 0
        captureHeight = 0
    }

    /**
     * AIDL 的释放入口：屏和采集端一起收。
     *
     * 注意**不能**用 `if (display == null) return` 提前退出 ——
     * 建屏失败时 virtualDisplay 恰好是 null，而采集端可能已经建好了
     * （setup 成功、建屏失败）。那种情况下提前 return 会把 reader 和
     * 3 个 GraphicBuffer 一直泄漏到进程退出。
     */
    @Synchronized
    override fun releaseVirtualDisplay() {
        releaseDisplay()
        releaseCapturer()
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

        /** 共享帧缓冲的头长度：序号(4) + 保留(12)，对齐 16 方便 mmap。 */
        const val FRAME_HEADER_BYTES = 16
        val SIZE_PATTERN = Regex("""\d+x\d+""")

        /**
         * 帧文件目录。
         *
         * `/data/local/tmp` 是 shell 身份可写、且 App 进程也读得到的位置 ——
         * 用它是为了绕开"跨进程传 3.7 MB 一帧"的问题（Binder 单次上限 1 MB）。
         */
        const val FRAME_DIR = "/data/local/tmp/pvz2"

        /** 虚拟屏名字。dumpsys 里靠它认出我们自己那块屏（displayId 配对不可靠）。 */
        const val VIRTUAL_DISPLAY_NAME = "pvz2-virtual"

        /** 初始化系统 Context 的等待上限。正常是毫秒级，5 秒只是防死等。 */
        const val CONTEXT_TIMEOUT_SEC = 5L

        /** adb shell 进程的 uid。createVirtualDisplay 的包名校验要用到它。 */
        const val SHELL_UID = 2000

        /** uid 2000 名下唯一（且各 Android 版本都一致）的包名。 */
        const val SHELL_PACKAGE = "com.android.shell"
    }
}
