package com.pvz2.auto

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
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
    private lateinit var authorizeButton: MaterialButton

    /** MediaProjection consent: the result must be forwarded straight to the service. */
    private val captureConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            AutomationService.startFallback(this, result.resultCode, data)
        } else {
            BotState.setStatus(getString(R.string.msg_capture_denied))
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        BotState.setStatus(
            getString(
                if (granted) R.string.msg_notifications_granted
                else R.string.msg_notifications_denied
            )
        )
    }

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            refreshShizukuState()
            BotState.setStatus(
                getString(
                    if (grantResult == PackageManager.PERMISSION_GRANTED) R.string.msg_permission_granted
                    else R.string.msg_permission_denied
                )
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
        findViewById<MaterialButton>(R.id.btnOverlay).setOnClickListener { requestOverlayPermission() }
        findViewById<MaterialButton>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<MaterialButton>(R.id.btnNotifications).setOnClickListener {
            requestNotificationPermission()
        }

        findViewById<MaterialButton>(R.id.btnShowOverlay).setOnClickListener {
            AutomationService.showPanel(this)
        }
        findViewById<MaterialButton>(R.id.btnStartShizuku).setOnClickListener {
            AutomationService.startShizuku(this)
        }
        findViewById<MaterialButton>(R.id.btnStartFallback).setOnClickListener { startFallback() }
        findViewById<MaterialButton>(R.id.btnStop).setOnClickListener { AutomationService.stop(this) }
        findViewById<MaterialButton>(R.id.btnGrabFrame).setOnClickListener {
            AutomationService.grabFrame(this)
        }
        findViewById<MaterialButton>(R.id.btnProbe).setOnClickListener {
            AutomationService.probe(this)
        }
        findViewById<MaterialButton>(R.id.btnVerifyDry).setOnClickListener {
            AutomationService.verifyDryRun(this)
        }
        findViewById<MaterialButton>(R.id.btnVerifyRun).setOnClickListener {
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
            BotState.setStatus(getString(R.string.msg_shizuku_not_alive))
            return
        }
        if (ShizukuBridge.hasPermission()) {
            BotState.setStatus(getString(R.string.msg_already_granted))
            return
        }
        ShizukuBridge.requestPermission()
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            BotState.setStatus(getString(R.string.msg_overlay_granted))
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
            BotState.setStatus(getString(R.string.msg_notifications_not_needed))
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun startFallback() {
        if (!Pvz2AccessibilityService.isEnabled(this)) {
            BotState.setStatus(getString(R.string.msg_need_accessibility))
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        captureConsentLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun refreshShizukuState() {
        shizukuStateView.text = when {
            !ShizukuBridge.isBinderAlive() -> getString(R.string.shizuku_not_running)

            ShizukuBridge.hasPermission() -> getString(
                R.string.shizuku_ready,
                runCatching { Shizuku.getUid() }.getOrDefault(-1)
            )

            else -> getString(R.string.shizuku_no_permission)
        }
        // 已授权时把按钮置灰，避免用户反复点击同一个动作。
        authorizeButton.isEnabled = ShizukuBridge.canRequestPermission()
    }
}
