package com.pvz2.auto.task

import android.util.Log
import com.pvz2.auto.core.BotState
import com.pvz2.auto.core.ScreenCapturer
import com.pvz2.auto.core.TapJitter
import com.pvz2.auto.core.TemplateMatcher
import com.pvz2.auto.core.TouchInjector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.random.Random

/**
 * The whole automation pipeline:
 *
 *     screenshot -> template match -> logical-to-screen coordinate conversion -> tap -> wait
 *
 * Runs until its coroutine is cancelled. Every iteration is individually guarded, so one
 * bad frame (decode failure, transient shell error, ...) skips that frame instead of
 * tearing the loop down.
 *
 * Must be driven from a `Dispatchers.IO` coroutine: [ScreenCapturer] and [TouchInjector]
 * both block.
 */
class Pvz2Bot(
    private val capturer: ScreenCapturer,
    private val injector: TouchInjector,
    private val matcher: TemplateMatcher,
    private val actions: List<BotAction>
) {

    suspend fun run() {
        Log.i(TAG, "loop start with ${actions.size} rule(s), templates=${matcher.templateNames}")
        BotState.setStatus("运行中（${matcher.loadedCount} 个模板）")

        while (currentCoroutineContext().isActive) {
            try {
                step()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                // Spec: a failing step must never crash the loop.
                Log.w(TAG, "step failed, skipping this iteration", t)
            }
            delay(LOOP_INTERVAL_MS + Random.nextLong(-LOOP_JITTER_MS, LOOP_JITTER_MS + 1))
        }
        Log.i(TAG, "loop cancelled")
    }

    private suspend fun step() {
        val frame = capturer.capture() ?: return
        try {
            // The frame carries the true screen size, so this also handles rotation and
            // multi-window without asking the WindowManager anything.
            if (matcher.setScreen(frame.width(), frame.height())) {
                BotState.setStatus("运行中 @ ${frame.width()}x${frame.height()}")
            }

            val adapter = matcher.adapter ?: return
            for (action in actions) {
                val hit = matcher.match(frame, action.template, action.roi, action.threshold)
                    ?: continue

                // Logical 720p hit -> physical pixels, then +/-5 px of anti-detection jitter.
                val point = adapter.toScreen(hit.logicalX, hit.logicalY)
                injector.tap(point.x + TapJitter.dx(), point.y + TapJitter.dy())

                Log.i(
                    TAG,
                    "tapped ${action.template} score=%.3f at logical(%.0f,%.0f) -> screen(%.0f,%.0f)"
                        .format(hit.score, hit.logicalX, hit.logicalY, point.x, point.y)
                )
                BotState.setStatus("${action.template}  (${"%.2f".format(hit.score)})")

                delay(action.afterTapDelayMs)
                // One action per frame keeps the loop predictable and lets the UI settle.
                return
            }
        } finally {
            frame.release()
        }
    }

    companion object {
        private const val TAG = "Pvz2Bot"

        /** Base interval between iterations. */
        const val LOOP_INTERVAL_MS = 500L

        /** +/-100 ms of jitter around [LOOP_INTERVAL_MS]. */
        const val LOOP_JITTER_MS = 100L
    }
}
