package com.pvz2.auto.shizuku

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Build
import android.os.Looper
import android.os.Process
import android.view.Surface
import java.io.File
import java.io.FileOutputStream

/**
 * 建屏 + 搬应用的设备端自检，用 `app_process` 直跑 —— 不经过 App，也不经过 Shizuku。
 *
 * ## 为什么需要它
 *
 * 「建不出虚拟屏」有四个完全不同的可能原因，混在一起时谁也说不清：
 *
 *   1. 系统/ROM 不允许建；
 *   2. flags 用错（漏 PUBLIC、误带 AUTO_MIRROR）；
 *   3. 调用者身份不对（`packageName must match the calling uid`）；
 *   4. Shizuku 那一层（UserService 没起来、版本没提）。
 *
 * 这个入口把 1/2/3 单独测掉，剩下的就一定是 4。它跑在 `adb shell` 里，
 * 身份是 uid 2000(shell) —— 和真机上调 Shizuku 的生产路径完全一致。
 *
 * ## 用法
 *
 * ```powershell
 * $apk = (& adb shell pm path com.pvz2.auto).Replace("package:","").Trim()
 * # 只建屏：
 * adb shell "CLASSPATH=$apk app_process /system/bin com.pvz2.auto.shizuku.VdProbeMain"
 * # 建屏 + 把某个包搬上去 + 等画面：
 * adb shell "CLASSPATH=$apk app_process /system/bin com.pvz2.auto.shizuku.VdProbeMain com.popcap.pvz2cthdop"
 * ```
 *
 * 第二个参数存在时，它会：解析该包的 launcher activity → `am start --display <id>` →
 * 轮询帧并打印亮度统计。**亮度从 0 变成非 0，就说明游戏真的渲染到这块屏上了。**
 */
object VdProbeMain {

    private const val WIDTH = 1280
    private const val HEIGHT = 720
    private const val DPI = 240
    private const val SHELL_UID = 2000
    private const val SHELL_PACKAGE = "com.android.shell"

    @JvmStatic
    fun main(args: Array<String>) {
        // ImageReader 内部要一个 Handler，Handler 要求当前线程有 Looper。
        Looper.prepare()

        val uid = Process.myUid()
        println("=== VdProbeMain ===")
        println("uid=$uid  sdk=${Build.VERSION.SDK_INT}  ${WIDTH}x$HEIGHT @${DPI}dpi")

        if (uid != SHELL_UID) {
            println("!! 这个自检必须在 uid 2000(shell) 下跑，当前是 $uid。")
            println("   root(uid 0) 名下没有任何包，必然过不了 packageName 校验 —— 那是另一条路的问题。")
            System.exit(2)
        }

        // `--own-focus` 会额外带上 OWN_FOCUS 位。它的作用是让这块屏自己持有焦点，
        // 否则 InputDispatcher 会因为「这块屏没有可触摸窗口」而丢掉所有注入事件。
        val baseFlags = VirtualDisplayFlags.forSdk(Build.VERSION.SDK_INT)
        val wantOwnFocus = args.contains(ARG_OWN_FOCUS)
        val flags = if (wantOwnFocus) baseFlags or VirtualDisplayFlags.OWN_FOCUS else baseFlags
        println("flags=${VirtualDisplayFlags.describe(flags)}" + if (wantOwnFocus) "  (+OWN_FOCUS)" else "")

        val reader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 3)
        val surface: Surface = reader.surface
        println("surface.isValid=${surface.isValid}")

        var created: Any? = null
        var exitCode = 1
        try {
            val result = VirtualDisplayCreator.create(
                callerContext = CallerContext(SHELL_PACKAGE),
                name = "pvz2-vd-probe",
                width = WIDTH,
                height = HEIGHT,
                dpi = DPI,
                surface = surface,
                flags = flags
            )
            created = result.virtualDisplay
            val displayId = result.displayId
            println("建屏成功：displayId=$displayId")
            exitCode = 0

            // 等系统登记这块屏，避免紧接着的 am start 拿到「display 不存在」。
            Thread.sleep(800)

            val targetPackage = args.firstOrNull()
            if (targetPackage != null) {
                println("--- 尝试把 $targetPackage 搬到 display $displayId ---")
                movePackageToDisplay(targetPackage, displayId)
            }

            // 可选：在副屏上点一下，验证「定向注入」真的落到这块屏上。
            // 第二个参数是 --tap 时启用。这里同时试两条路，好把「事件构造不对」和
            // 「这块屏没有焦点」区分开：
            //   A. 我们自己构造 MotionEvent + injectInputEvent（带 displayId）
            //   B. shell 的 `input -d <id> tap`（系统自带的同一条能力）
            if (args.getOrNull(1) == "--tap") {
                println("--- 注入对照实验（display $displayId, 640,316）---")
                println("此刻屏还活着、任务也在副屏上，是最干净的一次机会。")

                // 连续重点几次：任务刚被搬过来，窗口要过一会儿才可触摸。
                var ourOk = false
                for (attempt in 1..TAP_RETRIES_COUNT) {
                    ourOk = DisplayInputInjector.tap(displayId, WIDTH / 2, HEIGHT * 44 / 100, holdMs = 80)
                    println("A) 第 $attempt 次自建注入 -> $ourOk")
                    if (ourOk) break
                    Thread.sleep(TAP_RETRY_INTERVAL_MS)
                }

                // 同一时刻、同一块屏，用 shell 的 input -d 再打一次做对照。
                val shellOut = run("input -d $displayId tap ${WIDTH / 2} ${HEIGHT * 44 / 100}")
                println("B) input -d $displayId tap -> ${shellOut.ifBlank { "（无输出即成功）" }}")

                Thread.sleep(500)
                println("此刻该 display 下的窗口：")
                run(
                    "dumpsys window windows | grep -E 'Window #|mDisplayId=$displayId |mDisplayId=$displayId\$' " +
                        "| head -12"
                )
                println("各 display 上的任务：")
                run("dumpsys activity activities | grep -E '^Display #' | head -8")
                println("输入分发日志：")
                run("logcat -d -t 40 | grep -iE 'InputDispatcher' | tail -6")
            }

            // 轮询帧：亮度 > 0 说明这块屏上真的画了东西。
            val deadline = System.currentTimeMillis() + FRAME_WAIT_MS
            var attempt = 0
            var best = 0.0
            var saved: String? = null
            while (System.currentTimeMillis() < deadline) {
                attempt++
                val bitmap = grabFrame(reader, 600) ?: continue
                val brightness = meanBrightness(bitmap)
                best = maxOf(best, brightness)
                if (brightness > 0.5 && saved == null) {
                    saved = savePng(bitmap, "/sdcard/pvz-vd-frame.png")
                    println("第 $attempt 次采样：亮度 %.2f，已存 %s".format(brightness, saved))
                } else {
                    println("第 $attempt 次采样：亮度 %.2f".format(brightness))
                }
                bitmap.recycle()
                // 已经确认有内容就直接结束，不必等到超时。
                if (best > 0.5) break
                Thread.sleep(500)
            }
            println("--- 结论：最高亮度 %.2f ---".format(best))
            if (best > 0.5) {
                println("屏上有内容渲染 ✅（帧已存 /sdcard/pvz-vd-frame.png）")
            } else {
                println("屏上全是黑的 ❌ —— 应用没有画到这块屏上，或还没开始渲染")
            }
        } catch (t: Throwable) {
            println("失败：${t::class.java.name}: ${t.message}")
            var cause: Throwable? = t.cause
            while (cause != null) {
                println("  caused by ${cause::class.java.name}: ${cause.message}")
                cause = cause.cause
            }
            t.printStackTrace(System.out)
        } finally {
            VirtualDisplayCreator.release(created)
            runCatching { surface.release() }
            runCatching { reader.close() }
        }
        System.exit(exitCode)
    }

    /**
     * 把包搬到指定 display，按「成功率从高到低」依次尝试，每一步都打印结果。
     *
     * 顺序的取舍：
     *  ① `am start --display` —— 官方入口，最不改游戏状态；但若该应用已在主屏有 task，
     *     系统可能只是把原 task 提到前台而不搬到副屏。
     *  ② `am display move-stack <taskId> <displayId>` —— 直接搬栈。**要真实 taskId**，
     *     传 0 会报 `Unknown rootTaskId=0`（这里踩过）。
     */
    private fun movePackageToDisplay(pkg: String, displayId: Int) {
        val component = resolveLauncherComponent(pkg)
        println("launcher activity: ${component ?: "（解析失败）"}")

        // ① 先清掉已有任务，确保待会儿是「全新启动」而不是把旧任务提到前台。
        run("am force-stop $pkg")
        Thread.sleep(800)

        // ② ★ 用 ActivityOptions.launchDisplayId 在**启动那一刻**就指定副屏。
        //    这是与「事后搬」的本质区别：`moveRootTaskToDisplay` 只改任务记录、
        //    不搬窗口，搬完副屏上仍然没有可触摸窗口，注入全被丢。
        val launched = DisplayAppLauncher.launchOnDisplay(pkg, displayId)
        println("launchOnDisplay -> $launched")
        Thread.sleep(3_000)

        // 关键一步：`am start --display` 并不保证任务真的落在副屏（实测会落回 Display #0），
        // 所以再用 IActivityTaskManager 搬一次。取法照 MaaFwApp：
        // ServiceManager.getService("activity_task") + IActivityTaskManager$Stub.asInterface。
        println("搬之前所有任务：")
        println(DisplayTaskMover.dumpTasks())

        val movedTaskId = DisplayTaskMover.moveToDisplay(pkg, displayId)
        println("反射搬移结果 taskId=$movedTaskId")
        Thread.sleep(1_500)

        println("搬之后所有任务：")
        println(DisplayTaskMover.dumpTasks())
        println("该包现在所在 display：${DisplayTaskMover.taskDisplayId(pkg)}（期望 $displayId）")
    }

    /**
     * 从 `dumpsys activity activities` 里找出该包的 taskId。
     *
     * 输出形如：
     * ```
     * * Task{aaf4cf4 #405 type=standard A=10051:com.popcap.pvz2cthdop U=0 ...}
     * ```
     * `#` 后面的数字就是 taskId。
     *
     * **别用 `dumpsys activity recents`**：它的标题里就有 `Recent #0`，
     * 在包名附近开窗口抓 `#数字` 会抓到 0（踩过，然后把 0 传给 move-stack，
     * 报 `Unknown rootTaskId=0`）。这里直接在 Task{...} 内部匹配「#数字 且同一对大括号
     * 里有这个包名」，不会串。
     */
    private fun findTaskId(pkg: String): Int? = runCatching {
        val text = execCapture("dumpsys activity activities")
        Regex("Task\\{[^}]*#(\\d+)[^}]*:${Regex.escape(pkg)}\\}")
            .find(text)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
    }.getOrNull()

    /** 跑命令但不回显（供内部解析用）。 */
    private fun execCapture(command: String): String = runCatching {
        val process = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        output
    }.getOrDefault("")

    /** `cmd package resolve-activity --brief -c android.intent.category.LAUNCHER <pkg>` 的最后一行是组件名。 */
    private fun resolveLauncherComponent(pkg: String): String? {
        val output = run(
            "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $pkg"
        )
        return output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .lastOrNull { it.contains('/') }
    }

    /** 跑一条 shell 命令并把输出回显（带 `$ ` 前缀，便于和别的输出区分）。 */
    private fun run(command: String): String {
        println("$ $command")
        return runCatching {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            output.trim().take(2000).also { if (it.isNotEmpty()) println(it) }
        }.getOrElse {
            println("命令执行失败：${it.message}")
            ""
        }
    }

    /**
     * 取一帧，超时返回 null。
     *
     * 全程只用 Bitmap，**不碰 OpenCV**：`app_process` 里没有 app 的 native 库搜索路径，
     * `System.loadLibrary("opencv_java4")` 会失败。这个探针必须零依赖才能可靠地当判据用。
     */
    private fun grabFrame(reader: ImageReader, timeoutMs: Long): Bitmap? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val image = runCatching { reader.acquireLatestImage() }.getOrNull()
            if (image == null) {
                Thread.sleep(30)
                continue
            }
            try {
                val plane = image.planes.firstOrNull() ?: return null
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val w = image.width
                val h = image.height
                val padding = rowStride - pixelStride * w
                val paddedWidth = w + (padding + pixelStride - 1) / pixelStride

                val padded = Bitmap.createBitmap(paddedWidth, h, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(plane.buffer)
                return if (paddedWidth != w) {
                    Bitmap.createBitmap(padded, 0, 0, w, h).also { padded.recycle() }
                } else {
                    padded
                }
            } finally {
                runCatching { image.close() }
            }
        }
        return null
    }

    /** 采样求平均亮度（0~255）。全黑是 0。 */
    private fun meanBrightness(bitmap: Bitmap): Double {
        var sum = 0.0
        var count = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val c = bitmap.getPixel(x, y)
                sum += (android.graphics.Color.red(c) + android.graphics.Color.green(c) +
                    android.graphics.Color.blue(c)) / 3.0
                count++
                x += 16
            }
            y += 16
        }
        return if (count == 0) 0.0 else sum / count
    }

    private fun savePng(bitmap: Bitmap, path: String): String? = runCatching {
        FileOutputStream(File(path)).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        path
    }.getOrNull()

    /** 只为提供正确的包名；其它方法不该被调用（调用就会 NPE，暴露假设错误）。 */
    private class CallerContext(private val packageName: String) : ContextWrapper(null) {
        override fun getPackageName(): String = packageName
        override fun getOpPackageName(): String = packageName
    }

    private const val FRAME_WAIT_MS = 12_000L
    private const val ARG_OWN_FOCUS = "--own-focus"

    /** 注入重试次数与间隔：等游戏从加载态进入可交互态。 */
    private const val TAP_RETRIES_COUNT = 6
    private const val TAP_RETRY_INTERVAL_MS = 2_500L
}
