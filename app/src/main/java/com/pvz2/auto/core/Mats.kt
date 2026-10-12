package com.pvz2.auto.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.opencv.android.Utils
import org.opencv.core.Mat

/**
 * Bitmap -> Mat, transferring ownership of the Bitmap.
 *
 * Capture paths allocate one Bitmap per frame; recycling here instead of waiting for the GC
 * keeps peak memory flat over a long session.
 *
 * IMPORTANT: `Utils.bitmapToMat` produces CV_8UC4 for ARGB_8888 but CV_8UC3 for RGB_565.
 * Templates and frames must therefore both be decoded with [ARGB_8888] so that
 * `Imgproc.matchTemplate` receives two Mat objects of the same type.
 */
internal fun Bitmap.toMatAndRecycle(): Mat {
    val mat = Mat()
    Utils.bitmapToMat(this, mat)
    recycle()
    return mat
}

/** Decode options that pin every PNG to CV_8UC4 after conversion. */
internal fun argb8888Options(): BitmapFactory.Options =
    BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
