package com.pvz2.auto.shizuku

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Display
import android.view.Surface
import com.pvz2.auto.IPvz2Service

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
     * 官方给特权进程的做法是先 `ActivityThread.systemMain()` 拿到一个系统侧的
     * ActivityThread，再 `getSystemContext()` —— scrcpy 的 `FakeContext`、
     * MaaFwApp 的 `DisplayManager` 包装都是这条路。
     *
     * 只初始化一次并缓存：`systemMain()` 有副作用（会准备主线程 Looper），
     * 反复调用没有意义还可能踩到状态机。
     */
    private val systemContext: Context? by lazy {
        runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val systemMain = activityThreadClass
                .getDeclaredMethod("systemMain")
                .apply { isAccessible = true }
            val thread = systemMain.invoke(null)
            val getSystemContext = activityThreadClass
                .getDeclaredMethod("getSystemContext")
                .apply { isAccessible = true }
            getSystemContext.invoke(thread) as? Context
        }.onFailure { Log.e(TAG, "取系统 Context 失败，虚拟屏建不出来", it) }
            .getOrNull()
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
        val context = systemContext ?: return -1

        Log.i(TAG, "建虚拟屏 name=$name ${width}x$height @${dpi}dpi " +
            "flags=${VirtualDisplayFlags.describe(flags)}")

        return try {
            val displayManagerClass = Class.forName("android.hardware.display.DisplayManager")
            val displayManager = displayManagerClass
                .getDeclaredConstructor(Context::class.java)
                .apply { isAccessible = true }
                .newInstance(context)

            val create = displayManagerClass
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
            val created = create.invoke(displayManager, name, width, height, dpi, surface, flags)
                ?: run {
                    Log.e(TAG, "createVirtualDisplay 返回 null（多半是 flags 或权限被拒）")
                    return -1
                }

            val display = created.javaClass
                .getMethod("getDisplay")
                .apply { isAccessible = true }
                .invoke(created) as? Display

            val id = display?.displayId ?: -1
            // 显式把 null 也纳入这个失败分支：这样下面才能智能转换成非空 Display。
            if (display == null || id <= 0) {
                Log.e(TAG, "虚拟屏建出来了但拿不到有效 displayId：$id")
                runCatching { created.javaClass.getMethod("release").invoke(created) }
                return -1
            }

            virtualDisplay = created
            virtualDisplaySurface = surface
            virtualDisplayId = id
            // 用 mode 而不是 Display.getWidth()/getHeight()：后者从 API 17 起就废弃了。
            val mode = display.mode
            Log.i(
                TAG,
                "虚拟屏就绪 displayId=$id，有效显示尺寸 ${mode.physicalWidth}x${mode.physicalHeight}"
            )
            id
        } catch (t: Throwable) {
            // 常见原因：SecurityException（flags 里混进了 AUTO_MIRROR，或缺少 TRUSTED）、
            // NoSuchMethodException（ROM 改了签名）。把原始异常全打出来，真机排查全靠它。
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
    }
}
