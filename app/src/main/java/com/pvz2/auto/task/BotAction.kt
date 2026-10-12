package com.pvz2.auto.task

import android.graphics.Rect
import com.pvz2.auto.core.TemplateMatcher

/**
 * One recognition rule: "if this template is on screen (optionally inside [roi]), tap it".
 *
 * @param template PNG file name inside `assets/templates`, e.g. `"sun.png"`.
 * @param roi search region in **720p logical coordinates**, or null for the whole screen.
 * @param threshold minimum `TM_CCOEFF_NORMED` score.
 * @param afterTapDelayMs settle time before the next frame is examined.
 */
data class BotAction(
    val template: String,
    val roi: Rect? = null,
    val threshold: Double = TemplateMatcher.DEFAULT_THRESHOLD,
    val afterTapDelayMs: Long = 600L
)
