package com.pvz2.auto.shizuku

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import com.pvz2.auto.core.ScreenCapturer
import com.pvz2.auto.core.TouchInjector
import com.pvz2.auto.core.argb8888Options
import com.pvz2.auto.core.toMatAndRecycle
import org.opencv.core.Mat

/**
 * Screenshot through the privileged `screencap -p`, decoded straight from the returned
 * descriptor so no temporary file is ever written and nothing has to be cleaned up.
 */
class ShizukuScreenCapturer(
    private val client: ShizukuUserServiceClient
) : ScreenCapturer {

    override fun capture(): Mat? {
        val service = client.peek() ?: return null
        val descriptor = runCatching { service.screencap() }.getOrNull() ?: return null

        // AutoCloseInputStream owns the descriptor, so the read end of the pipe is closed
        // even when decoding throws - without this the remote copy-writer could block.
        val bitmap: Bitmap? = runCatching {
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
                BitmapFactory.decodeStream(stream, null, argb8888Options())
            }
        }.getOrNull()

        return bitmap?.toMatAndRecycle()
    }
}

/** Touch injection through the privileged `input` command. */
class ShizukuTouchInjector(
    private val client: ShizukuUserServiceClient
) : TouchInjector {

    override fun tap(x: Float, y: Float) {
        val service = client.peek() ?: return
        runCatching { service.tap(x.toInt(), y.toInt()) }
    }

    override fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val service = client.peek() ?: return
        runCatching { service.swipe(x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt(), durationMs.toInt()) }
    }
}
