package com.pvz2.auto.shizuku

import android.view.Surface

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
 *
 * ## 取帧是事件驱动的，不再有 acquireFrame
 *
 * 以前是定时轮询 `acquireFrame`。而虚拟屏生产 120fps、我们只取 30 次/秒，
 * ImageReader 队列永远满，SurfaceFlinger 在"取空闲缓冲"那步被阻塞，
 * 合成线程卡死 → system_server 连锁超时 → 平板重启。
 *
 * 现在 native 侧注册了 `AImageReader_setImageListener`，**来一帧就在回调里
 * 取走一帧**，写进共享区的三缓冲轮转区。Java 侧只从共享区读最新帧，
 * 完全不参与"取"这个动作，也就永远不会把生产者堵住。
 *
 * 用法：
 * ```
 * val surface = NativeCapture.setup(w, h, ring.fd)   // 交出 Surface 和共享区
 * createVirtualDisplay(..., surface, flags)
 * ... 之后随时：
 * ring.readLatest(dst)                                // 从共享区拷最新帧
 * ```
 */
internal object NativeCapture {

    /**
     * 建采集端并把它的 Surface 交出去。
     *
     * @param sharedFd 共享区文件 fd（见 [FrameRing]），由 native 侧 mmap
     * @return null 表示 native 层建失败（见 logcat 标签 Pvz2Capture）
     */
    fun setup(width: Int, height: Int, sharedFd: java.io.FileDescriptor): Surface? = runCatching {
        loadLibrary()
        nativeSetup(width, height, sharedFd)
    }.getOrNull()

    /** 释放采集端。可重复调用。 */
    fun release() {
        runCatching {
            loadLibrary()
            nativeRelease()
        }
    }

    /** 因三块缓冲全被占而丢掉的帧数。调试用，正常应一直是 0。 */
    fun droppedFrames(): Long = runCatching {
        loadLibrary()
        nativeDroppedFrames()
    }.getOrDefault(0L)

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

    // ---- native 声明（实现在 cpp/capture.cpp）----

    private external fun nativeSetup(width: Int, height: Int, sharedFd: java.io.FileDescriptor): Surface?

    private external fun nativeRelease()

    private external fun nativeDroppedFrames(): Long
}
