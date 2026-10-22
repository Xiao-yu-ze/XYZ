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
 * offset 0    : int32  state[3]          每块缓冲的状态
 * offset 12   : int32  readerCount[3]    正在读的读者数
 * offset 24   : int64  frameCount        写成功的总帧数
 * offset 32   : int32  width
 * offset 36   : int32  height
 * offset 40   : int32  released          1 = 采集端已释放
 * offset 44   : int32  _pad
 * offset 48   : 像素数据（3 × w*h*4，RGBA_8888）
 * ```
 *
 * 改一边必须改另一边，两边都写了同一份注释。
 *
 * ## 为什么读端不阻塞写端
 *
 * 读的时候把状态从 READY 抢成 READING，写者看到它就会绕开这块缓冲。
 * 三块缓冲轮转 —— 一块在写、一块刚写完、一块空闲，读者永远有得读，
 * **写者永远不必等读者**。这一点是帧率的关键：读者的慢不该传染给生产者。
 *
 * 读完立刻置回 READY（而不是 FREE）：万一没有更新的帧，下一轮还能读到同一块，
 * 不会出现"刚读完就什么都读不到"的空窗。
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
        private const val OFF_FRAME_COUNT = 24
        private const val OFF_WIDTH = 32
        private const val OFF_HEIGHT = 36
        private const val OFF_RELEASED = 40

        private const val READY = 2
        private const val READING = 3

        /** 打开（必要时创建）共享帧环文件并定长。 */
        fun open(path: String, width: Int, height: Int): FrameRing {
            val f = File(path)
            // 目录由服务端（shell）先建好并放开权限。App 能建就建，
            // 建不了也不致命 —— 文件已经存在、可打开就行。
            runCatching { f.parentFile?.mkdirs() }

            val need = HEADER_BYTES.toLong() + BUFFER_COUNT.toLong() * width * height * 4
            val raf = RandomAccessFile(f, "rw")
            if (raf.length() < need) raf.setLength(need)

            // 让对面（shell 进程）能打开。App 建的文件默认只有自己可读写。
            runCatching {
                f.setReadable(true, false)
                f.setWritable(true, false)
            }.onFailure { Log.w(TAG, "设置共享文件权限失败", it) }

            val fd = raf.fd
            Log.i(TAG, "共享帧环已打开 $path（$need 字节）fd=$fd")
            return FrameRing(raf, fd, width, height)
        }
    }

    private val map: ByteBuffer = file.channel
        .map(FileChannel.MapMode.READ_WRITE, 0, file.length())
        .order(ByteOrder.nativeOrder())

    /** 给 native 用的原始 fd（native 会自己 dup）。 */
    val fd: java.io.FileDescriptor get() = fdField

    val frameBytes: Int = width * height * 4

    /** 已写入的总帧数，单调递增。 */
    fun frameCount(): Long = map.getLong(OFF_FRAME_COUNT)

    /** 采集端是否已释放。 */
    fun released(): Boolean = map.getInt(OFF_RELEASED) != 0

    private fun state(index: Int): Int = map.getInt(OFF_STATE + index * 4)

    private fun setState(index: Int, v: Int) = map.putInt(OFF_STATE + index * 4, v)

    private fun bufferOffset(index: Int): Int = HEADER_BYTES + index * frameBytes

    /**
     * 把**最新一帧**拷进 [dst]。
     *
     * @param dst 容量必须 >= [frameBytes]
     * @return true = 拷到了一帧；false = 没有可用的帧（刚建屏、正在写、已释放）
     */
    fun readLatest(dst: ByteBuffer): Boolean {
        if (released()) return false
        if (map.getInt(OFF_WIDTH) != width || map.getInt(OFF_HEIGHT) != height) return false

        // 找一块 READY 的。三块轮转，最多两块同时 READY，取第一块即可 ——
        // 两块都只是"最近"的，差异只有一帧，而识别本来就容差一帧。
        var chosen = -1
        for (i in 0 until BUFFER_COUNT) {
            if (state(i) == READY) {
                chosen = i
                break
            }
        }
        if (chosen < 0) return false

        // 抢成 READING。抢不到说明写者刚把它拿去重写，这一轮放弃。
        setState(chosen, READING)
        if (state(chosen) != READING) return false

        try {
            val base = bufferOffset(chosen)
            dst.clear()
            // 用 position+get 而不是 duplicate：duplicate() 每帧新建对象，
            // 且返回类型不保证是 MappedByteBuffer。
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
            // 放回 READY：让写者能用它，下一轮也还能读到这一帧。
            setState(chosen, READY)
        }
    }

    fun close() {
        runCatching { file.close() }
    }
}
