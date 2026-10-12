package com.pvz2.auto.fallback

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.pvz2.auto.core.ScreenCapturer
import com.pvz2.auto.core.toMatAndRecycle
import org.opencv.core.Mat

/**
 * Application-layer screenshot fallback, used when Shizuku is unavailable.
 *
 * The ordering is fixed by Android 14 and must not be rearranged:
 *   1. the UI already called `MediaProjectionManager.createScreenCaptureIntent()` and the
 *      user granted it (the result code + data are handed to [start]);
 *   2. a foreground service with `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` is running;
 *   3. only now may `getMediaProjection()` be called.
 *
 * A `MediaProjection.Callback` must be registered before `createVirtualDisplay()`,
 * otherwise the call throws on Android 14.
 */
class MediaProjectionCapturer(private val context: Context) : ScreenCapturer {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null

    private var width = 0
    private var height = 0

    fun start(resultCode: Int, data: Intent, width: Int, height: Int, densityDpi: Int) {
        release()
        this.width = width
        this.height = height

        val manager = context.getSystemService(MediaProjectionManager::class.java)
        val mediaProjection = manager.getMediaProjection(resultCode, data)

        mediaProjection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "media projection stopped by the system")
                    release()
                }
            },
            Handler(Looper.getMainLooper())
        )

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        mediaProjection.createVirtualDisplay(
            "pvz2-capture",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            null
        )

        reader = imageReader
        projection = mediaProjection
    }

    override fun capture(): Mat? {
        val imageReader = reader ?: return null

        // The virtual display needs a moment to produce its first frame, so retry briefly
        // rather than discarding the whole automation iteration.
        var image = imageReader.acquireLatestImage()
        var attempts = 0
        while (image == null && attempts < ACQUIRE_ATTEMPTS) {
            Thread.sleep(ACQUIRE_RETRY_MS)
            image = imageReader.acquireLatestImage()
            attempts++
        }
        if (image == null) return null

        return image.use { frame ->
            val plane = frame.planes[0]
            // Producers pad each row to a stride; the Bitmap must span the padding and then
            // be cropped back to the real width, otherwise the image shears.
            val rowPadding = plane.rowStride - plane.pixelStride * width

            val padded = Bitmap.createBitmap(
                width + rowPadding / plane.pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            padded.copyPixelsFromBuffer(plane.buffer)

            val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
            padded.recycle()
            cropped.toMatAndRecycle()
        }
    }

    fun release() {
        runCatching { virtualDisplay?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        virtualDisplay = null
        reader = null
        projection = null
    }

    private companion object {
        const val TAG = "MediaProjectionCapture"
        const val ACQUIRE_ATTEMPTS = 5
        const val ACQUIRE_RETRY_MS = 30L
    }
}
