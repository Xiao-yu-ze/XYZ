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
    }

    /** 取快照（按时间顺序）。 */
    fun snapshot(): List<String> = synchronized(ring) { ring.toList() }

    fun clear() = synchronized(ring) { ring.clear() }
}
