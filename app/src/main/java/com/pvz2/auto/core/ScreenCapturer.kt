package com.pvz2.auto.core

import android.os.ParcelFileDescriptor
import org.opencv.core.Mat

/**
 * Grabs one frame of the screen.
 *
 * Implementations block (shell round-trip or ImageReader poll), so they are only ever
 * called from the automation loop's `Dispatchers.IO` coroutine.
 *
 * Ownership: a non-null result belongs to the caller, who must `release()` it.
 */
fun interface ScreenCapturer {
    fun capture(): Mat?
}
