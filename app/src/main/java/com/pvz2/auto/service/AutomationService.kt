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
import com.pvz2.auto.IPvz2Service
import com.pvz2.auto.MainActivity
import com.pvz2.auto.R
import com.pvz2.auto.core.BotState
import com.pvz2.auto.core.ScreenCapturer
import com.pvz2.auto.core.TemplateMatcher
import com.pvz2.auto.core.TouchInjector
import com.pvz2.auto.maa.OcrEngine
import com.pvz2.auto.maa.PipelineRunner
import com.pvz2.auto.maa.ProbeTargets
import com.pvz2.auto.maa.RecognitionProbe
import com.pvz2.auto.maa.Recognizer
import com.pvz2.auto.maa.VerifyChain
import com.pvz2.auto.shizuku.ShizukuBridge
import com.pvz2.auto.shizuku.ShizukuScreenCapturer
import com.pvz2.auto.shizuku.ShizukuTouchInjector
import com.pvz2.auto.shizuku.ShizukuUserServiceClient
import com.pvz2.auto.shizuku.VirtualDisplayCreator
import com.pvz2.auto.shizuku.VirtualDisplayFlags
import com.pvz2.auto.shizuku.VirtualDisplaySurface
import com.pvz2.auto.task.Pvz2Bot
import com.pvz2.auto.task.Pvz2Tasks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import java.io.File
import java.io.FileOutputStream

/**
 * 前台服务：所有长生命周期资源的宿主。
 *
 *  * 自动化循环（始终在 [Dispatchers.IO] 上，不碰主线程）；
 *  * Shizuku UserService 绑定（截图 / 注入 / 建虚拟屏全在那一侧）；
 *  * 虚拟屏与它的帧消费端（ImageReader）。
 *
 * 它是前台服务是为了让进程在游戏占屏时有足够优先级活着。前台服务**类型**必须与
 * manifest 一致，否则 Android 14 会直接抛异常：
 *
 *  * 本应用只有一条路 —— Shizuku -> `specialUse`。
 *    （MediaProjection + 无障碍的降级路线已按需求整体移除：没有 MediaProjection
 *     会话，也就不再需要 `mediaProjection` 这个类型。）
 *  * 悬浮面板也已移除：开始/暂停/停止全部在本应用界面内操作。
 */
class AutomationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    private lateinit var shizukuClient: ShizukuUserServiceClient

    /** 虚拟屏会话：屏由它持有，生命周期跟随本服务，而不是某个一次性任务。 */
    private lateinit var vdSession: VirtualDisplaySession

    /** 本次销毁是否由用户主动触发（决定要不要释放虚拟屏）。 */
    @Volatile
    private var userStopped = false

    private var capturer: ScreenCapturer? = null
    private var injector: TouchInjector? = null
    private var matcher: TemplateMatcher? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        shizukuClient = ShizukuUserServiceClient(this)
        createNotificationChannel()
        // 状态只往通知栏与界面走，不再有悬浮面板需要镜像。

        // 虚拟屏会话：把"建屏 / 搬应用 / 出帧"收进一个长期对象，
        // 让屏活到用户点停止为止 —— 这是能真正观察和验证的前提。
        vdSession = VirtualDisplaySession(
            callerContext = this,
            scope = scope,
            // ★ 缓存已连上的 service，**不要**每次调用都 bind + await。
            //
            // 之前的写法是「每次调用都 bind() 然后 await(5s)」。而
            // VirtualDisplaySession 一次 start() 里要调 5 次（建屏、查屏状态、
            // 查窗口、搬应用…），等于 5 次握手。真机日志里
            // 「屏建出来随即消失」很可能就是这个造成的：
            // 重复 bind 打乱了连接状态，服务被重建 → 屏跟着释放。
            shizukuServiceProvider = {
                shizukuClient.peek() ?: run {
                    if (!ShizukuBridge.hasPermission()) {
                        null
                    } else {
                        shizukuClient.bind()
                        shizukuClient.await()
                    }
                }
            },
        )
    }

    /** 供界面读取虚拟屏状态。 */
    fun virtualDisplayState(): kotlinx.coroutines.flow.StateFlow<VirtualDisplaySession.State> =
        vdSession.state

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SHIZUKU -> {
                startForegroundCompat()
                launchLoop { runShizukuLoop() }
            }

            ACTION_GRAB_FRAME -> scope.launch { grabOneFrame() }

            ACTION_PROBE -> scope.launch { runProbe() }

            // ★ 虚拟屏：**持有**一块屏直到用户停止。这是产品形态的入口，
            //   与下面的 ACTION_VD_PROBE（一次性探测、跑完就释放）不同。
            ACTION_VD_START -> {
                startForegroundCompat()
                // 包名由界面传进来（渠道选择），这里不再有硬编码默认值 ——
                // 硬编码过 OPPO 渠道的包名，用户装别的渠道就启动不了。
                val pkg = intent.getStringExtra(EXTRA_TARGET_PACKAGE)
                if (pkg.isNullOrBlank()) {
                    BotState.setStatus("没有指定游戏包名")
                } else {
                    scope.launch { vdSession.start(pkg) }
                }
            }

            ACTION_VD_STOP -> {
                // ★ 必须先 startForeground。
                //
                //   本 action 是用 `startForegroundService()` 拉起来的
                //   （见 vdStop()），系统要求 5 秒内调 startForeground()，
                //   否则 Android 12+ 直接抛 ForegroundServiceDidNotStartInTimeException
                //   把进程干掉。而这个分支只做"停屏 + stopSelf"，原来一次都没调，
                //   属于**必然崩溃**的路径 —— 只是停得快时来不及触发。
                startForegroundCompat()

                // 用户主动要求释放：标记后连服务一起停（onDestroy 会据此释放屏）。
                userStopped = true
                scope.launch {
                    vdSession.stop()
                    stopSelf()
                }
            }

            // 一次性探测（诊断用，保留）：建屏 + 等首帧 + 存 PNG，然后释放。
            ACTION_VD_PROBE -> {
                startForegroundCompat()
                launchLoop { runVirtualDisplayProbe() }
            }

            // 方案 C：识别链验证。干跑（只识别不点）与实跑（会点击）分开两个入口，
            // 避免误触 —— 第一次上真机必须走干跑。
            ACTION_VERIFY_DRY -> {
                startForegroundCompat()
                launchLoop { withBootstrap { runVerifyChain(dryRun = true) } }
            }

            ACTION_VERIFY_RUN -> {
                startForegroundCompat()
                launchLoop { withBootstrap { runVerifyChain(dryRun = false) } }
            }
        }
        // 用 STICKY：虚拟屏挂在本服务上，服务被系统回收时屏会跟着没。
        // 重新创建后 onCreate 会重建 vdSession，但**不会**自动重新建屏 ——
        // 屏一旦丢了就得用户重新点一次，这是当前实现的已知限制。
        // 之所以不选 NOT_STICKY：Android 16 对 specialUse 前台服务回收更积极，
        // 实测点完「启动」后没一会儿服务就没了、屏也跟着释放
        //（第一次真机日志就是这个现象：导出时屏已经消失）。
        return START_STICKY
    }

    override fun onDestroy() {
        loopJob?.cancel()
        loopJob = null
        // ⚠️ 这里**不能**主动 vdSession.stop()。
        //
        // 服务被系统回收（内存压力、厂商清理）时也会走到 onDestroy，
        // 此时主动释放会把虚拟屏一起干掉 —— 用户看到的就是"屏莫名其妙没了"。
        // 屏的释放只应该由两条路径触发：
        //   ① 用户点「停止并释放」；
        //   ② 进程真的要结束（由系统回收 display 资源兜底）。
        // 第一次真机日志里 `虚拟屏已释放 displayId=44` 就是这个误伤。
        capturer = null
        injector?.release()
        injector = null
        matcher?.release()
        matcher = null

        runCatching { shizukuClient.unbind(remove = true) }


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
            fail("尚未获得 Shizuku 权限")
            return
        }

        shizukuClient.bind()
        val service = shizukuClient.await()
        if (service == null) {
            fail("Shizuku UserService 未连接：${shizukuClient.lastError ?: "超时"}")
            return
        }

        // Cheap end-to-end proof that the privileged shell actually works before we start
        // pumping frames through it.
        val displaySize = runCatching { service.getDisplaySize() }.getOrNull().orEmpty()
        if (displaySize.isEmpty()) {
            fail("特权 shell 无响应")
            return
        }
        BotState.setStatus("Shizuku 就绪（$displaySize）")

        // ★ 识别优先用虚拟屏帧，拿不到再回退 screencap。
        //
        //   `screencap -p` 那条路每帧要 fork 两个进程 + PNG 编码 + PNG 解码，
        //   实测 40~90ms；而虚拟屏的帧本来就以 60fps 在出，复制一份只要 1~2ms。
        //
        //   Boss 放技能的窗口只有一两百毫秒 —— 这 40~90ms 的差距直接决定
        //   能不能打断。这是"识别不够快"的头号原因。
        capturer = VirtualDisplayCapturer(
            vdSession,
            ShizukuScreenCapturer(shizukuClient)
        )
        injector = ShizukuTouchInjector(shizukuClient)
        runLoop()
    }

    /**
     * 旧的「识别到就点」线性循环。
     *
     * 保留它是因为 maa/ 那条链还没在真机验证过 —— 删掉等于把唯一跑通过的退路拆了。
     * 等 pipeline 路径稳定后再删。
     */
    private suspend fun runLoop() {
        if (!loadMatcher()) return
        val activeCapturer = capturer ?: return
        val activeInjector = injector ?: return
        val activeMatcher = matcher ?: return
        Pvz2Bot(activeCapturer, activeInjector, activeMatcher, Pvz2Tasks.defaultActions()).run()
    }

    /**
     * Shizuku 绑定 + 截图后端就绪检查，成功后才执行 [block]。
     *
     * 抽出来是因为验证链、干跑、实跑三条路径都要这段前置：绑定 UserService、
     * 确认特权 shell 真的能响应（`getDisplaySize` 往返是最便宜的端到端证明）。
     */
    private suspend fun withBootstrap(block: suspend () -> Unit) {
        if (!ShizukuBridge.hasPermission()) {
            fail("需要 Shizuku 权限（用来截图与点击）")
            return
        }
        shizukuClient.bind()
        val service = shizukuClient.await()
        if (service == null) {
            fail("Shizuku UserService 未连接：${shizukuClient.lastError ?: "超时"}")
            return
        }
        val displaySize = runCatching { service.getDisplaySize() }.getOrNull().orEmpty()
        if (displaySize.isEmpty()) {
            fail("特权 shell 无响应")
            return
        }
        BotState.setStatus("Shizuku 就绪 ($displaySize)")

        capturer = ShizukuScreenCapturer(shizukuClient)
        injector = ShizukuTouchInjector(shizukuClient)
        block()
    }

    /**
     * 方案 C：跑无尽挑战入口的**声明式识别链**（不做任何 Custom 动作）。
     *
     * 这条链覆盖模板匹配 / 多模板 / OCR 三种识别通道，用于回答
     * 「识别层在真机上到底准不准」—— 这是决定要不要移植 MaaPVZ 完整引擎的前提。
     */
    private suspend fun runVerifyChain(dryRun: Boolean) {
        if (!OpenCVLoader.initLocal()) {
            fail("OpenCV 原生库加载失败")
            return
        }
        val activeCapturer = capturer ?: return
        val activeInjector = injector ?: return

        val recognizer = Recognizer(this)
        // OCR 加载失败不阻断：模板匹配那两条节点仍然会跑，只是 OCR 节点永远不命中。
        val ocr = OcrEngine(this).also { it.load() }
        recognizer.ocr = ocr

        try {
            BotState.setStatus(if (dryRun) "干跑中（不点击）" else "运行中（会点击）")
            PipelineRunner(
                context = this,
                capturer = activeCapturer,
                injector = activeInjector,
                recognizer = recognizer,
                dryRun = dryRun
            ).run(VerifyChain.ENTRY)
            BotState.setStatus("识别链走完（详见日志 PipelineRunner / NodeGraph）")
        } finally {
            runCatching { ocr.close() }
            runCatching { recognizer.release() }
        }
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
            fail("OpenCV 原生库加载失败")
            return false
        }

        val loaded = TemplateMatcher()
        loaded.load(this)
        matcher = loaded
        if (loaded.loadedCount == 0) {
            Log.w(TAG, "no templates found in assets/${TemplateMatcher.DEFAULT_ASSETS_DIR}")
            BotState.setStatus("未找到模板图，循环空跑中")
        }
        return true
    }

    // -------------------------------------------------------------- one-shot

    /**
     * 识别自检：抓一帧，逐个模板打分并写日志。
     *
     * 存在的意义是让识别层**可验证**：模板匹配会给出 0~1 的分数，
     * 分数本身就是证据 —— 不需要"看"截图也能确认模板、缩放、ROI 是否正确。
     * 纯只读，不点击任何东西。
     */
    private suspend fun runProbe() {
        if (!OpenCVLoader.initLocal()) {
            fail("OpenCV 原生库加载失败")
            return
        }
        if (!ShizukuBridge.hasPermission()) {
            fail("识别自检需要 Shizuku 权限（用来截图）")
            return
        }

        shizukuClient.bind()
        val service = shizukuClient.await()
        if (service == null) {
            fail("Shizuku UserService 未连接")
            return
        }

        val recognizer = Recognizer(this)
        try {
            RecognitionProbe(this, ShizukuScreenCapturer(shizukuClient), recognizer)
                .probeAndLog(ProbeTargets.ALL)
            BotState.setStatus("识别自检完成，见日志 RecognitionProbe")
        } finally {
            recognizer.release()
        }
    }

    /**
     * 虚拟屏探测 · 第一步：只建屏 + 拿首帧，**不搬 App、不注入**。
     *
     * 为什么不一口气做完：虚拟屏最讨厌的失败方式不是「建不出来」，而是
     * 「建出来了，但没有任何内容渲染进来」。这两种情况的排查方向完全相反，
     * 所以先用最小动作把两件事分开回答：
     *
     *   1. **displayId 拿到了吗** —— 拿到了说明权限与 flags 是对的（shell 身份 +
     *      PUBLIC + 不带 AUTO_MIRROR + 版本对应的 TRUSTED）。
     *   2. **3 秒内有帧吗** —— 有帧说明 Surface 通路真的打通了（GPU 出帧 → ImageReader）。
     *      注意：**空屏没有内容渲染时就是不出帧，这是预期行为，不是失败。**
     *
     * 拿到帧就存一张 PNG 到应用私有目录，让你能用眼睛直接看这块屏渲染了什么 ——
     * 比任何日志都直观。
     */
    private suspend fun runVirtualDisplayProbe() {
        if (!OpenCVLoader.initLocal()) {
            fail("OpenCV 原生库加载失败")
            return
        }

        // flags 按系统版本算。算错一位就会 SecurityException 或游戏挂不上窗口，
        // 所以这里把拆解后的名字也打进状态栏，真机排查时一眼能看到缺哪一位。
        val flags = VirtualDisplayFlags.forSdk(Build.VERSION.SDK_INT)
        BotState.setStatus("建虚拟屏 ${VD_WIDTH}x$VD_HEIGHT flags=${VirtualDisplayFlags.describe(flags)}")

        val surface = VirtualDisplaySurface(VD_WIDTH, VD_HEIGHT)
        var inProcessDisplay: Any? = null
        var shizukuService: IPvz2Service? = null

        try {
            // ① 先在**本进程**里建。
            //
            // 这么做的理由：createVirtualDisplay 会用「调用者包的 getPackageName()」做
            // `packageName must match the calling uid` 校验。本进程的包就是
            // com.pvz2.auto，天然匹配，所以这条路不依赖 Shizuku 的运行身份。
            // 而这块屏带了 PUBLIC 位，是 public 的，别的 uid（游戏）照样能挂窗口 ——
            // **建屏者是谁不影响谁能用这块屏**。
            val displayId = runCatching {
                val created = VirtualDisplayCreator.create(
                    callerContext = this,
                    name = VD_NAME,
                    width = VD_WIDTH,
                    height = VD_HEIGHT,
                    dpi = VD_DPI,
                    surface = surface.surface,
                    flags = flags
                )
                inProcessDisplay = created.virtualDisplay
                created.displayId
            }.onFailure {
                // 常见原因：隐藏 API 限制（App 进程里反射 @hide 方法可能被拦）。
                // 这不是终点，下面还有 shell 那条路。
                Log.w(TAG, "本进程建屏失败，改用 shell 身份再试", it)
            }.getOrDefault(-1)

            val actualDisplayId: Int
            if (displayId > 0) {
                Log.i(TAG, "本进程建屏成功 displayId=$displayId")
                actualDisplayId = displayId
            } else {
                // ② 退回 shell 身份（Shizuku UserService）。
                if (!ShizukuBridge.hasPermission()) {
                    fail("本进程建屏失败，且没有 Shizuku 权限可以退路")
                    return
                }
                shizukuClient.bind()
                val service = shizukuClient.await()
                if (service == null) {
                    fail("本进程建屏失败；Shizuku UserService 也未连接：" +
                        (shizukuClient.lastError ?: "超时"))
                    return
                }
                shizukuService = service
                val shellDisplayId = service.createVirtualDisplay(
                    VD_NAME, VD_WIDTH, VD_HEIGHT, VD_DPI, surface.surface, flags
                )
                if (shellDisplayId <= 0) {
                    fail(
                        "两条路都建不出虚拟屏（本进程失败，shell 返回 " +
                            "$shellDisplayId）。原因见日志 Pvz2UserService：若是 " +
                            "packageName must match the calling uid，说明 Shizuku 以 root " +
                            "身份运行，需改用 ADB/无线调试方式启动。"
                    )
                    return
                }
                Log.i(TAG, "shell 身份建屏成功 displayId=$shellDisplayId")
                actualDisplayId = shellDisplayId
            }

            BotState.setStatus("虚拟屏 displayId=$actualDisplayId，等首帧…")
            val frame = surface.awaitFrame(VD_FIRST_FRAME_TIMEOUT_MS)
            if (frame == null) {
                fail(
                    "虚拟屏已建（displayId=$actualDisplayId），但 ${VD_FIRST_FRAME_TIMEOUT_MS}ms 内无帧。" +
                        "空屏没有内容渲染时就是这样，属预期 —— 把游戏搬上来之后才会有帧"
                )
                return
            }

            try {
                val saved = savePng(frame, "vd_$actualDisplayId")
                BotState.setStatus(
                    "虚拟屏首帧 ${frame.width()}x${frame.height()}" +
                        (saved?.let { "，已存：$it" } ?: "，但保存失败")
                )
            } finally {
                frame.release()
            }
        } finally {
            // 无论成败都要收干净：虚拟屏不释放会一直占着一个 display id，
            // 反复探测几次之后系统就不肯再建了。
            runCatching { shizukuService?.releaseVirtualDisplay() }
            VirtualDisplayCreator.release(inProcessDisplay)
            surface.close()
        }
    }

    /** 存一张 PNG 到 `<外部私有目录>/shots/`。返回绝对路径，失败返回 null。 */
    private fun savePng(frame: org.opencv.core.Mat, namePrefix: String): String? {
        val baseDir = getExternalFilesDir(null) ?: return null
        val directory = File(baseDir, "shots").apply { mkdirs() }
        val file = File(directory, "${namePrefix}_${System.currentTimeMillis()}.png")

        val bitmap = Bitmap.createBitmap(frame.width(), frame.height(), Bitmap.Config.ARGB_8888)
        return try {
            Utils.matToBitmap(frame, bitmap)
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Log.i(TAG, "saved $file")
            file.absolutePath
        } catch (t: Throwable) {
            Log.w(TAG, "保存 PNG 失败", t)
            null
        } finally {
            bitmap.recycle()
        }
    }

    /** Saves a single frame so the user can crop templates out of it. */
    private suspend fun grabOneFrame() {
        if (!OpenCVLoader.initLocal()) {
            fail("OpenCV 原生库加载失败")
            return
        }
        if (!ShizukuBridge.hasPermission()) {
            fail("抓取截图需要 Shizuku 权限")
            return
        }

        shizukuClient.bind()
        val service = shizukuClient.await()
        if (service == null) {
            fail("Shizuku UserService 未连接")
            return
        }

        val frame = ShizukuScreenCapturer(shizukuClient).capture()
        if (frame == null) {
            fail("截图失败")
            return
        }

        try {
            val bitmap = Bitmap.createBitmap(frame.width(), frame.height(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(frame, bitmap)

            val baseDir = getExternalFilesDir(null)
            if (baseDir == null) {
                bitmap.recycle()
                fail("没有可用的外部存储目录")
                return
            }
            val directory = File(baseDir, "shots").apply { mkdirs() }
            val file = File(directory, "shot_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()

            Log.i(TAG, "saved $file")
            BotState.setStatus("已保存：${file.absolutePath}")
        } finally {
            frame.release()
        }
    }

    // -------------------------------------------------------------- plumbing

    private fun fail(message: String) {
        Log.w(TAG, "cannot start: $message")
        BotState.setStatus(message)
        BotState.setRunning(false)
    }

    /**
     * 只传一种前台服务类型：`specialUse`。
     *
     * 降级路线（MediaProjection）移除之后就不再有第二种类型，也就不需要按后端分支。
     * Android 14 起必须显式声明类型，且要与 manifest 里的 `foregroundServiceType` 一致。
     * `ServiceCompat.startForeground` 是官方推荐的写法。
     */
    private fun startForegroundCompat() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
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

        private const val ACTION_START_SHIZUKU = "com.pvz2.auto.START_SHIZUKU"
        private const val ACTION_GRAB_FRAME = "com.pvz2.auto.GRAB_FRAME"
        private const val ACTION_PROBE = "com.pvz2.auto.PROBE"
        private const val ACTION_VERIFY_DRY = "com.pvz2.auto.VERIFY_DRY"
        private const val ACTION_VERIFY_RUN = "com.pvz2.auto.VERIFY_RUN"
        private const val ACTION_VD_PROBE = "com.pvz2.auto.VD_PROBE"

        /** 持有式虚拟屏：start 之后屏一直在，直到 stop。 */
        private const val ACTION_VD_START = "com.pvz2.auto.VD_START"
        private const val ACTION_VD_STOP = "com.pvz2.auto.VD_STOP"
        private const val EXTRA_TARGET_PACKAGE = "target_package"

        // 这里**不再**有默认包名。
        //
        // 曾经硬编码 com.popcap.pvz2cthdop（OPPO 渠道），结果用户装的是别的渠道时
        // 根本启动不了游戏。现在包名一律由界面（渠道选择）传进来。

        /** 虚拟屏参数：720p @ 240dpi，与 MaaFwApp 的默认档一致。 */
        private const val VD_NAME = "pvz2-virtual"
        private const val VD_WIDTH = 1280
        private const val VD_HEIGHT = 720
        private const val VD_DPI = 240

        /** 等首帧的上限。空屏本来就不出帧，所以这个超时不是"失败判定"。 */
        private const val VD_FIRST_FRAME_TIMEOUT_MS = 3_000L


        private fun intent(context: Context, action: String) =
            Intent(context, AutomationService::class.java).setAction(action)

        fun startShizuku(context: Context) {
            ContextCompat.startForegroundService(context, intent(context, ACTION_START_SHIZUKU))
        }

        fun grabFrame(context: Context) {
            context.startService(intent(context, ACTION_GRAB_FRAME))
        }

        /**
         * 识别自检：抓一帧，把每个模板的匹配分数打进日志。
         * 开发期用来验证「模板 + 缩放 + ROI + 匹配算法」这条链路，不做任何点击。
         *
         * 用 startService 而不是 startForegroundService：这是一次性的只读操作，
         * 不需要常驻前台服务。用后者会在 app 处于后台（游戏在前台）时抛
         * ForegroundServiceStartNotAllowedException。
         */
        fun probe(context: Context) {
            context.startService(intent(context, ACTION_PROBE))
        }

        /**
         * 虚拟屏探测：建 720p 虚拟屏、等首帧、存一张 PNG。
         *
         * 用 `startForegroundService` 而不是 `startService`：这条路径要绑 UserService
         * 并持有 ImageReader，属于"要活一会儿"的操作；而且 App 在后台时
         * （游戏在前台）用 startService 会直接抛 ForegroundServiceStartNotAllowedException。
         */
        fun vdProbe(context: Context) {
            ContextCompat.startForegroundService(context, intent(context, ACTION_VD_PROBE))
        }

        /**
         * 在虚拟屏上启动游戏（持有式：屏一直在，直到 [vdStop]）。
         *
         * 用 `startForegroundService`：屏必须挂在前台服务的生命周期上，
         * 否则服务被系统回收时屏就没了。而且界面在后台调用时也只有这条路径合法。
         */
        fun vdStart(context: Context, targetPackage: String) {
            val i = intent(context, ACTION_VD_START)
            i.putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
            ContextCompat.startForegroundService(context, i)
        }

        /** 停止虚拟屏：释放 display、游戏回到主屏。 */
        fun vdStop(context: Context) {
            ContextCompat.startForegroundService(context, intent(context, ACTION_VD_STOP))
        }

        /**
         * 方案 C 干跑：跑识别链但**不点击**，把「本该点哪里」打进日志。
         * 第一次在真机验证 pipeline 时用这个。
         *
         * 走 startForegroundService：这条路径要绑定 UserService 并持续截图，
         * 是长时间操作，不是一次性只读操作，必须有前台服务保活。
         */
        fun verifyDryRun(context: Context) {
            ContextCompat.startForegroundService(context, intent(context, ACTION_VERIFY_DRY))
        }

        /** 方案 C 实跑：识别链会**真的点击**。确认干跑日志无误后再用。 */
        fun verifyRun(context: Context) {
            ContextCompat.startForegroundService(context, intent(context, ACTION_VERIFY_RUN))
        }

        /** `stopService` is always permitted, unlike starting a service from the background. */
        /**
         * 停止整个服务。
         *
         * 注意：`stopService` 不会走我们的 ACTION_VD_STOP，所以这里必须先
         * 通过一次 ACTION_VD_STOP 把 `userStopped` 标记置上，否则 onDestroy 会
         * 认为"是系统回收"，把屏留在系统里变成幽灵屏。
         */
        fun stop(context: Context) {
            ContextCompat.startForegroundService(context, intent(context, ACTION_VD_STOP))
        }
    }
}