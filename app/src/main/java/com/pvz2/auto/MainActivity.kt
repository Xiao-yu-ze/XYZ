package com.pvz2.auto

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
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
import androidx.core.content.ContextCompat
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
import org.opencv.core.Mat
import com.pvz2.auto.service.RecognitionSelfTest
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

    private companion object {
        const val TAG = "MainActivity"

        /** 虚拟屏尺寸。建屏时写死 1280×720（见 VirtualDisplaySession）。 */
        const val VD_WIDTH = 1280
        const val VD_HEIGHT = 720

        /**
         * 识别自检用的模板。
         *
         * 挑的是"画面里很容易出现"的特征：开始游戏按钮、通用叉叉、暂停。
         * 它们的分数能直接说明识别链路（模板加载 → 缩放 → ROI → 匹配）通不通。
         */
        val PROBE_TEMPLATES = listOf(
            "Start_game/开始游戏.png",
            "Start_game/叉叉.png",
            "General/暂停.png"
        )
    }

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

        // 只保留注入自检。
        //
        // 抓帧 / 探针 / 验证链那几个临时工具已经删掉：抓帧和建屏探针的功能
        // 被主界面的启动按钮覆盖，验证链依赖的模板集也早已不存在。
        // 留着只会让"该点哪个"变成一道谜题。
        findViewById<MaterialButton>(R.id.btnTestTap).setOnClickListener {
            testVirtualDisplayTap()
        }
        findViewById<MaterialButton>(R.id.btnRecogTest).setOnClickListener {
            runRecognitionSelfTest()
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
        val fpsView = findViewById<TextView>(R.id.tvVdFps)
        val emptyView = findViewById<TextView>(R.id.tvPreviewEmpty)

        // 标题行讲"在跑 / 多大"，或者**讲错误**。
        //
        // ★ 之前这里是 `!running && displayId < 0 -> vd_state_idle` 抢先返回，
        //   而 detail 行又在 `!running` 时置空 —— 于是"建虚拟屏失败"、
        //   "Shizuku 服务不可用（无法建屏）"这些**真正有用的错误信息，
        //   在界面上任何位置都不出现**，用户只看到一句"虚拟屏未启动"，
        //   完全不知道出了什么事。
        //
        //   现在：没在跑的时候，只要 message 不是"未启动"这类无信息量的默认值，
        //   就把它当标题显示出来。
        stateView.text = when {
            state.running && state.displayId > 0 && state.frameWidth > 0 ->
                getString(R.string.vd_running_format, state.frameWidth, state.frameHeight)
            !state.running && state.message != getString(R.string.vd_state_idle) ->
                state.message
            !state.running -> getString(R.string.vd_state_idle)
            // 到这里只剩一种情况：running 但 displayId<=0 或 frameWidth==0，
            // 也就是"正在建屏/搬应用"。此时 message 可能是 "未启动" 这个默认值
            // （State 初始值没被覆盖），直接显示它会**撒谎**成"未启动"。
            state.message != getString(R.string.vd_state_idle) -> state.message
            else -> getString(R.string.vd_building)
        }

        // 帧率单独一列，只在真的在跑时出现。它是遥测，不是状态。
        val showFps = state.running && state.frameWidth > 0
        fpsView.visibility = if (showFps) View.VISIBLE else View.GONE
        if (showFps) {
            fpsView.text = getString(R.string.vd_live_frames, state.fps)
            // 帧率为 0 说明这一秒一帧都没取到 —— 用待办色提示"还没好"，
            // 而不是红色（红色留给真正的错误）。
            fpsView.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (state.fps == 0) R.color.state_todo else R.color.ink_faint
                )
            )
        }

        // 详情行：补充说明，不重复标题。
        //
        // ★ 不要加 `frameWidth == 0 -> ""`。
        //
        //   "在跑、屏建好了、但 frameWidth 还是 0" 恰恰就是"建屏成功但取不到
        //   画面"这个状态，而它正是 vd_no_content 要解释的事。之前先返回空串，
        //   把那条提示整个吃掉了 —— 用户看到屏建好了但什么都不显示，
        //   也不知道是游戏没搬上去还是卡住了。
        //
        //   现在让它落到 fps == 0 那一支（此时 fps 必然是 0）。
        detailView.text = when {
            !state.running -> ""
            state.fps == 0 -> getString(R.string.vd_no_content)
            else -> if (state.appOnDisplay) "" else state.message
        }

        // 空状态文案：没启动 → "尚未启动"；建好了但还没出帧 → "等待画面…"。
        // 不解释一句的话，这块纯黑区域看起来就像坏了。
        //
        // 文案在这里定，显隐统一交给 updatePreviewEmptyState()。
        emptyView.text = if (state.running) {
            getString(R.string.vd_preview_waiting)
        } else {
            getString(R.string.vd_preview_empty)
        }
        updatePreviewEmptyState()

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
                    findViewById<ImageView>(R.id.ivVdPreview).setImageBitmap(bitmap)
                    updatePreviewEmptyState()
                }
            }
        }
    }

    /**
     * 预览区空提示的唯一显隐入口。
     *
     * ## 为什么要收敛成一个函数
     *
     * 之前状态刷新（[renderVirtualDisplay]）和帧到达（[observePreview]）各写各的
     * `setVisibility`，两边判据还不一样。改一处忘一处，就会出现
     * "有画面但提示还盖在上面"这种问题。
     *
     * 判据只有一个：**ImageView 里有没有位图**。
     * 帧率、running 状态都跟"有没有图"是两回事 ——
     * 帧率掉到 0 时画面上可能还挂着上一帧，那时不该冒出"尚未启动"。
     */
    private fun updatePreviewEmptyState() {
        val hasFrame = findViewById<ImageView>(R.id.ivVdPreview).drawable != null
        findViewById<TextView>(R.id.tvPreviewEmpty).visibility =
            if (hasFrame) View.GONE else View.VISIBLE
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
                        appendLine("实时帧率: ${st.fps} fps（上限 $VirtualDisplaySession.MAX_FPS）")
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

    /**
     * 注入自检：在虚拟屏上点一下**指定坐标**，并把四条路径的结果直接显示出来。
     *
     * ## 为什么要这个按钮
     *
     * "游戏点不动"至少有四种原因，而它们在日志里长得差不多：
     *  1. 事件打到了错误的 display；
     *  2. 事件发对了，但那块屏上没有可触摸窗口；
     *  3. 识别没命中，压根没触发点击；
     *  4. 脚本没跑。
     *
     * 这个按钮**绕开 3 和 4**，只测 1 和 2：不依赖识别、不依赖脚本，
     * 直接用 AIDL 的 `tapOnDisplay` 往会话当前的 displayId 发一次点击。
     *
     * ## 为什么一次试四条路径
     *
     * 单看"被拒"两个字无法定位原因。四条路径的**失败方式**不同，
     * 组合起来正好能把原因分开（见下面的判读逻辑）。
     */
    private fun mark(ok: Boolean): String = if (ok) "✓ 接受" else "✗ 被拒"

    /**
     * 识别自检：把虚拟屏当前的画面跑一遍模板匹配，报出**分数和命中位置**。
     *
     * ## 为什么要它
     *
     * 注入已验证能进虚拟屏。接下来要回答"识别准不准"，而这件事没法从日志推断：
     * 分数 0.65 是模板不对还是阈值定高了，只能看具体数字；
     * 命中了却点不中，是框算错了还是坐标没换算，只能看命中框。
     *
     * 这个自检**不点任何东西**，只报数字。
     */
    private fun runRecognitionSelfTest() {
        val resultView = findViewById<TextView>(R.id.tvTestTapResult)
        val bitmap = VirtualDisplaySession.previewFlow.value

        if (bitmap == null) {
            resultView.setTextColor(ContextCompat.getColor(this, R.color.state_bad))
            resultView.text = getString(R.string.recog_no_frame)
            return
        }

        resultView.setTextColor(ContextCompat.getColor(this, R.color.ink_muted))
        resultView.text = getString(R.string.recog_running)

        lifecycleScope.launch {
            val text = withContext(Dispatchers.Default) {
                try {
                    if (!org.opencv.android.OpenCVLoader.initLocal()) {
                        return@withContext "ERROR: OpenCV 加载失败"
                    }

                    // Bitmap -> Mat。**不 recycle**：这张 Bitmap 归 previewFlow 所有，
                    // 回收了 ImageView 会拿到一张废图。
                    val mat = Mat()
                    org.opencv.android.Utils.bitmapToMat(bitmap, mat)

                    val probes = try {
                        RecognitionSelfTest(applicationContext).run(
                            frame = mat,
                            templates = PROBE_TEMPLATES
                        )
                    } finally {
                        mat.release()
                    }

                    if (probes.isEmpty()) {
                        "没有任何结果 —— 帧为空或模板全部没加载上。"
                    } else {
                        buildString {
                            appendLine("画面 ${bitmap.width}×${bitmap.height}")
                            appendLine()
                            probes.forEach { appendLine(it.toString()) }
                            appendLine()
                            val passed = probes.count { it.passed }
                            appendLine("命中 $passed / ${probes.size}")
                            if (passed == 0) {
                                appendLine("全都没到阈值。看分数定位原因：")
                                appendLine("  · 分数 < 0.3   → 画面里没有这东西，模板要换")
                                appendLine("  · 分数 0.5~0.7 → 找对了，但缩放或通道有偏差")
                                appendLine("  · 分数 -1      → 模板没加载（路径错）")
                            }
                        }
                    }
                } catch (t: Throwable) {
                    "ERROR: ${t.message}"
                }
            }
            resultView.text = text
            resultView.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ink_muted))
        }
    }

    private fun testVirtualDisplayTap() {
        val resultView = findViewById<TextView>(R.id.tvTestTapResult)
        val displayId = VirtualDisplaySession.sharedState.value.displayId

        if (displayId <= 0) {
            resultView.setTextColor(ContextCompat.getColor(this, R.color.state_bad))
            resultView.text = getString(R.string.test_tap_no_display)
            return
        }

        // 坐标从输入框读，空着就用屏幕正中。
        // 虚拟屏是 1280×720，中心 (640, 360)。
        val x = findViewById<EditText>(R.id.etTapX).text.toString().toIntOrNull()
            ?.coerceIn(0, VD_WIDTH - 1) ?: (VD_WIDTH / 2)
        val y = findViewById<EditText>(R.id.etTapY).text.toString().toIntOrNull()
            ?.coerceIn(0, VD_HEIGHT - 1) ?: (VD_HEIGHT / 2)

        resultView.setTextColor(ContextCompat.getColor(this, R.color.ink_muted))
        resultView.text = getString(R.string.test_tap_running, displayId)

        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                val client = ShizukuUserServiceClient(applicationContext)
                try {
                    client.bind()
                    val service = client.await()
                        ?: return@withContext "ERROR: Shizuku 服务未连接（${client.lastError ?: "超时"}）"

                    // 四条路径同时试。它们失败的**方式**不一样，正好能把原因分开：
                    //
                    //   A. injectInputEvent + setDisplayId(虚拟屏)  ← 线上用的这条
                    //   B. injectInputEvent + display 0             ← 对照组：注入本身通不通
                    //   C. `input -d <id> tap`                      ← shell 的官方路径
                    //   D. `input tap`                              ← 对照组
                    //
                    // 判读：
                    //   A✗ B✓ C✓ → 是 setDisplayId/副屏路由的问题
                    //   A✗ B✗ C✗ → 权限或 InputManager 层的问题
                    //   A✗ C✓    → 我们的注入实现有 bug，改用 shell 路径
                    val report = StringBuilder()
                    report.appendLine("display=$displayId  坐标 ($x, $y)")
                    report.appendLine()

                    val a = runCatching { service.tapOnDisplay(displayId, x, y, 80) }
                        .getOrDefault(false)
                    report.appendLine("A 直注 InputInjector → $displayId : ${mark(a)}")

                    val b = runCatching { service.tapOnDisplay(0, x, y, 80) }
                        .getOrDefault(false)
                    report.appendLine("B 直注 → display 0（对照）        : ${mark(b)}")

                    val cOut = runCatching {
                        service.exec("input -d $displayId tap $x $y")
                    }.getOrDefault("")
                    // `input` 失败只往 stderr 写、退出码仍是 0，只能看输出。
                    val c = cOut.isBlank()
                    report.appendLine("C shell input -d $displayId（对照）: ${mark(c)}")
                    if (!c) report.appendLine("   └ $cOut")

                    val dOut = runCatching { service.exec("input tap $x $y") }.getOrDefault("")
                    val d = dOut.isBlank()
                    report.appendLine("D shell input（display 0）        : ${mark(d)}")
                    if (!d) report.appendLine("   └ $dOut")

                    report.appendLine()
                    report.appendLine("判读：")
                    report.appendLine(
                        when {
                            a -> "✓ A 通 —— 直注已能被系统接受，可以用它做自动化的动作层。"
                            !a && c -> "A 不通但 C 通 → 直注实现仍有问题，继续用 shell。"
                            !a && !c -> "两条都不通，看下面的窗口分布和权限。"
                            else -> "状态异常。"
                        }
                    )

                    val perm = runCatching {
                        service.exec("dumpsys package com.android.shell | grep -i INJECT_EVENTS")
                    }.getOrDefault("")
                    report.appendLine()
                    report.appendLine("shell 的 INJECT_EVENTS：")
                    report.appendLine(if (perm.isBlank()) "  （查不到，可能没这个权限）" else "  ${perm.trim()}")

                    val win = runCatching { service.exec("dumpsys window windows | grep -E 'Window #|mDisplayId'") }
                        .getOrDefault("")
                    report.appendLine()
                    report.appendLine("窗口分布：")
                    report.appendLine(win.trim().take(600))

                    report.toString()
                } catch (t: Throwable) {
                    "ERROR: ${t.message}"
                } finally {
                    runCatching { client.unbind(remove = false) }
                }
            }

            resultView.text = text
            resultView.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ink_muted))
        }
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
