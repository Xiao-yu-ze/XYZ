package com.pvz2.auto

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.core.content.FileProvider
import com.pvz2.auto.shizuku.ShizukuUserServiceClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import androidx.core.widget.doAfterTextChanged
import com.pvz2.auto.core.ChannelPrefs
import com.pvz2.auto.core.Pvz2Channels
import android.widget.ImageView
import com.pvz2.auto.service.AutomationService
import com.pvz2.auto.service.VirtualDisplaySession
import com.pvz2.auto.shizuku.ShizukuBridge
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * Permission console and launcher.
 *
 * Everything that can run headless lives in [AutomationService]; this activity only
 * acquires the permissions that require user interaction and then hands control over.
 * 没有 MediaProjection 授权流程了 —— 降级方案已移除，只剩 Shizuku 一条路。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var shizukuStateView: TextView
    private lateinit var statusView: TextView

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

        // 两个准备步骤现在是清单行，不再各自是一个大按钮。
        // 行整行可点，右侧文字显示状态。
        // 悬浮窗与无障碍这两行都已去掉：
        //  · 悬浮窗 —— 所有操作都在本界面做（虚拟屏下游戏跑副屏，不遮挡本界面）；
        //  · 无障碍 —— 它是 MediaProjection 降级方案的触控后端，降级方案已整体移除。
        findViewById<LinearLayout>(R.id.rowStep1).setOnClickListener { requestShizukuPermission() }
        findViewById<LinearLayout>(R.id.rowStep2).setOnClickListener {
            requestNotificationPermission()
        }

        findViewById<MaterialButton>(R.id.btnStartShizuku).setOnClickListener {
            AutomationService.startShizuku(this)
        }
        findViewById<MaterialButton>(R.id.btnStop).setOnClickListener { AutomationService.stop(this) }

        // 虚拟屏：主功能区的两个按钮
        findViewById<MaterialButton>(R.id.btnVdStart).setOnClickListener {
            val pkg = ChannelPrefs.resolvePackage(this)
            if (pkg.isNullOrBlank()) {
                // 没有可用包名时别静默失败 —— 直接告诉用户该做什么。
                findViewById<TextView>(R.id.tvChannelHint).text =
                    getString(R.string.channel_not_found)
            } else {
                AutomationService.vdStart(this, pkg)
            }
        }
        findViewById<MaterialButton>(R.id.btnVdStop).setOnClickListener {
            AutomationService.vdStop(this)
        }

        setupChannelPicker()
        observePreview()

        // 导出日志：把关键日志存成文件并弹分享。
        // 真机上没有电脑连着的时候，这是唯一能把现场发给别人的办法。
        findViewById<MaterialButton>(R.id.btnExportLog).setOnClickListener {
            exportLogs()
        }

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
                // 虚拟屏状态：服务写、界面读。两者是独立组件，所以走进程级单例流。
                launch { VirtualDisplaySession.sharedState.collect { renderVirtualDisplay(it) } }
            }
        }
    }

    /**
     * 渠道选择器。
     *
     * 三条行为：
     *  ① 进界面时**自动识别**设备上已装的渠道（包名匹配），认出来就选上；
     *  ② 用户手动换渠道 → 记住；
     *  ③ 选「自定义包名…」→ 显示输入框，内容也记住。
     *
     * 为什么需要它：不同渠道的包名不同，选错就启动不了游戏，
     * 而之前代码里硬编码的是 OPPO 渠道的包名。
     */
    private fun setupChannelPicker() {
        val spinner = findViewById<Spinner>(R.id.spChannel)
        val customInput = findViewById<EditText>(R.id.etCustomPackage)
        val hint = findViewById<TextView>(R.id.tvChannelHint)

        // 选项 = 全部渠道 + 末尾的「自定义包名…」
        val labels = Pvz2Channels.ALL.map { "${it.label}（${it.packageName}）" } +
            getString(R.string.channel_custom)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)

        val customIndex = Pvz2Channels.ALL.size

        val savedKey = ChannelPrefs.savedChannelKey(this)
        val savedCustom = ChannelPrefs.savedCustomPackage(this)
        val detected = Pvz2Channels.detect(installedPackages())

        val initialIndex = when {
            savedCustom.isNotEmpty() -> customIndex
            savedKey != null -> Pvz2Channels.ALL.indexOfFirst { it.key == savedKey }.takeIf { it >= 0 } ?: 0
            detected != null -> Pvz2Channels.ALL.indexOf(detected)
            else -> Pvz2Channels.ALL.indexOf(Pvz2Channels.DEFAULT)
        }
        spinner.setSelection(initialIndex)

        if (savedCustom.isNotEmpty()) {
            customInput.setText(savedCustom)
            customInput.visibility = View.VISIBLE
        }
        when {
            savedKey == null && savedCustom.isEmpty() && detected != null ->
                hint.text = getString(R.string.channel_detected, detected.label, detected.packageName)

            detected == null && savedCustom.isEmpty() ->
                hint.text = getString(R.string.channel_not_found)
        }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == customIndex) {
                    customInput.visibility = View.VISIBLE
                    hint.text = getString(R.string.hint_custom_package)
                } else {
                    customInput.visibility = View.GONE
                    val channel = Pvz2Channels.ALL[position]
                    ChannelPrefs.saveChannel(this@MainActivity, channel.key)
                    // 选了具体渠道就清掉自定义包名，否则它会一直优先生效。
                    ChannelPrefs.saveCustomPackage(this@MainActivity, "")
                    hint.text = getString(R.string.channel_picked, channel.label, channel.packageName)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        customInput.doAfterTextChanged { text ->
            val value = text?.toString()?.trim().orEmpty()
            if (value.isEmpty()) return@doAfterTextChanged
            // 只做格式校验，不要求已安装 —— 用户可能在另一台设备上装好了。
            if (value.matches(Regex("[A-Za-z0-9._]+"))) {
                ChannelPrefs.saveCustomPackage(this, value)
                hint.text = getString(R.string.channel_picked, "自定义", value)
            } else {
                hint.text = getString(R.string.channel_custom_invalid)
            }
        }
    }

    /** 设备上已安装的包名。用于自动认渠道。 */
    private fun installedPackages(): Set<String> = runCatching {
        packageManager.getInstalledPackages(0).map { it.packageName }.toSet()
    }.getOrDefault(emptySet())

    /**
     * 把虚拟屏状态渲染到界面。
     *
     * 三处信息刻意分开显示，因为它们回答的是三个不同的问题：
     *  · 主状态行 —— 现在进行到哪一步；
     *  · 明细行 —— 画面更新了几次（**只算内容真正变化的帧**，残影不算）；
     *  · 预览图 —— 黑屏也是信息：说明"屏在了但没东西画"。
     */
    private fun renderVirtualDisplay(state: VirtualDisplaySession.State) {
        val stateView = findViewById<TextView>(R.id.tvVdState)
        val detailView = findViewById<TextView>(R.id.tvVdDetail)

        stateView.text = when {
            !state.running && state.displayId < 0 -> getString(R.string.vd_state_idle)
            state.running && state.displayId > 0 && state.frameWidth > 0 ->
                getString(R.string.vd_running_format, state.displayId, state.frameWidth, state.frameHeight)
            else -> state.message
        }

        detailView.text = when {
            !state.running -> ""
            state.frameWidth == 0 -> state.message
            state.liveFrameCount == 0 -> getString(R.string.vd_no_content)
            else -> getString(R.string.vd_live_frames, state.liveFrameCount) +
                (if (state.appOnDisplay) "" else "\n" + state.message)
        }

        findViewById<MaterialButton>(R.id.btnVdStart).isEnabled = !state.running
        findViewById<MaterialButton>(R.id.btnVdStop).isEnabled = state.running
    }

    /**
     * 订阅预览帧并显示到 ImageView。
     *
     * ## 为什么之前那块屏一直是黑的
     *
     * 采集端其实一直在工作（真机日志里 `画面更新次数: 740`），但**没有任何代码
     * 把帧送到界面** —— `ivVdPreview` 从布局定义至今一次都没被赋过值。
     * 采集到的像素全留在内存里烂掉，用户看到的就是一块永远空白的区域。
     */
    private fun observePreview() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                VirtualDisplaySession.previewFlow.collect { bitmap ->
                    val view = findViewById<ImageView>(R.id.ivVdPreview)
                    view.setImageBitmap(bitmap)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        refreshShizukuState()
    }

    /**
     * 导出日志。
     *
     * 为什么要在 App 里做这件事：Android 4.1 起普通应用读不到别的进程的日志，
     * 而排查虚拟屏问题恰恰要看 system_server 打的 `Permission Denial` 之类。
     * 所以真正的抓取在 UserService（shell 身份）里做，这里只负责落盘与分享。
     *
     * 输出两段：
     *  ① 我们的标签（建屏、候选 flags、搬移结果）—— 直接看结论；
     *  ② 系统侧与虚拟屏相关的日志 —— 看权限/显示服务说了什么。
     */
    private fun exportLogs() {
        val hint = findViewById<TextView>(R.id.tvLogHint)
        hint.text = getString(R.string.log_export_ok, "正在抓取…")

        lifecycleScope.launch {
            // 抓日志用的连接。用完必须 unbind —— 之前每次导出都新建一个 client
            // 并 bind() 却从不 unbind，每点一次"导出日志"就多挂一条 UserService
            // 连接，反复导出会累积。
            val client = ShizukuUserServiceClient(applicationContext)

            val text = withContext(Dispatchers.IO) {
                try {
                    runCatching {
                        client.bind()
                        val service = client.await()
                        if (service == null) {
                            return@runCatching "ERROR: Shizuku 服务未连接（${client.lastError ?: "超时"}）"
                        }
                    val own = service.grabLogcat(
                        "VirtualDisplayCreator|VirtualDisplaySession|Pvz2UserService|DisplayAppLauncher|DisplayTaskMover|Pvz2Capture",
                        800
                    )
                    val system = service.grabLogcat(
                        "DisplayManagerService|Permission Denial|DisplayDeviceRepository|LogicalDisplayMapper|InputDispatcher",
                        400
                    )
                    val display = service.describeDisplay(
                        VirtualDisplaySession.sharedState.value.displayId
                    )
                    // 采集端状态：判断"到底有没有取到帧"的直接依据。
                    val capture = runCatching { service.captureStatus() }
                        .getOrElse { "ERROR: ${it.message}" }
                    val st = VirtualDisplaySession.sharedState.value
                    buildString {
                        appendLine("===== PVZ2 自动化 诊断日志 =====")
                        appendLine("时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
                        appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}  Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                        appendLine()
                        // 这两行是"成没成"的直接答案，放最前面。
                        appendLine("===== 结论 =====")
                        appendLine("建屏是否成功: ${if (st.displayId > 0) "是（displayId=${st.displayId}）" else "否"}")
                        appendLine("屏是否 TRUSTED: ${if (st.displayTrusted) "是（窗口可以挂上去）" else "否（窗口挂不上去）"}")
                        appendLine("应用是否在副屏: ${if (st.appOnDisplay) "是" else "否"}")
                        appendLine("画面更新次数: ${st.liveFrameCount}")
                        appendLine("当前状态: ${st.message}")
                        appendLine()
                        appendLine("建屏时的报告（屏已释放也保留）:")
                        appendLine(st.buildReport.ifBlank { "（没有记录）" })
                        appendLine()
                        appendLine("导出时实时查询: $display")
                        appendLine("采集端: $capture")
                        appendLine()
                        // ★ 事件流水：不受 logcat 被刷屏影响，这是最可靠的一段。
                        appendLine("----- 事件流水（本次运行全过程）-----")
                        if (st.history.isEmpty()) {
                            appendLine("（没有事件记录 —— 说明本次启动后从没走到过建屏）")
                        } else {
                            st.history.forEach { appendLine(it) }
                        }
                        appendLine()
                        appendLine()
                        appendLine("----- ① 本应用（建屏 / 搬移 / 采集）-----")
                        appendLine(own)
                        appendLine()
                        appendLine("----- ② 系统侧（权限 / 显示服务 / 输入分发）-----")
                        appendLine(system)
                    }
                    }.getOrElse { "ERROR: ${it.message}" }
                } finally {
                    // 只断开连接，不杀 UserService —— 虚拟屏可能正在跑，
                    // 那由 vdStop / onDestroy 负责。
                    runCatching { client.unbind(remove = false) }
                }
            }

            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(getExternalFilesDir(null), "logs").apply { mkdirs() }
                    val name = "pvz2-log-${System.currentTimeMillis()}.txt"
                    File(dir, name).apply { writeText(text) }
                }.getOrNull()
            }

            if (file == null) {
                hint.text = getString(R.string.log_export_fail, "写文件失败")
                return@launch
            }
            hint.text = getString(R.string.log_export_ok, file.name)
            shareFile(file)
        }
    }

    /** 弹出系统分享面板，把日志文件发出去。 */
    private fun shareFile(file: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.log_share_title))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.log_share_title)))
        }.onFailure {
            findViewById<TextView>(R.id.tvLogHint).text =
                getString(R.string.log_export_fail, it.message ?: "分享失败")
        }
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

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            BotState.setStatus(getString(R.string.msg_notifications_not_needed))
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
     * 两个步骤的完成判定互相独立，而且**随时可能被外部改变**
     * （用户跳去系统设置授权再回来），所以放在 [onResume] 里也调一次。
     *
     * 只剩两步了：悬浮窗（改成界面内操作）和无障碍（降级方案移除）都已删掉。
     */
    private fun refreshSetupSteps() {
        val done = booleanArrayOf(
            ShizukuBridge.hasPermission(),
            notificationsGranted()
        )
        // 每步"未完成"时右侧显示的动词
        val todoText = intArrayOf(
            R.string.step_todo_auth,
            R.string.step_todo_auth
        )
        // 每步"已完成"时的措辞
        val doneText = intArrayOf(
            R.string.step_granted,
            R.string.step_granted
        )
        val rowIds = intArrayOf(R.id.rowStep1, R.id.rowStep2)
        val numIds = intArrayOf(R.id.stepNum1, R.id.stepNum2)
        val actionIds = intArrayOf(R.id.stepAction1, R.id.stepAction2)

        for (i in done.indices) {
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
