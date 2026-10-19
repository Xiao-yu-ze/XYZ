package com.pvz2.auto

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
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

        // 四个准备步骤现在是清单行，不再各自是一个大按钮。
        // 行整行可点，右侧文字显示状态。
        findViewById<LinearLayout>(R.id.rowStep1).setOnClickListener { requestShizukuPermission() }
        findViewById<LinearLayout>(R.id.rowStep2).setOnClickListener { requestOverlayPermission() }
        findViewById<LinearLayout>(R.id.rowStep3).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<LinearLayout>(R.id.rowStep4).setOnClickListener {
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
        findViewById<MaterialButton>(R.id.btnVdProbe).setOnClickListener {
            AutomationService.vdProbe(this)
        }

        // 调试工具默认折叠：它不是主流程，常驻展开会把主路径挤出首屏。
        findViewById<LinearLayout>(R.id.debugHeader).setOnClickListener { toggleDebugSection() }

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
        refreshStatusDot()
        refreshSetupSteps()
    }

    /** 状态点颜色：红了=Shizuku 没跑，金=跑了但没授权，绿=可用。 */
    private fun refreshStatusDot() {
        findViewById<View>(R.id.statusDot).setBackgroundResource(
            when {
                !ShizukuBridge.isBinderAlive() -> R.drawable.bg_dot_bad
                ShizukuBridge.hasPermission() -> R.drawable.bg_dot_ok
                else -> R.drawable.bg_dot_todo
            }
        )
    }

    /**
     * 刷新准备清单的完成状态。
     *
     * 四个步骤的完成判定互相独立，而且**随时可能被外部改变**
     * （用户跳去系统设置授权再回来），所以放在 [onResume] 里也调一次。
     */
    private fun refreshSetupSteps() {
        val done = booleanArrayOf(
            ShizukuBridge.hasPermission(),
            Settings.canDrawOverlays(this),
            Pvz2AccessibilityService.isEnabled(this),
            notificationsGranted()
        )
        // 每步"未完成"时右侧显示的动词
        val todoText = intArrayOf(
            R.string.step_todo_auth,
            R.string.step_todo_auth,
            R.string.step_todo_enable,
            R.string.step_todo_auth
        )
        // 每步"已完成"时的措辞（授权 / 启用 语感不同，不硬套一个"已完成"）
        val doneText = intArrayOf(
            R.string.step_granted,
            R.string.step_granted,
            R.string.step_enabled,
            R.string.step_granted
        )
        val rowIds = intArrayOf(R.id.rowStep1, R.id.rowStep2, R.id.rowStep3, R.id.rowStep4)
        val numIds = intArrayOf(R.id.stepNum1, R.id.stepNum2, R.id.stepNum3, R.id.stepNum4)
        val actionIds =
            intArrayOf(R.id.stepAction1, R.id.stepAction2, R.id.stepAction3, R.id.stepAction4)

        for (i in 0 until 4) {
            val isDone = done[i]

            findViewById<TextView>(numIds[i]).setBackgroundResource(
                if (isDone) R.drawable.bg_step_number_done else R.drawable.bg_step_number
            )
            val action = findViewById<TextView>(actionIds[i])
            action.setText(if (isDone) doneText[i] else todoText[i])
            // 已完成的行整体压暗，让"还剩哪几步"在扫视时更跳出来。
            findViewById<LinearLayout>(rowIds[i]).alpha = if (isDone) 0.55f else 1f
        }

        findViewById<TextView>(R.id.tvProgress)
            .text = getString(R.string.step_progress, done.count { it }, done.size)
    }

    /** Android 13 起通知权限才需要单独申请；更低版本视为已授予。 */
    private fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun toggleDebugSection() {
        val body = findViewById<LinearLayout>(R.id.debugBody)
        val expand = body.visibility != View.VISIBLE
        body.visibility = if (expand) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.debugArrow).text = if (expand) "▾" else "▸"
    }

    override fun onResume() {
        super.onResume()
        // 用户可能刚从系统设置里授权完回来，回来时状态要立刻反映出来。
        refreshShizukuState()
    }
}
