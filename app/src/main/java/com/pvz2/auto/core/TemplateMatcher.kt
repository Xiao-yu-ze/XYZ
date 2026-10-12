package com.pvz2.auto.core

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** A successful template hit, reported in 720p logical coordinates. */
data class MatchResult(
    val template: String,
    val score: Double,
    val logicalX: Float,
    val logicalY: Float
)

/**
 * Loads 720p PNG templates from `assets/templates` once, rescales them to the current
 * screen, and locates them in captured frames with `TM_CCOEFF_NORMED`.
 *
 * Not thread safe by design: it is owned by the single automation-loop coroutine, so no
 * locking is needed and no OpenCV work ever happens on the main thread.
 */
class TemplateMatcher(
    private val defaultThreshold: Double = DEFAULT_THRESHOLD,
    private val assetsDir: String = DEFAULT_ASSETS_DIR
) {
    /** Templates exactly as authored, i.e. in the 1280x720 logical space. */
    private val originals = LinkedHashMap<String, Mat>()

    /** The same templates resized for the screen currently in [adapter]. */
    private var scaled = LinkedHashMap<String, Mat>()

    /** Null until the first frame tells us the real screen size. */
    var adapter: ScreenAdapter? = null
        private set

    val templateNames: List<String> get() = originals.keys.toList()

    val loadedCount: Int get() = originals.size

    /**
     * Reads every `*.png` under `assets/<assetsDir>` and keeps the 720p original.
     * The scaled copies are produced later by [setScreen], once the frame size is known.
     */
    fun load(context: Context) {
        release()
        val names = runCatching { context.assets.list(assetsDir) }
            .getOrNull()
            .orEmpty()
            .filter { it.endsWith(".png", ignoreCase = true) }
            .sorted()

        for (name in names) {
            val mat = Mat()
            try {
                val bitmap = context.assets.open("$assetsDir/$name").use { stream ->
                    BitmapFactory.decodeStream(stream, null, argb8888Options())
                }
                if (bitmap == null) {
                    Log.w(TAG, "cannot decode template $name")
                    mat.release()
                    continue
                }
                Utils.bitmapToMat(bitmap, mat)
                bitmap.recycle()
                originals[name] = mat
            } catch (t: Throwable) {
                mat.release()
                Log.w(TAG, "cannot load template $name", t)
            }
        }
        Log.i(TAG, "loaded ${originals.size} template(s): ${originals.keys}")
    }

    /**
     * Points the matcher at a screen of this size, rebuilding the resized templates.
     *
     * Called with the size of an actual captured frame rather than a queried display size,
     * which keeps the templates correct across rotation and multi-window on its own.
     *
     * @return true when the screen size changed and templates were rescaled.
     */
    fun setScreen(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        val current = adapter
        if (current != null && current.screenWidth == width && current.screenHeight == height) {
            return false
        }

        val next = ScreenAdapter(width, height)
        val rescaled = LinkedHashMap<String, Mat>(originals.size)
        for ((name, original) in originals) {
            val target = Mat()
            try {
                Imgproc.resize(
                    original, target,
                    Size(),
                    next.scale.toDouble(), next.scale.toDouble(),
                    Imgproc.INTER_AREA
                )
                if (target.empty()) {
                    target.release()
                    Log.w(TAG, "template $name scaled to nothing at scale=${next.scale}")
                    continue
                }
                rescaled[name] = target
            } catch (t: Throwable) {
                target.release()
                Log.w(TAG, "cannot scale template $name", t)
            }
        }

        releaseScaled()
        scaled = rescaled
        adapter = next
        Log.i(TAG, "$next, ${scaled.size} template(s) ready")
        return true
    }

    /**
     * Locates [template] inside [frame].
     *
     * @param roi optional search region in 720p logical coordinates; restricting the search
     *   is both faster and far less prone to matching the same artwork elsewhere on screen.
     * @return the hit, or null when the template is missing/too large or the best score is
     *   below [threshold].
     */
    fun match(
        frame: Mat,
        template: String,
        roi: Rect? = null,
        threshold: Double = defaultThreshold
    ): MatchResult? {
        val current = adapter ?: return null
        val tmpl = scaled[template] ?: return null
        if (frame.empty() || tmpl.empty()) return null
        if (tmpl.width() > frame.width() || tmpl.height() > frame.height()) return null

        val searchArea = if (roi == null) {
            org.opencv.core.Rect(0, 0, frame.width(), frame.height())
        } else {
            current.toScreenRect(roi) ?: return null
        }
        if (searchArea.width < tmpl.width() || searchArea.height < tmpl.height()) return null

        val region = frame.submat(searchArea)
        val result = Mat()
        try {
            Imgproc.matchTemplate(region, tmpl, result, Imgproc.TM_CCOEFF_NORMED)
            val best = Core.minMaxLoc(result)
            if (best.maxVal < threshold) return null

            // maxLoc is the top-left corner of the best alignment, relative to the region.
            val centerX = searchArea.x + best.maxLoc.x + tmpl.width() / 2.0
            val centerY = searchArea.y + best.maxLoc.y + tmpl.height() / 2.0
            val logical = current.toLogical(centerX.toFloat(), centerY.toFloat())
            return MatchResult(template, best.maxVal, logical.x, logical.y)
        } finally {
            result.release()
            region.release()
        }
    }

    /** Releases every Mat this matcher owns. */
    fun release() {
        releaseScaled()
        for (mat in originals.values) mat.release()
        originals.clear()
        adapter = null
    }

    private fun releaseScaled() {
        for (mat in scaled.values) mat.release()
        scaled.clear()
    }

    companion object {
        private const val TAG = "TemplateMatcher"
        const val DEFAULT_ASSETS_DIR = "templates"

        /** TM_CCOEFF_NORMED yields a correlation coefficient in [-1, 1]. */
        const val DEFAULT_THRESHOLD = 0.80
    }
}
