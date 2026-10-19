package com.pvz2.auto.shizuku

import android.view.Surface
import java.nio.ByteBuffer

/**
 * NDK 层采集端的 Java 门面。
 *
 * ## 为什么需要它
 *
 * Java 的 `ImageReader` / `SurfaceTexture` 给的 Surface，其 buffer usage
 * 不满足 WMS 对「可信显示端」的要求。用它们建虚拟屏，屏会被标成 untrusted：
 * 窗口留在主屏、IME 策略设不上、注入全被丢。
 *
 * 必须用 NDK 的 `AImageReader_newWithUsage()`（带 `CPU_READ_OFTEN |
 * GPU_SAMPLED_IMAGE`）再 `ANativeWindow_toSurface()`，系统才认。
 * 实现见 `cpp/capture.cpp`。
 *
 * ## 用法
 *
 * ```
 * val surface = NativeCapture.setup(width, height) ?: 失败
 * createVirtualDisplay(..., surface, flags)   // 把它交给虚拟屏
 * ... 之后每帧：
 * val buf = ByteBuffer.allocateDirect(width*height*4)
 * NativeCapture.acquireFrame(buf, size)
 * ```
 */
internal object NativeCapture {

    /**
     * 建采集端并把它的 Surface 交出去。
     *
     * @return null 表示 native 层建失败（见 logcat 标签 Pvz2Capture）
     */
    fun setup(width: Int, height: Int): Surface? = runCatching {
        loadLibrary()
        nativeSetup(width, height)
    }.getOrNull()

    /** 释放采集端。可重复调用。 */
    fun release() {
        runCatching {
            loadLibrary()
            nativeRelease()
        }
    }

    /**
     * 取最新一帧到 [out]（RGBA_8888，逐行已裁掉 rowStride 补白）。
     *
     * @param out    直接缓冲（必须是 allocateDirect），容量 >= w*h*4
     * @param outSize 长度 2 的数组，成功后写入 [宽, 高]
     * @return true 表示取到并已拷贝
     */
    fun acquireFrame(out: ByteBuffer, outSize: IntArray): Boolean = runCatching {
        loadLibrary()
        nativeAcquireFrame(out, outSize)
    }.getOrDefault(false)

    /** 只加载一次；`System.loadLibrary` 自己幂等，这里加个标记只为少一次 JNI 查表。 */
    @Volatile
    private var loaded = false

    private fun loadLibrary() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            System.loadLibrary("pvz2capture")
            loaded = true
        }
    }

    /**
     * 当前是否有一帧可取，返回字节数（width*height*4）；没有则 0。
     *
     * 先问大小再读，避免没帧时白分配 3.7 MB。
     */
    fun frameSize(): Int = runCatching {
        loadLibrary()
        nativeFrameSize()
    }.getOrDefault(0)

    // ---- native 声明（实现在 cpp/capture.cpp）----

    private external fun nativeSetup(width: Int, height: Int): Surface?

    private external fun nativeRelease()

    private external fun nativeAcquireFrame(out: ByteBuffer, outSize: IntArray): Boolean

    private external fun nativeFrameSize(): Int
}
