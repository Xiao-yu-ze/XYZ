package com.pvz2.auto.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.pvz2.auto.R
import kotlin.math.abs

/**
 * Draggable always-on-top control panel with Start/Stop and a live status line.
 *
 * Requires the SYSTEM_ALERT_WINDOW permission; callers must check
 * `Settings.canDrawOverlays()` first. On API 26+ the only window type allowed for this is
 * `TYPE_APPLICATION_OVERLAY`.
 *
 * All methods must be called from the main thread.
 */
class FloatingWindow(private val context: Context) {

    private val windowManager: WindowManager =
        context.getSystemService(WindowManager::class.java)

    private var root: View? = null
    private var statusView: TextView? = null
    private lateinit var params: WindowManager.LayoutParams

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    fun show(onStart: () -> Unit, onStop: () -> Unit) {
        if (root != null) return

        val view = LayoutInflater.from(context).inflate(R.layout.overlay_panel, null)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_FOCUSABLE keeps the game's input focus while still delivering touches.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 120
        }

        statusView = view.findViewById(R.id.overlayStatus)
        view.findViewById<Button>(R.id.btnOverlayStart).setOnClickListener { onStart() }
        view.findViewById<Button>(R.id.btnOverlayStop).setOnClickListener { onStop() }
        // Only the title row is draggable, which leaves the buttons clickable.
        view.findViewById<View>(R.id.dragHandle).setOnTouchListener(DragListener())

        windowManager.addView(view, params)
        root = view
    }

    fun hide() {
        val view = root ?: return
        runCatching { windowManager.removeView(view) }
            .onFailure { Log.w(TAG, "removeView failed", it) }
        root = null
        statusView = null
    }

    fun updateStatus(text: String) {
        statusView?.text = text
    }

    /** Drags the panel and, on release, snaps it to the nearest screen edge. */
    private inner class DragListener : View.OnTouchListener {

        private var startX = 0
        private var startY = 0
        private var downRawX = 0f
        private var downRawY = 0f
        private var dragging = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    dragging = false
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                    }
                    if (dragging) {
                        params.x = startX + dx
                        params.y = startY + dy
                        root?.let { windowManager.updateViewLayout(it, params) }
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) snapToEdge()
                    return dragging
                }
            }
            return false
        }
    }

    private fun snapToEdge() {
        val view = root ?: return
        val screenWidth = context.resources.displayMetrics.widthPixels
        params.x = if (params.x + view.width / 2 < screenWidth / 2) {
            0
        } else {
            screenWidth - view.width
        }
        windowManager.updateViewLayout(view, params)
    }

    private companion object {
        const val TAG = "FloatingWindow"
    }
}
