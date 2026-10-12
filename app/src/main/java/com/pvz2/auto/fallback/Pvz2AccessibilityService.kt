package com.pvz2.auto.fallback

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import com.pvz2.auto.core.TouchInjector
import kotlin.math.hypot

/**
 * Application-layer touch injection, used when Shizuku is unavailable.
 *
 * `AccessibilityService#dispatchGesture` (API 24+) is the only public API an ordinary app
 * can use to synthesise taps and swipes; it requires `canPerformGestures="true"` in
 * `res/xml/accessibility_service_config.xml`.
 */
class Pvz2AccessibilityService : AccessibilityService(), TouchInjector {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "accessibility input service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        dispatch(
            GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS)
                )
                .build()
        )
    }

    override fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val dx = x2 - x1
        val dy = y2 - y1
        val length = hypot(dx, dy).coerceAtLeast(1f)

        // Quadratic Bezier with the control point pushed off the straight line, so the
        // gesture follows a slight arc instead of a perfectly straight robot swipe.
        val bend = (length * BEND_RATIO).coerceAtMost(MAX_BEND)
        val controlX = (x1 + x2) / 2f - dy / length * bend
        val controlY = (y1 + y2) / 2f + dx / length * bend

        val path = Path().apply {
            moveTo(x1, y1)
            quadTo(controlX, controlY, x2, y2)
        }

        dispatch(
            GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        path, 0L, durationMs.coerceAtLeast(1L)
                    )
                )
                .build()
        )
    }

    private fun dispatch(gesture: GestureDescription) {
        // Null callback: a dropped gesture is simply retried on the next loop iteration.
        if (!dispatchGesture(gesture, null, null)) {
            Log.w(TAG, "dispatchGesture rejected")
        }
    }

    companion object {
        private const val TAG = "Pvz2Accessibility"
        private const val TAP_DURATION_MS = 40L
        private const val BEND_RATIO = 0.08f
        private const val MAX_BEND = 60f

        @Volatile
        var instance: Pvz2AccessibilityService? = null
            private set

        /** Bound and usable right now. */
        fun isConnected(): Boolean = instance != null

        /**
         * Whether the user has switched this service on in system settings, which is a
         * different question from whether it is currently bound.
         */
        fun isEnabled(context: Context): Boolean {
            val manager = context.getSystemService(AccessibilityManager::class.java)
                ?: return false
            val expected = "${context.packageName}/${Pvz2AccessibilityService::class.java.name}"
            return manager
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.id.equals(expected, ignoreCase = true) }
        }
    }
}
