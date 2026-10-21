package com.pvz2.auto

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.MotionEvent
import com.pvz2.auto.core.PreviewPicker
import com.pvz2.auto.core.PreviewTouchMapper
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.pvz2.auto.ui.page.EditorPage
import com.pvz2.auto.ui.page.ScriptPage
import com.pvz2.auto.ui.page.DiagPage
import android.os.Bundle
import android.os.SystemClock
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

        /** 功能页下标。 */
        const val PAGE_SCREEN = 0
        const val PAGE_SCRIPT = 1
        const val PAGE_EDITOR = 2
        const val PAGE_DIAG = 3

        /** 虚拟屏尺寸。建屏时写死 1280×720（见 VirtualDisplaySession）。 */
        const val VD_WIDTH = 1280
        const val VD_HEIGHT = 720

        /**
         * 手指位移超过这个值才算滑动，否则算点击（dp）。
         *
         * 手指点击时落点总会抖几像素。阈值太小会把每次点击都判成滑动，
         * 于是游戏里永远点不中；太大则短距离拖动失效。
         */
        const val SWIPE_THRESHOLD_DP = 12

        /** 滑动时长上下限。太快在低帧率下会被识别成"瞬移"，列表不会跟着滚。 */
        const val MIN_SWIPE_MS = 150
        const val MAX_SWIPE_MS = 1200

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

    /**
     * 第 1 页（屏）的根视图。
     *
     * 页面在 ViewPager2 里会被创建和回收，所以不能像以前那样在 onCreate
     * 里一次性 findViewById —— 控件属于某一页，必须从那一页的根上找。
     */
    private var screenRoot: View? = null

    private var shizukuStateView: TextView? = null
    private var statusView: TextView? = null

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

        // 预览固定在顶部，功能页在下面用 ViewPager2 左右滑动切换。
        // 预览**不能**放进 pager —— 那样翻页时会重建，画面黑一下、采集要重来。
        setupPreviewTouch()
        observePreview()
        setupPager()

        // Shizuku can arrive or disappear while we are on screen, so track both edges.
        ShizukuBridge.addPermissionResultListener(shizukuPermissionListener)
        ShizukuBridge.addBinderReceivedListener(binderReceivedListener)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { BotState.status.collect { statusView?.text = it } }
                // 虚拟屏状态：服务写、界面读。两者是独立组件，所以走进程级单例流。
                launch { VirtualDisplaySession.sharedState.collect { renderVirtualDisplay(it) } }
                // 取点模式：画面顶部显示"请在画面上点一下"。
                // 没有这个提示，用户不知道已经进入取点状态，会以为界面没反应。
                launch {
                    PreviewPicker.mode.collect { mode ->
                        val banner = findViewById<TextView>(R.id.tvPickerHint)
                        if (mode == null) {
                            banner.visibility = View.GONE
                        } else {
                            banner.text = PreviewPicker.hintText(mode)
                            banner.visibility = View.VISIBLE
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ 分页

    /**
     * 三个功能页：屏 / 脚本 / 诊断。
     *
     * 预览在 pager **外面**（固定在顶部），所以翻页不影响画面和采集。
     * 功能页做成 view 而不是 Activity 也是因为这个 ——
     * 独立 Activity 一翻页预览就看不见了，而脚本运行时最需要看画面。
     */
    private fun setupPager() {
        val pager = findViewById<ViewPager2>(R.id.pager)
        pager.adapter = PageAdapter()
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = renderDots(position)
        })
        renderDots(0)
    }

    private fun renderDots(selected: Int) {
        val ids = intArrayOf(R.id.dot0, R.id.dot1, R.id.dot2, R.id.dot3)
        ids.forEachIndexed { i, id ->
            findViewById<TextView>(id).apply {
                text = getString(
                    if (i == selected) R.string.page_dot_on else R.string.page_dot_off
                )
                setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (i == selected) R.color.ink else R.color.ink_faint
                    )
                )
            }
        }
    }

    private inner class PageAdapter : RecyclerView.Adapter<PageAdapter.Holder>() {
        override fun getItemCount(): Int = 4

        /**
         * ★ 必须重写，否则启动即崩。
         *
         * `RecyclerView.Adapter` 的默认实现**永远返回 0**。不重写的话
         * ViewPager2 给三页都传 viewType=0，三页全 inflate 成 page_screen；
         * 接着 onBindViewHolder 拿第 2/3 页的控件（scriptList、etTapX…）
         * 去那个错误的布局上找 —— 全部返回 null，赋给非空属性直接 NPE。
         *
         * 页下标恰好就是视图类型，一一对应。
         */
        override fun getItemViewType(position: Int): Int = position

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val layout = when (viewType) {
                PAGE_SCREEN -> R.layout.page_screen
                PAGE_SCRIPT -> R.layout.activity_script
                PAGE_EDITOR -> R.layout.page_editor
                else -> R.layout.activity_diagnostics
            }
            return Holder(layoutInflater.inflate(layout, parent, false))
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            // 单页绑定失败不该让整个应用起不来。
            // 一个页面的控件名写错了，用户还能用其它几页，也能把日志导出来；
            // 直接崩掉的话什么线索都拿不到。
            //
            // ★ 这里的分支必须和 onCreateViewHolder 用同一组常量。
            //   之前加了第 3 页（编辑）却没同步改这里，位置 2 走到 else
            //   绑成了 DiagPage，而去拿 page_editor 布局里的控件 ——
            //   findViewById 返回 null，构造就抛 NPE，那一页从此空白。
            runCatching {
                when (position) {
                    PAGE_SCREEN -> bindScreenPage(holder.itemView)
                    PAGE_SCRIPT -> ScriptPage(this@MainActivity, holder.itemView).bind()
                    PAGE_EDITOR -> EditorPage(this@MainActivity, holder.itemView).bind()
                    PAGE_DIAG -> DiagPage(this@MainActivity, holder.itemView).bind()
                }
            }.onFailure {
                Log.e(TAG, "第 $position 页绑定失败", it)
            }
        }

        inner class Holder(v: View) : RecyclerView.ViewHolder(v)
    }

    /**
     * 第 1 页：屏。
     *
     * 这一页会被 ViewPager2 反复创建和回收，所以控件引用每次都以 [root] 为准。
     */
    private fun bindScreenPage(root: View) {
        screenRoot = root
        shizukuStateView = root.findViewById(R.id.tvShizukuState)
        statusView = root.findViewById(R.id.tvStatus)

        root.findViewById<MaterialButton>(R.id.btnStartShizuku).setOnClickListener {
            AutomationService.startShizuku(this)
        }
        root.findViewById<MaterialButton>(R.id.btnStop).setOnClickListener {
            AutomationService.stop(this)
        }
        root.findViewById<MaterialButton>(R.id.btnVdStart).setOnClickListener {
            val pkg = ChannelPrefs.resolvePackage(this)
            if (pkg.isNullOrBlank()) {
                // 没有可用包名时别静默失败 —— 直接告诉用户该做什么。
                root.findViewById<TextView>(R.id.tvChannelHint).text =
                    getString(R.string.channel_not_found)
            } else {
                AutomationService.vdStart(this, pkg)
            }
        }
        root.findViewById<MaterialButton>(R.id.btnVdStop).setOnClickListener {
            AutomationService.vdStop(this)
        }
        // 导出日志：真机上没电脑连着时，这是唯一能把现场发给别人的办法。
        root.findViewById<MaterialButton>(R.id.btnExportLog).setOnClickListener {
            exportLogs()
        }

        setupChannelPicker(root)
        refreshShizukuState()
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
    private fun setupChannelPicker(root: View) {
        val spinner = root.findViewById<Spinner>(R.id.spChannel)
        val customInput = root.findViewById<EditText>(R.id.etCustomPackage)
        val hint = root.findViewById<TextView>(R.id.tvChannelHint)

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

        // 建屏/停屏按钮在**第 1 页**里，不在 Activity 的布局上。
        //
        // 之前这里是 Activity 级的 findViewById，但那两个按钮随分页改造
        // 搬进了 page_screen.xml —— Activity 上找不到，返回 null，
        // 直接 NPE 闪退（真机日志 MainActivity.kt:419）。
        //
        // 必须从 screenRoot 找，而且要容忍"那一页还没被创建"的情况：
        // ViewPager2 只保证当前页存在，翻走之后可能被回收。
        screenRoot?.let { root ->
            root.findViewById<MaterialButton>(R.id.btnVdStart)?.isEnabled = !state.running
            root.findViewById<MaterialButton>(R.id.btnVdStop)?.isEnabled = state.running
        }
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
    /**
     * 让预览画面可以点 —— 点哪就往虚拟屏的哪注入一次点击。
     *
     * ## 为什么必须有这个
     *
     * 虚拟屏**没有物理触摸面板**。用户在别处怎么点都碰不到它，
     * 所以"把游戏点到某个界面"这件事，本来只能靠脚本自己走。
     * 但脚本还在调试，它又需要游戏先在某个界面才能验证 —— 死循环。
     *
     * 这条路径把这个环打开了：手指点预览，坐标换算后注入虚拟屏，
     * 于是可以手动把游戏点登录、翻菜单、进关卡。
     *
     * ## 为什么在 ACTION_UP 才注入
     *
     * DOWN 就注入的话，用户手指还在屏幕上滑动时就已经点下去了，
     * 想取消都来不及。UP 才注入等于"按下-抬起"才算一次点击，
     * 和真实触摸的语义一致。
     */
    private fun setupPreviewTouch() {
        val preview = findViewById<ImageView>(R.id.ivVdPreview)
        preview.isClickable = true

        // 手势判定：按下记起点，抬起时按位移决定是点击还是滑动。
        //
        // ## 为什么不能只有点击
        //
        // 游戏里翻世界列表、拖动植物、关卡片都要滑动。只支持点击的话，
        // 用户在预览上怎么拖都没用 —— 而那正是最自然的操作直觉。
        //
        // ## 阈值怎么定的
        //
        // 用手指在预览上点，实际落点总会抖几像素。阈值太小会把每次点击
        // 都判成滑动（于是游戏里永远点不中）；太大则短距离拖动失效。
        // 按 dp 而不是 px：不同密度屏幕上同样的手指位移应该得到同样的判定。
        var downX = 0f
        var downY = 0f
        var downAt = 0L

        preview.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downAt = SystemClock.uptimeMillis()
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val elapsed = (SystemClock.uptimeMillis() - downAt).toInt()

                    // ★ 取点模式优先：不能让取点顺手把游戏点一下。
                    //
                    //   自动精灵 也是这个模型 —— 武装一个模式后，用户的触摸
                    //   属于"选位置"，不属于"操作游戏"，两者必须互斥。
                    PreviewPicker.mode.value?.let { mode ->
                        val st = VirtualDisplaySession.sharedState.value
                        val iw = st.frameWidth.takeIf { it > 0 } ?: VD_WIDTH
                        val ih = st.frameHeight.takeIf { it > 0 } ?: VD_HEIGHT
                        val a = PreviewTouchMapper.map(downX, downY, view.width, view.height, iw, ih)
                        val b = PreviewTouchMapper.map(event.x, event.y, view.width, view.height, iw, ih)
                        if (a != null && b != null) {
                            val left = minOf(a.x, b.x)
                            val top = minOf(a.y, b.y)
                            val right = maxOf(a.x, b.x)
                            val bottom = maxOf(a.y, b.y)
                            val isPoint = mode == PreviewPicker.Mode.POINT

                            PreviewPicker.deliver(
                                PreviewPicker.Result(
                                    mode = mode,
                                    x = if (isPoint) b.x else left,
                                    y = if (isPoint) b.y else top,
                                    w = if (isPoint) 0 else (right - left),
                                    h = if (isPoint) 0 else (bottom - top),
                                    crop = if (mode == PreviewPicker.Mode.TEMPLATE) {
                                        cropTemplate(left, top, right - left, bottom - top)
                                    } else null,
                                    screenWidth = iw,
                                    screenHeight = ih
                                )
                            )
                        } else {
                            // 落在留边上，当作取消
                            PreviewPicker.cancel()
                        }
                        return@setOnTouchListener true
                    }

                    val state = VirtualDisplaySession.sharedState.value
                    val imageWidth = state.frameWidth.takeIf { it > 0 } ?: VD_WIDTH
                    val imageHeight = state.frameHeight.takeIf { it > 0 } ?: VD_HEIGHT

                    fun mapPoint(vx: Float, vy: Float) = PreviewTouchMapper.map(
                        viewX = vx, viewY = vy,
                        viewWidth = view.width, viewHeight = view.height,
                        imageWidth = imageWidth, imageHeight = imageHeight
                    )

                    val start = mapPoint(downX, downY)
                    val end = mapPoint(event.x, event.y)
                    // 起点或终点落在留边上：那里没有画面，什么都不做 ——
                    // 否则会误触屏幕边缘的按钮。
                    if (start == null || end == null) return@setOnTouchListener true

                    val dx = (end.x - start.x).toDouble()
                    val dy = (end.y - start.y).toDouble()
                    val distance = kotlin.math.hypot(dx, dy)

                    if (distance < swipeThresholdPx()) {
                        AutomationService.manualTap(this, end.x, end.y)
                    } else {
                        // 时长下限保护：太快的手势在低帧率下会被识别成"瞬移"，
                        // 游戏里的列表不会跟着滚。
                        AutomationService.manualSwipe(
                            this,
                            start.x, start.y, end.x, end.y,
                            elapsed.coerceIn(MIN_SWIPE_MS, MAX_SWIPE_MS)
                        )
                    }
                    true
                }

                // MOVE 必须消费掉，否则系统不会继续给我们后续事件。
                MotionEvent.ACTION_MOVE -> true

                else -> false
            }
        }
    }

    /**
     * 从当前预览帧裁一块出来当模板。
     *
     * 直接裁**采集到的原始帧**，而不是截屏控件 —— 控件上的图已经被缩放/留边，
     * 裁出来会带黑边和缩放失真，匹配时会明显掉分。
     *
     * 裁失败返回 null（帧还没到的时候），调用方会当成"没取到图"处理。
     */
    private fun cropTemplate(x: Int, y: Int, w: Int, h: Int): android.graphics.Bitmap? {
        if (w <= 0 || h <= 0) return null
        val frame = VirtualDisplaySession.previewFlow.value ?: return null
        val left = x.coerceIn(0, frame.width - 1)
        val top = y.coerceIn(0, frame.height - 1)
        val width = w.coerceAtMost(frame.width - left)
        val height = h.coerceAtMost(frame.height - top)
        if (width <= 0 || height <= 0) return null
        return runCatching { android.graphics.Bitmap.createBitmap(frame, left, top, width, height) }
            .getOrNull()
    }

    /** 手指位移超过这个距离才算滑动（dp 转 px）。 */
    private fun swipeThresholdPx(): Double =
        SWIPE_THRESHOLD_DP * resources.displayMetrics.density.toDouble()

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
        // tvLogHint 在第 1 页里，必须从 screenRoot 找 —— Activity 级
        // findViewById 会返回 null，直接 NPE（真机日志 MainActivity.kt:534）。
        screenRoot?.findViewById<TextView>(R.id.tvLogHint)?.text =
            getString(R.string.log_export_ok, "正在抓取…")

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
                screenRoot?.findViewById<TextView>(R.id.tvLogHint)?.text =
                    getString(R.string.log_export_fail, "写文件失败")
                return@launch
            }
            screenRoot?.findViewById<TextView>(R.id.tvLogHint)?.text =
                getString(R.string.log_export_ok, file.name)
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
            screenRoot?.findViewById<TextView>(R.id.tvLogHint)?.text =
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
        shizukuStateView?.text = when {
            !ShizukuBridge.isBinderAlive() -> getString(R.string.shizuku_not_running)

            ShizukuBridge.hasPermission() -> getString(
                R.string.shizuku_ready,
                runCatching { Shizuku.getUid() }.getOrDefault(-1)
            )

            else -> getString(R.string.shizuku_no_permission)
        }

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

    /** Android 13 起通知权限才需要单独申请；更低版本视为已授予。 */
    private fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    override fun onResume() {
        super.onResume()
        // 用户可能刚从系统设置里授权完回来，回来时状态要立刻反映出来。
        refreshShizukuState()
    }
}