package com.pvz2.auto.core

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * 共享帧环的读端。
 *
 * ## 布局必须与 `cpp/capture.cpp` 逐字节一致
 *
 * ```
 * offset 0    : int32  state[3]       每块缓冲的状态（0 空闲 / 1 正在写 / 2 可读 / 3 正在读）
 * offset 12   : int32  bufferSeq[3]   每块缓冲里装的是第几帧
 * offset 24   : int64  frameCount     写成功的总帧数
 * offset 32   : int32  width
 * offset 36   : int32  height
 * offset 40   : int32  released       1 = 采集端已释放
 * offset 44   : int32  _pad
 * offset 48   : 像素数据（3 × w*h*4，RGBA_8888）
 * ```
 *
 * 改一边必须改另一边，两边都写了同一份注释。
 *
 * ## 为什么要挑"帧号最大"的那块，而不是第一块
 *
 * 三块轮转时写者拿不到空闲缓冲，会抢一块可读的覆盖。所以"第一块 READY"
 * 很可能正是它刚动过的那块 —— 读它会拿到写了一半的帧：画面撕裂、发黑，
 * 而状态行一切正常（帧号在涨、帧率正常）。
 *
 * 这类问题的特征就是**界面全黑但数据看着都正常**，所以读端必须带帧号校验。
 *
 * ## 为什么读端不阻塞写端
 *
 * 读的时候把状态从"可读"抢成"正在读"，写者看到它就绕开。
 * **写者永远不必等读者** —— 这一点是帧率的关键。
 */
class FrameRing(
    private val file: RandomAccessFile,
    private val fdField: java.io.FileDescriptor,
    val width: Int,
    val height: Int
) {

    companion object {
        private const val TAG = "FrameRing"

        private const val BUFFER_COUNT = 3
        private const val HEADER_BYTES = 48
        private const val OFF_STATE = 0
        private const val OFF_BUFFER_SEQ = 12
        private const val OFF_FRAME_COUNT = 24
        private const val OFF_WIDTH = 32
        private const val OFF_HEIGHT = 36
        private const val OFF_RELEASED = 40

        private const val READY = 2
        private const val READING = 3

        /** 打开（必要时创建）共享帧环文件并定长。 */
        fun open(path: String, width: Int, height: Int): FrameRing {
            val f = File(path)
            runCatching { f.parentFile?.mkdirs() }

            val need = HEADER_BYTES.toLong() + BUFFER_COUNT.toLong() * width * height * 4
            val raf = RandomAccessFile(f, "rw")
            if (raf.length() < need) raf.setLength(need)

            // 让另一个进程（shell）能打开。
            runCatching {
                f.setReadable(true, false)
                f.setWritable(true, false)
            }.onFailure { Log.w(TAG, "设置共享文件权限失败", it) }

            Log.i(TAG, "共享帧环已打开 $path（$need 字节）")
            return FrameRing(raf, raf.fd, width, height)
        }
    }

    private val map: ByteBuffer = file.channel
        .map(FileChannel.MapMode.READ_WRITE, 0, file.length())
        .order(ByteOrder.nativeOrder())

    /** 给 native 用的 fd（native 会自己 dup）。 */
    val fd: java.io.FileDescriptor get() = fdField

    val frameBytes: Int = width * height * 4

    /** 已写入的总帧数，单调递增。 */
    fun frameCount(): Long = map.getLong(OFF_FRAME_COUNT)

    /** 采集端是否已释放。 */
    fun released(): Boolean = map.getInt(OFF_RELEASED) != 0

    private fun state(index: Int): Int = map.getInt(OFF_STATE + index * 4)

    private fun setState(index: Int, v: Int) = map.putInt(OFF_STATE + index * 4, v)

    /** 这块缓冲里装的是第几帧。 */
    private fun seqAt(index: Int): Int = map.getInt(OFF_BUFFER_SEQ + index * 4)

    private fun bufferOffset(index: Int): Int = HEADER_BYTES + index * frameBytes

    /**
     * 把**最新一帧**拷进 [dst]。
     *
     * @param dst 容量必须 >= [frameBytes]
     * @return true = 拷到了一帧；false = 没有可用的帧
     */
    fun readLatest(dst: ByteBuffer): Boolean {
        if (released()) return false
        if (map.getInt(OFF_WIDTH) != width || map.getInt(OFF_HEIGHT) != height) return false

        // 挑帧号最大的那块可读缓冲。理由见类注释。
        var best = -1
        var bestSeq = Int.MIN_VALUE
        for (i in 0 until BUFFER_COUNT) {
            if (state(i) != READY) continue
            val seq = seqAt(i)
            if (seq > bestSeq) {
                bestSeq = seq
                best = i
            }
        }
        if (best < 0) return false

        // 抢成"正在读"。抢不到说明写者刚好把它拿去重写了，这一轮放弃。
        setState(best, READING)
        if (state(best) != READING) return false

        try {
            // 再确认帧号没变：从判断到抢占之间写者可能已经动过它。
            if (seqAt(best) != bestSeq) return false

            val base = bufferOffset(best)
            dst.clear()
            val chunk = ByteArray(frameBytes)
            map.position(base)
            map.get(chunk, 0, frameBytes)
            dst.put(chunk)
            dst.flip()
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "拷帧失败", t)
            return false
        } finally {
            // 放回"可读"：让写者能用它，下一轮也还能读到这一帧。
            setState(best, READY)
        }
    }

    fun close() {
        runCatching { file.close() }
    }
}
