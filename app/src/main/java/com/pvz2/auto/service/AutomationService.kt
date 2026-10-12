package com.pvz2.auto.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.pvz2.auto.MainActivity
import com.pvz2.auto.R
import com.pvz2.auto.core.BotState
import com.pvz2.auto.core.ScreenCapturer
import com.pvz2.auto.core.TemplateMatcher
import com.pvz2.auto.core.TouchInjector
import com.pvz2.auto.fallback.MediaProjectionCapturer
import com.pvz2.auto.fallback.Pvz2AccessibilityService
import com.pvz2.auto.overlay.FloatingWindow
import com.pvz2.auto.shizuku.ShizukuBridge
import com.pvz2.auto.shizuku.ShizukuScreenCapturer
import com.pvz2.auto.shizuku.ShizukuTouchInjector
import com.pvz2.auto.shizuku.ShizukuUserServiceClient
import com.pvz2.auto.task.Pvz2Bot
import com.pvz2.auto.task.Pvz2Tasks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import java.io.File
import java.io.FileOutputStream

/**
 * Foreground service that owns everything long-lived:
 *
 *  * the automation loop (always on [Dispatchers.IO], never on the main thread);
 *  * the Shizuku UserService binding;
 *  * the MediaProjection fallback session;
 *  * the floating control panel.
 *
 * It is a foreground service so the process keeps enough priority to survive while the
 * game owns the screen. The foreground service *type* differs per backend and must match
 * the manifest, otherwise Android 14 throws:
 *
 *  * Shizuku path   -> `specialUse`            (no MediaProjection session exists)
 *  * fallback path  -> `mediaProjection`       (only after the user granted capture)
 */
class AutomationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    private lateinit var shizukuClient: ShizukuUserServiceClient
    private var floatingWindow: FloatingWindow? = null

    private var capturer: ScreenCapturer? = null
    private var injector: TouchInjector? = null
    private var matcher: TemplateMatcher? = null
    private var fallbackCapturer: MediaProjectionCapturer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        shizukuClient = ShizukuUserServiceClient(this)
        createNotificationChannel()

        // Mirror the loop's status into the floating panel. Collected on the main thread
        // because updateStatus() touches a View, while the loop publishes from Dispatchers.IO.
        scope.launch(Dispatchers.Main) {
            BotState.status.collect { text -> floatingWindow?.updateStatus(text) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_PANEL -> {
                startForegroundCompat(mediaProjection = false)
                showPanel()
            }

            ACTION_START_SHIZUKU -> {
                startForegroundCompat(mediaProjection = false)
                showPanel()
                launchLoop { runShizukuLoop() }
            }

            ACTION_START_FALLBACK -> {
                // Order matters and is fixed by Android 14: the foreground service with the
                // mediaProjection type must already be running before getMediaProjection().
                startForegroundCompat(mediaProjection = true)
                showPanel()
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                // Explicit type: without it Kotlin infers the bound (Parcelable?), not Intent.
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (resultData == null) {
                    fail("No screen-capture consent data")
                } else {
                    launchLoop { runFallbackLoop(resultCode, resultData) }
                }
            }

            ACTION_GRAB_FRAME -> scope.launch { grabOneFrame() }
        }
        // Never restart with a null intent: the loop's parameters live in the intent.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        loopJob?.cancel()
        loopJob = null
        capturer = null
        injector?.release()
        injector = null
        fallbackCapturer?.release()
        fallbackCapturer = null
        matcher?.release()
        matcher = null

        runCatching { shizukuClient.unbind(remove = true) }

        floatingWindow?.hide()
        floatingWindow = null

        BotState.setRunning(false)
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ loop

    private fun launchLoop(block: suspend () -> Unit) {
        loopJob?.cancel()
        loopJob = scope.launch {
            BotState.setRunning(true)
            try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                Log.e(TAG, "automation loop failed", t)
                fail(t.message ?: t::class.java.simpleName)
            }
        }
    }

    /** Primary path: everything runs as shell through the Shizuku UserService. */
    private suspend fun runShizukuLoop() {
        if (!ShizukuBridge.hasPermission()) {
            fail("Shizuku permission not granted")
            return
        }

        shizukuClient.bind()
        val service = shizukuClient.await()
        if (service == null) {
            fail("Shizuku UserService did not connect: ${shizukuClient.lastError ?: "timeout"}")
            return
        }

        // Cheap end-to-end proof that the privileged shell actually works before we start
        // pumping frames through it.
        val displaySize = runCatching { service.getDisplaySize() }.getOrNull().orEmpty()
        if (displaySize.isEmpty()) {
            fail("Privileged shell is not responding")
            return
        }
        BotState.setStatus("Shizuku ready ($displaySize)")

        capturer = ShizukuScreenCapturer(shizukuClient)
        injector = ShizukuTouchInjector(shizukuClient)
        runLoop()
    }

    /** Fallback path: MediaProjection for pixels, accessibility gestures for touch. */
    private suspend fun runFallbackLoop(resultCode: Int, resultData: Intent) {
        val accessibility = Pvz2AccessibilityService.instance
        if (accessibility == null) {
            fail("Fallback needs the accessibility service enabled")
            return
        }

        val (width, height) = realScreenSize()
        val projection = MediaProjectionCapturer(this)
        projection.start(resultCode, resultData, width, height, resources.displayMetrics.densityDpi)

        fallbackCapturer = projection
        capturer = projection
        injector = accessibility
        runLoop()
    }

    private suspend fun runLoop() {
        if (!loadMatcher()) return
        val activeCapturer = capturer ?: return
        val activeInjector = injector ?: return
        val activeMatcher = matcher ?: return
        Pvz2Bot(activeCapturer, activeInjector, activeMatcher, Pvz2Tasks.defaultActions()).run()
    }

    /**
     * Loads the native OpenCV library and the 720p templates.
     *
     * `initLocal()` must run before the first `Mat` is constructed, and template loading is
     * disk I/O, so both happen here on the IO dispatcher.
     *
     * An empty template folder is not fatal: the loop still captures frames and reports the
     * detected resolution, which is how you confirm the privileged screenshot path works
     * before you have any artwork.
     */
    private fun loadMatcher(): Boolean {
        if (!OpenCVLoader.initLocal()) {
            fail("OpenCV native library failed to load")
            return false
        }

        val loaded = TemplateMatcher()
        loaded.load(this)
        matcher = loaded
        if (loaded.loadedCount == 0) {
            Log.w(TAG, "no templates found in assets/${TemplateMatcher.DEFAULT_ASSETS_DIR}")
            BotState.setStatus("Running with 0 templates - see assets/templates/README.md")
        }
        return true
    }

    // -------------------------------------------------------------- one-shot

    /** Saves a single frame so the user can crop templates out of it. */
    private suspend fun grabOneFrame() {
        if (!OpenCVLoader.initLocal()) {
            fail("OpenCV native library failed to load")
            return
        }
        if (!ShizukuBridge.hasPermission()) {
            fail("Grabbing a frame needs Shizuku permission")
            return
        }

        shizukuClient.bind()
        val service = shizukuClient.await()
        if (service == null) {
            fail("Shizuku UserService did not connect")
            return
        }

        val frame = ShizukuScreenCapturer(shizukuClient).capture()
        if (frame == null) {
            fail("Frame capture failed")
            return
        }

        try {
            val bitmap = Bitmap.createBitmap(frame.width(), frame.height(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(frame, bitmap)

            val baseDir = getExternalFilesDir(null)
            if (baseDir == null) {
                bitmap.recycle()
                fail("No external files directory available")
                return
            }
            val directory = File(baseDir, "shots").apply { mkdirs() }
            val file = File(directory, "shot_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()

            Log.i(TAG, "saved $file")
            BotState.setStatus("Saved ${file.absolutePath}")
        } finally {
            frame.release()
        }
    }

    // -------------------------------------------------------------- plumbing

    private fun showPanel() {
        if (floatingWindow != null) return
        if (!android.provider.Settings.canDrawOverlays(this)) {
            BotState.setStatus("Grant \"display over other apps\" to get the floating panel")
            return
        }
        floatingWindow = FloatingWindow(this).also {
            it.show(
                onStart = { startShizuku(this) },
                onStop = { stop(this) }
            )
            it.updateStatus(BotState.status.value)
        }
    }

    private fun fail(message: String) {
        Log.w(TAG, "cannot start: $message")
        BotState.setStatus(message)
        BotState.setRunning(false)
    }

    private fun realScreenSize(): Pair<Int, Int> {
        val windowManager = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
    }

    /**
     * Passes exactly one foreground service type, matching the backend in use.
     * `ServiceCompat.startForeground` is the documented best practice for this.
     */
    private fun startForegroundCompat(mediaProjection: Boolean) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (mediaProjection) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pvz2)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(BotState.status.value)
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "AutomationService"
        private const val CHANNEL_ID = "pvz2_automation"
        private const val NOTIFICATION_ID = 1

        private const val ACTION_SHOW_PANEL = "com.pvz2.auto.SHOW_PANEL"
        private const val ACTION_START_SHIZUKU = "com.pvz2.auto.START_SHIZUKU"
        private const val ACTION_START_FALLBACK = "com.pvz2.auto.START_FALLBACK"
        private const val ACTION_GRAB_FRAME = "com.pvz2.auto.GRAB_FRAME"

        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        private fun intent(context: Context, action: String) =
            Intent(context, AutomationService::class.java).setAction(action)

        fun showPanel(context: Context) {
            ContextCompat.startForegroundService(context, intent(context, ACTION_SHOW_PANEL))
        }

        fun startShizuku(context: Context) {
            ContextCompat.startForegroundService(context, intent(context, ACTION_START_SHIZUKU))
        }

        fun startFallback(context: Context, resultCode: Int, resultData: Intent) {
            ContextCompat.startForegroundService(
                context,
                intent(context, ACTION_START_FALLBACK)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_RESULT_DATA, resultData)
            )
        }

        fun grabFrame(context: Context) {
            context.startService(intent(context, ACTION_GRAB_FRAME))
        }

        /** `stopService` is always permitted, unlike starting a service from the background. */
        fun stop(context: Context) {
            context.stopService(Intent(context, AutomationService::class.java))
        }
    }
}
