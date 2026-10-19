package com.pvz2.auto.service

import android.view.Surface
import com.pvz2.auto.shizuku.NativeCapture
import java.nio.ByteBuffer
import org.opencv.core.CvType
import org.opencv.core.Mat

/**
 * 虚拟屏的采集端（NDK 版）。
 *
 * ## 为什么不是 Java 的 ImageReader
 *
 * Java 的 `ImageReader.getSurface()` / `SurfaceTexture` 给的 Surface，buffer usage
 * 不满足 WMS 对「可信显示端」的要求，用它建出来的虚拟屏会被标成 untrusted：
 * 窗口留在主屏、IME 策略设不上、注入全被丢。
 *
 * NDK 的 `AImageReader_newWithUsage(..., CPU_READ_OFTEN | GPU_SAMPLED_IMAGE)` +
 * `ANativeWindow_toSurface()` 才被系统当成正常显示端。实现见 `cpp/capture.cpp`。
 *
 * ## 与旧实现的关键区别
 *
 * 旧实现把 ImageReader 的 surface 直接交给虚拟屏当**显示端**，
 * 结果那块屏的画面全被吸走、没有东西"显示"它。
 * 现在这块 surface 是**采集端**，默认不占用；需要取帧时临时切换。
 */
internal class NativeCaptureSource(val width: Int, val height: Int) : AutoCloseable {

    /** 交给虚拟屏的采集端 surface；null 表示 native 层建失败。 */
    val surface: Surface? = NativeCapture.setup(width, height)

    /** 复用的直接缓冲：每帧重新分配会给 GC 很大压力。 */
    private val buffer: ByteBuffer = ByteBuffer.allocateDirect(width * height * 4)

    private val size = IntArray(2)

    /** 成功取到的帧数，用于区分"没帧"和"转换失败"。 */
    @Volatile
    var frames: Int = 0
        private set

    /**
     * 取最新一帧并转成 [Mat]（BGR，与项目其余部分一致）。
     *
     * @return null 表示这次没取到（正常，屏上可能还没内容）
     */
    fun acquireFrame(): Mat? {
        if (surface == null) return null

        buffer.clear()
        if (!NativeCapture.acquireFrame(buffer, size)) return null

        val w = size[0]
        val h = size[1]
        if (w <= 0 || h <= 0) return null

        buffer.position(0)
        buffer.limit(w * h * 4)

        // Mat.put(offset, array) 只吃 byte[]，而直接缓冲没有 backing array，
        // 所以先拷到一个复用的 byte[]，再喂给 Mat。多一次拷贝，但省掉
        // 每帧 new Mat(h, w, ...) 的分配 —— 后者在高频取帧时代价更大。
        if (scratch.size < w * h * 4) {
            scratch = ByteArray(w * h * 4)
        }
        buffer.get(scratch, 0, w * h * 4)

        // native 侧已按 rowStride 裁过补白，拿到的是紧密排列的 RGBA。
        val rgba = Mat(h, w, CvType.CV_8UC4)
        rgba.put(0, 0, scratch)

        val bgr = Mat()
        org.opencv.imgproc.Imgproc.cvtColor(rgba, bgr, org.opencv.imgproc.Imgproc.COLOR_RGBA2BGR)
        rgba.release()
        frames++
        return bgr
    }

    /** 复用缓冲，避免每帧分配。 */
    private var scratch = ByteArray(0)

    override fun close() {
        NativeCapture.release()
    }
}
