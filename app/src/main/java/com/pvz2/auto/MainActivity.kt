package com.pvz2.auto

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.pvz2.auto.core.BotState
import com.pvz2.auto.fallback.Pvz2AccessibilityService
import com.pvz2.auto.service.AutomationService
import com.pvz2.auto.shizuku.ShizukuBridge
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * Permission console and launcher.
 *
 * Everything that can run headless lives in [AutomationService]; this activity only
 * acquires the permissions that require user interaction and then hands control over.
 * The one flow that genuinely needs an activity is the MediaProjection consent dialog.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var shizukuStateView: TextView
    private lateinit var statusView: TextView
    private lateinit var authorizeButton: Button

    /** MediaProjection consent: the result must be forwarded straight to the service. */
    private val captureConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            AutomationService.startFallback(this, result.resultCode, data)
        } else {
            BotState.setStatus("Screen capture consent denied")
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        BotState.setStatus(
            if (granted) "Notifications allowed"
            else "Notifications denied - the foreground service still runs"
        )
    }

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            refreshShizukuState()
            BotState.setStatus(
                if (grantResult == PackageManager.PERMISSION_GRANTED) "Shizuku permission granted"
                else "Shizuku permission denied"
            )
        }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refreshShizukuState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        shizukuStateView = findViewById(R.id.tvShizukuState)
        statusView = findViewById(R.id.tvStatus)
        authorizeButton = findViewById(R.id.btnShizuku)

        authorizeButton.setOnClickListener { requestShizukuPermission() }
        findViewById<Button>(R.id.btnOverlay).setOnClickListener { requestOverlayPermission() }
        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnNotifications).setOnClickListener {
            requestNotificationPermission()
        }

        findViewById<Button>(R.id.btnShowOverlay).setOnClickListener {
            AutomationService.showPanel(this)
        }
        findViewById<Button>(R.id.btnStartShizuku).setOnClickListener {
            AutomationService.startShizuku(this)
        }
        findViewById<Button>(R.id.btnStartFallback).setOnClickListener { startFallback() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { AutomationService.stop(this) }
        findViewById<Button>(R.id.btnGrabFrame).setOnClickListener {
            AutomationService.grabFrame(this)
        }
        findViewById<Button>(R.id.btnProbe).setOnClickListener {
            AutomationService.probe(this)
        }
        findViewById<Button>(R.id.btnVerifyDry).setOnClickListener {
            AutomationService.verifyDryRun(this)
        }
        findViewById<Button>(R.id.btnVerifyRun).setOnClickListener {
            AutomationService.verifyRun(this)
        }

        // Shizuku can arrive or disappear while we are on screen, so track both edges.
        ShizukuBridge.addPermissionResultListener(shizukuPermissionListener)
        ShizukuBridge.addBinderReceivedListener(binderReceivedListener)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { BotState.status.collect { statusView.text = it } }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        refreshShizukuState()
    }

    override fun onDestroy() {
        ShizukuBridge.removePermissionResultListener(shizukuPermissionListener)
        ShizukuBridge.removeBinderReceivedListener(binderReceivedListener)
        super.onDestroy()
    }

    private fun requestShizukuPermission() {
        if (!ShizukuBridge.isBinderAlive()) {
            BotState.setStatus("Shizuku is not running - install and start it first")
            return
        }
        if (ShizukuBridge.hasPermission()) {
            BotState.setStatus("Shizuku already granted")
            return
        }
        ShizukuBridge.requestPermission()
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            BotState.setStatus("Overlay permission already granted")
            return
        }
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            BotState.setStatus("Notifications are not gated before Android 13")
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun startFallback() {
        if (!Pvz2AccessibilityService.isEnabled(this)) {
            BotState.setStatus("Enable the accessibility service first (step 3)")
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        captureConsentLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun refreshShizukuState() {
        shizukuStateView.text = when {
            !ShizukuBridge.isBinderAlive() ->
                "Shizuku: not running. Install/start Shizuku, then reopen this screen."

            ShizukuBridge.hasPermission() ->
                "Shizuku: ready (uid ${runCatching { Shizuku.getUid() }.getOrDefault(-1)})"

            else -> "Shizuku: running, permission not granted"
        }
        authorizeButton.isEnabled = ShizukuBridge.canRequestPermission()
    }
}
