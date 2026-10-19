package com.pvz2.auto.shizuku

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.util.Log
import android.view.Surface
import com.pvz2.auto.core.toMatAndRecycle
import org.opencv.core.Mat

/**
 * 虚拟屏的**帧消费端**：一块 ImageReader，把它的 Surface 交给特权侧当虚拟屏的输出。
 *
 * ## 相比 `screencap -p` 好在哪
 *
 * 现在的采集路径是 `screencap -p` → PNG 字节流（1~3 MB）→ 经 Binder 管道回传 →
 * App 里解码成 Bitmap。一帧要经历 PNG 编码 + 传输 + PNG 解码三次搬运。
 *
 * 虚拟屏这条路是：游戏直接渲染进这块 Surface → GPU 出帧到 ImageReader 的
 * GraphicBuffer → 我们只做一次 RGBA→Bitmap→Mat 的拷贝。**没有编码、没有文件、
 * 没有 Binder 大块传输**，而且天然是"最新一帧"，不会积压。
 *
 * ## 为什么不用 `setOnImageAvailableListener` 回调
 *
 * 回调要一个带 Looper 的 Handler，等于为了"拿一帧"额外引入一个线程和一个生命周期
 * 要管的对象。而我们的使用模式永远是**同步地问"现在有帧吗"**（自动化循环是一步
 * 一步走的），所以直接在 [awaitFrame] 里用 `acquireLatestImage()` 轮询更简单：
 * 无线程、无回调、无竞态，超时语义也直白。
 *
 * `acquireLatestImage()` 本身会丢掉积压的旧帧，正是我们要的语义 —— 自动化要的是
 * "此刻屏幕长什么样"，不是"按顺序处理所有历史帧"。
 */
class VirtualDisplaySurface(
    val width: Int,
    val height: Int,
    /** 缓冲几帧。3 足够跟上 30fps 且不会让延迟拖长。 */
    maxImages: Int = 3,
) : AutoCloseable {

    private val reader: ImageReader =
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, maxImages)

    /**
     * 交给特权侧 `createVirtualDisplay(...)` 的那个 surface。
     *
     * ⚠️ **注意：这个 surface 现在是"显示端"，不是"采集端"。**
     *
     * 曾经直接把它当采集端传进去，结果那块屏的画面全被 ImageReader 吸走，
     * 没有任何东西负责"显示"—— 表现就是窗口不往上挂、渲染行为异常。
     *
     * 正确做法（照 ShadowAuto）：建屏时传一个**真正的输出 surface**
     * （见 [com.pvz2.auto.shizuku.VirtualDisplaySink]），截图时才用
     * `VirtualDisplay.setSurface()` 临时切到 ImageReader，取完立刻切回去。
     */
    val surface: Surface get() = reader.surface

    /** 已经成功转换出的帧数，用于区分"没帧"和"帧转换失败"。 */
    @Volatile
    var convertedFrames: Int = 0
        private set

    /**
     * 等一帧并转成 [Mat]。
     *
     * @return 720p 画面；超时或转换失败返回 null。**调用方负责 release() 返回的 Mat。**
     */
    fun awaitFrame(timeoutMs: Long = 3_000L): Mat? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val image = runCatching { reader.acquireLatestImage() }.getOrNull()
            if (image == null) {
                // 还没出帧。10ms 是"不空转得太凶"和"不丢掉反应速度"的折中。
                Thread.sleep(POLL_INTERVAL_MS)
                continue
            }
            try {
                val mat = image.toMatOrNull() ?: continue
                convertedFrames++
                return mat
            } finally {
                // Image 必须显式 close，否则缓冲区不归还，很快就会不再产帧。
                runCatching { image.close() }
            }
        }
        Log.w(TAG, "等待首帧超时：${timeoutMs}ms 内没有可用帧（已转换 $convertedFrames 帧）")
        return null
    }

    /**
     * Image → Mat。
     *
     * 两个坑：
     * ① **行跨距(rowStride)通常大于 `宽度 × 像素跨距`**，GPU 会按对齐补齐。直接
     *    `copyPixelsFromBuffer` 会把整块（含补白）塞进 Bitmap 导致画面斜切，
     *    所以先按补齐后的宽度建图，再裁掉右边那一条。
     * ② RGBA_8888 在内存里就是 R,G,B,A 顺序，和 Android `Bitmap.Config.ARGB_8888`
     *    的字节序一致，所以能直接拷贝；反过来若用 RGB_565 就会和模板的
     *    CV_8UC4 不一致，`matchTemplate` 会直接抛类型错。
     */
    private fun Image.toMatOrNull(): Mat? {
        val plane = planes.firstOrNull() ?: return null
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride <= 0 || rowStride <= 0) return null

        val w = width
        val h = height
        if (w <= 0 || h <= 0) return null

        val rowPaddingBytes = rowStride - pixelStride * w
        // 补白不一定是整数个像素，向上取整后再建图，最后按 w 裁掉。
        val paddedWidth = w + (rowPaddingBytes + pixelStride - 1) / pixelStride

        return try {
            val padded = Bitmap.createBitmap(paddedWidth, h, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            val cropped = if (paddedWidth != w) {
                Bitmap.createBitmap(padded, 0, 0, w, h).also { padded.recycle() }
            } else {
                padded
            }
            cropped.toMatAndRecycle()
        } catch (t: Throwable) {
            Log.w(TAG, "帧转换失败", t)
            null
        }
    }

    override fun close() {
        runCatching { reader.close() }
    }

    private companion object {
        const val TAG = "VirtualDisplaySurface"
        const val POLL_INTERVAL_MS = 10L
    }
}
