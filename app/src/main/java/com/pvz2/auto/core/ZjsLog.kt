package com.pvz2.auto.core

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 应用内的日志环形缓冲。
 *
 * ## 为什么不能只靠 logcat
 *
 * 实测：真机上系统的热限流/合成器日志每秒好几条，
 * `logcat` 的缓冲区几十秒就把我们的行冲掉了 ——
 * 「导出日志」导出来的东西里根本没有脚本那部分，
 * 而用户恰恰是要拿它来报问题的。
 *
 * 所以脚本日志在这里自己留一份（默认 3000 行，够看一次完整运行）。
 * logcat 照样写，方便连着电脑时用。
 */
object ZjsLog {

    private const val CAPACITY = 3000
    private const val TAG = "ZjsLog"
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val ring = ArrayDeque<String>(CAPACITY)

    /** 写一行：logcat + 环形缓冲。 */
    fun add(line: String) {
        val stamped = "${fmt.format(Date())}  $line"
        Log.i(TAG, line)
        synchronized(ring) {
            ring.addLast(stamped)
            while (ring.size > CAPACITY) ring.removeFirst()
        }
        // 同时落盘：文件才是"能发给别人"的东西（写失败不影响运行）
        runCatching {
            runWriter?.apply {
                write("[")
                write(fullFmt.format(Date()))
                write("] ")
                write(line)
                newLine()
                flush()
            }
        }
    }

    /** 取快照（按时间顺序）。 */
    fun snapshot(): List<String> = synchronized(ring) { ring.toList() }

    // ------------------------------------------------------------ 落盘
    //
    // 环形缓冲只在内存里，进程一死就没了。用户要"把这次运行发给我"时
    // 需要一份文件 —— 参照 参考管线：一次运行一个文件，失败帧单独存。

    private val fullFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var runFile: java.io.File? = null
    private var runWriter: java.io.BufferedWriter? = null

    /** 日志根目录（Service 启动时把外部 files 目录设进来）。 */
    @Volatile
    var baseDir: java.io.File = java.io.File("/data/local/tmp")

    /** `<baseDir>/zjs_logs`。 */
    fun runsDir(): java.io.File = java.io.File(baseDir, "zjs_logs").apply { mkdirs() }

    /** 失败截图目录，对应 参考管线 的 `debug/on_error`。 */
    fun shotDir(): java.io.File = java.io.File(runsDir(), "on_error").apply { mkdirs() }

    /** 当前/最近一次运行的日志文件。 */
    fun lastRunFile(): java.io.File? = runFile

    /** 开始一次运行：建文件并接上写入。 */
    fun beginRun(scriptName: String) {
        endRun()
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val safe = scriptName.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(40)
        // 先清到 9 份：马上就要再建一份，最终正好 10 份
        pruneOldRuns(keep = 9)
        val f = java.io.File(runsDir(), "run-$ts" + (if (safe.isBlank()) "" else "_$safe") + ".log")
        runCatching {
            runWriter = f.bufferedWriter(Charsets.UTF_8)
            runFile = f
            runWriter?.apply {
                write("# PVZ2 自动化 脚本运行日志")
                newLine()
                write("# 脚本: $scriptName")
                newLine()
                write("# 开始: ${fullFmt.format(Date())}")
                newLine()
                flush()
            }
        }
    }

    /**
     * 只保留最近 [keep] 份运行日志（含失败截图目录里的图）。
     *
     * 一次运行一个文件、失败还存图，跑一晚上就能堆出几百 MB ——
     * 手机上没人会去清，最后把存储占满。保留最近 10 次够回看问题了。
     */
    fun pruneOldRuns(keep: Int = 10) {
        runCatching {
            val logs = runsDir().listFiles { f -> f.isFile && f.name.endsWith(".log") }
                ?.sortedByDescending { it.lastModified() } ?: return
            logs.drop(keep).forEach { it.delete() }
            // 截图同理，按时间保留最近的
            val shots = shotDir().listFiles { f -> f.isFile }?.sortedByDescending { it.lastModified() }
            shots?.drop(keep * 3)?.forEach { it.delete() }
        }
    }

    /** 结束这次运行。 */
    fun endRun(summary: String? = null) {
        runCatching {
            runWriter?.apply {
                if (summary != null) {
                    write("# 结束: $summary")
                    newLine()
                }
                flush()
                close()
            }
        }
        runWriter = null
    }

    fun clear() = synchronized(ring) { ring.clear() }
}
