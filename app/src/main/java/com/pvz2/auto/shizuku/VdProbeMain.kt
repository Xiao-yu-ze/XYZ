package com.pvz2.auto.shizuku

import android.content.Context
import android.content.ContextWrapper
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Build
import android.os.Looper
import android.os.Process
import android.view.Surface

/**
 * 建屏自检 —— 用 `app_process` 直接在设备上跑，不经过 App、也不经过 Shizuku。
 *
 * ## 为什么需要它
 *
 * 「建不出虚拟屏」有四个完全不同的可能原因，混在一起时谁也说不清：
 *
 *   1. 系统/ROM 不允许建（`config_supportsMultiDisplay` 之类）；
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
 * adb shell "CLASSPATH=$apk app_process /system/bin com.pvz2.auto.shizuku.VdProbeMain"
 * ```
 *
 * 成功会打印 `displayId=...`，失败会把原始异常打出来。
 */
object VdProbeMain {

    private const val WIDTH = 1280
    private const val HEIGHT = 720
    private const val DPI = 240

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

        val flags = VirtualDisplayFlags.forSdk(Build.VERSION.SDK_INT)
        println("flags=${VirtualDisplayFlags.describe(flags)}")

        // 用 ImageReader 当消费端 —— 和 App 里的做法完全一致，只差一个回调。
        val reader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 2)
        val surface: Surface = reader.surface
        println("surface.isValid=${surface.isValid}")

        var created: Any? = null
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
            println("建屏成功：displayId=${result.displayId}")

            // 等一下，让系统把屏登记进 DisplayManager，方便外面用 dumpsys 复查。
            Thread.sleep(1_500)
            println("完成（屏会在退出时释放）")
        } catch (t: Throwable) {
            println("建屏失败：${t::class.java.name}: ${t.message}")
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
        System.exit(if (created == null) 1 else 0)
    }

    /** 只为提供正确的包名；其它方法不该被调用（调用就会 NPE，暴露假设错误）。 */
    private class CallerContext(private val packageName: String) : ContextWrapper(null) {
        override fun getPackageName(): String = packageName
        override fun getOpPackageName(): String = packageName
    }

    private const val SHELL_UID = 2000
    private const val SHELL_PACKAGE = "com.android.shell"
}
