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
import com.pvz2.auto.core.ScriptRunState
import com.pvz2.auto.core.BotState
import com.pvz2.auto.core.ScreenCapturer
import com.pvz2.auto.core.TemplateMatcher
import com.pvz2.auto.core.TouchInjector
import com.pvz2.auto.maa.OcrEngine
import com.pvz2.auto.maa.PipelineRunner
import com.pvz2.auto.maa.ProbeTargets
import com.pvz2.auto.maa.RecognitionProbe
import com.pvz2.auto.maa.Recognizer
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
import kotlinx.coroutines.cancelAndJoin
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

    /** 当前脚本任务。停止时 cancel 它。 */
    private var scriptJob: Job? = null

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

            // ★ 虚拟屏：**持有**一块屏直到用户停止。这是产品形态的入口。
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

            // ★ 脚本运行。这是整条执行链第一次有真实入口 ——
            //   在此之前 PipelineRunner 只在自身声明处出现过一次，
            //   节点解析和图执行都写好了，但没有任何地方会启动它。
            ACTION_SCRIPT_RUN -> {
                startForegroundCompat()
                val dir = intent.getStringExtra(EXTRA_SCRIPT_DIR)
                val entry = intent.getStringExtra(EXTRA_SCRIPT_ENTRY)
                val dry = intent.getBooleanExtra(EXTRA_SCRIPT_DRY_RUN, true)
                if (dir.isNullOrBlank()) {
                    ScriptRunState.finish("没有指定脚本")
                } else {
                    // ★ 必须等旧任务**真正结束**再起新的。
                    //
                    //   只 cancel() 不够：cancel 只是发出信号，旧协程可能还在
                    //   runScript 的 bind()+await()（最长 5 秒）或 loadNodes 里。
                    //   那段时间 ScriptRunState.running 仍是 false，界面上的
                    //   「运行」按钮还能点 —— 用户连点两下就有两个 job 共用同一个
                    //   采集器和注入器：并发抓帧，非干跑时两边同时在点游戏。
                    //
                    //   cancelAndJoin 保证串行。previous 在挂起前就捕获了，
                    //   不受下面重新赋值的影响。
                    val previous = scriptJob
                    scriptJob = scope.launch {
                        previous?.cancelAndJoin()
                        runScript(dir, entry, dry)
                    }
                }
            }

            ACTION_SCRIPT_STOP -> {
                startForegroundCompat()
                scriptJob?.cancel()
                scriptJob = null
                ScriptRunState.finish("已停止")
            }

            // ★ 手动点击：用户在预览画面上点一下就注入到虚拟屏。
            //
            //   虚拟屏没有物理触摸面板，用户没法用手指点它。没有这条路径，
            //   用户连"把游戏点到能验证的界面"都做不到 —— 脚本永远跑不到该跑的地方。
            //
            //   用 startService 而不是 startForegroundService：这是一次性动作，
            //   而且要求尽可能快（用户期望点下去就有反应）。
            ACTION_MANUAL_TAP -> {
                val x = intent.getIntExtra(EXTRA_TAP_X, -1)
                val y = intent.getIntExtra(EXTRA_TAP_Y, -1)
                if (x >= 0 && y >= 0) {
                    scope.launch {
                        // ★ 这里**不能**写成 `injector ?: return@launch`。
                        //
                        //   注入器原本只在两条路径里创建：runShizukuLoop()（启动
                        //   自动化）和 withBootstrap()。而"只建虚拟屏"走的是
                        //   ACTION_VD_START，它不经过那两条 —— 于是 injector 一直是
                        //   null，每次手动点击都在这里静默返回。
                        //
                        //   现象就是"点预览完全没反应"，而且没有任何日志能看出原因。
                        //   现在按需创建，并把结果报到界面上。
                        val target = ensureInjector()
                        if (target == null) {
                            BotState.setStatus("点击失败：Shizuku 未就绪")
                            Log.w(TAG, "手动点击跳过：注入器不可用")
                            return@launch
                        }
                        val displayId = vdSession.state.value.displayId
                        target.tap(x.toFloat(), y.toFloat())
                        BotState.setStatus("已点击 ($x,$y) → display=$displayId")
                    }
                }
            }

            // 手动滑动：用户在预览画面上拖动，换算后注入到虚拟屏。
            // 游戏里翻列表、拖植物都靠它 —— 只有点击的话，用户在预览上怎么拖都没用。
            ACTION_MANUAL_SWIPE -> {
                val x1 = intent.getIntExtra(EXTRA_SWIPE_X1, -1)
                val y1 = intent.getIntExtra(EXTRA_SWIPE_Y1, -1)
                val x2 = intent.getIntExtra(EXTRA_SWIPE_X2, -1)
                val y2 = intent.getIntExtra(EXTRA_SWIPE_Y2, -1)
                val ms = intent.getIntExtra(EXTRA_SWIPE_MS, 300)
                if (x1 >= 0 && y1 >= 0 && x2 >= 0 && y2 >= 0) {
                    scope.launch {
                        val target = ensureInjector()
                        if (target == null) {
                            BotState.setStatus("滑动失败：Shizuku 未就绪")
                            return@launch
                        }
                        target.swipe(
                            x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(),
                            ms.toLong()
                        )
                        BotState.setStatus("已滑动 ($x1,$y1)→($x2,$y2)")
                    }
                }
            }

            // 抓帧 / 探针 / 建屏探针 / 验证链（干跑、实跑）五个诊断入口已删除。
            // 它们都是排查早期问题的临时工具，问题解决后就没有用途了：
            // 抓帧和建屏探针的功能被 ACTION_VD_START 覆盖，
            // 验证链依赖的模板集早已不存在。留着只是死代码。
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
        // ★ 脚本任务必须在**释放注入器之前**取消。
        //
        //   顺序反了的话：injector?.release() 已经执行，而 scriptJob 还在
        //   IO 线程上跑 NodeGraph，下一轮照样会调 injector.tap() ——
        //   对着已经释放的对象调用，典型的 use-after-release。
        scriptJob?.cancel()
        scriptJob = null
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
        // ★ 注入必须打到**虚拟屏**上，不能打主屏。
        //
        //   虚拟屏是另一块屏，注入到 display 0 的事件它收不到 ——
        //   表现就是"点了完全没反应"。之前这里写死了 0，整条
        //   识别→点击链路一直在点主屏。
        //
        //   displayId 从会话状态里实时读：建屏前它是 -1，
        //   建好后变成真实 id。用 lambda 而不是传值，这样
        //   建屏/重建屏之后不用重新构造 injector。
        //
        //   -1 时回退到 0（主屏）—— 那是"还没建虚拟屏、直接在
        //   主屏上跑"的合法场景。
        injector = ShizukuTouchInjector(shizukuClient) {
            vdSession.state.value.displayId.takeIf { it > 0 } ?: 0
        }
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
     * 确保注入器可用，没有就现造一个。
     *
     * ## 为什么需要"按需创建"
     *
     * 注入器原本只在两条路径里建：`runShizukuLoop()`（启动自动化）和
     * `withBootstrap()`。而**只建虚拟屏**走的是 `ACTION_VD_START`，
     * 不经过那两条 —— 于是 injector 永远是 null。
     *
     * 用户手动点预览时注入器是空的，`injector ?: return` 静默返回，
     * 表现就是"点了完全没反应"，日志里也看不出原因。
     *
     * 现在第一次需要时把它建出来，之后复用。
     */
    private suspend fun ensureInjector(): TouchInjector? {
        injector?.let { return it }

        if (!ShizukuBridge.hasPermission()) {
            Log.w(TAG, "ensureInjector: 没有 Shizuku 权限")
            return null
        }
        // 已经连上就别再 bind —— 重复 bind 会重建连接，正在跑的采集会断。
        if (shizukuClient.peek() == null) {
            shizukuClient.bind()
            val svc = shizukuClient.await()
            if (svc == null) {
                Log.w(TAG, "ensureInjector: UserService 连接超时")
                return null
            }
        }

        val created = ShizukuTouchInjector(shizukuClient) {
            vdSession.state.value.displayId.takeIf { it > 0 } ?: 0
        }
        injector = created
        Log.i(TAG, "ensureInjector: 注入器已就绪")
        return created
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
        // ★ 注入必须打到**虚拟屏**上，不能打主屏。
        //
        //   虚拟屏是另一块屏，注入到 display 0 的事件它收不到 ——
        //   表现就是"点了完全没反应"。之前这里写死了 0，整条
        //   识别→点击链路一直在点主屏。
        //
        //   displayId 从会话状态里实时读：建屏前它是 -1，
        //   建好后变成真实 id。用 lambda 而不是传值，这样
        //   建屏/重建屏之后不用重新构造 injector。
        //
        //   -1 时回退到 0（主屏）—— 那是"还没建虚拟屏、直接在
        //   主屏上跑"的合法场景。
        injector = ShizukuTouchInjector(shizukuClient) {
            vdSession.state.value.displayId.takeIf { it > 0 } ?: 0
        }
        block()
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
    /**
     * 跑一个脚本。
     *
     * 与旧的 `runLoop()`（识别到就点）并存：那条路是唯一在真机跑通过的退路，
     * 等 pipeline 稳定后再删。
     *
     * 流程：绑定 Shizuku → 准备采集器/注入器 → 建 Recognizer → 交给 PipelineRunner。
     */
    private suspend fun runScript(scriptDir: String, entry: String?, dryRun: Boolean) {
        if (!OpenCVLoader.initLocal()) {
            ScriptRunState.finish("OpenCV 原生库加载失败")
            return
        }
        if (!ShizukuBridge.hasPermission()) {
            ScriptRunState.finish("需要 Shizuku 权限")
            return
        }

        shizukuClient.bind()
        val size = runCatching { shizukuClient.await()?.getDisplaySize() }.getOrNull()
        if (size.isNullOrBlank()) {
            ScriptRunState.finish("特权 shell 无响应")
            return
        }

        val activeCapturer = capturer ?: VirtualDisplayCapturer(
            vdSession,
            ShizukuScreenCapturer(shizukuClient)
        ).also { capturer = it }
        val activeInjector = injector ?: ShizukuTouchInjector(shizukuClient) {
            vdSession.state.value.displayId.takeIf { it > 0 } ?: 0
        }.also { injector = it }

        val recognizer = Recognizer(this)
        try {
            // 用虚拟屏的实际尺寸定识别基准，而不是写死 720p ——
            // 换分辨率时 ScreenAdapter 才是准的。
            val w = vdSession.state.value.frameWidth.takeIf { it > 0 } ?: VD_WIDTH
            val h = vdSession.state.value.frameHeight.takeIf { it > 0 } ?: VD_HEIGHT
            recognizer.setScreen(w, h)

            PipelineRunner(
                context = this,
                capturer = activeCapturer,
                injector = activeInjector,
                recognizer = recognizer,
                dryRun = dryRun
            ).run(scriptDir, entry)
        } finally {
            recognizer.release()
        }
    }

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

        /** 持有式虚拟屏：start 之后屏一直在，直到 stop。 */
        private const val ACTION_VD_START = "com.pvz2.auto.VD_START"
        private const val ACTION_VD_STOP = "com.pvz2.auto.VD_STOP"
    private const val ACTION_SCRIPT_RUN = "com.pvz2.auto.SCRIPT_RUN"
    private const val ACTION_SCRIPT_STOP = "com.pvz2.auto.SCRIPT_STOP"
    private const val EXTRA_SCRIPT_DIR = "script_dir"
    private const val EXTRA_SCRIPT_ENTRY = "script_entry"
    private const val EXTRA_SCRIPT_DRY_RUN = "script_dry_run"
    private const val ACTION_MANUAL_TAP = "com.pvz2.auto.MANUAL_TAP"
    private const val EXTRA_TAP_X = "tap_x"
    private const val EXTRA_TAP_Y = "tap_y"
    private const val ACTION_MANUAL_SWIPE = "com.pvz2.auto.MANUAL_SWIPE"
    private const val EXTRA_SWIPE_X1 = "swipe_x1"
    private const val EXTRA_SWIPE_Y1 = "swipe_y1"
    private const val EXTRA_SWIPE_X2 = "swipe_x2"
    private const val EXTRA_SWIPE_Y2 = "swipe_y2"
    private const val EXTRA_SWIPE_MS = "swipe_ms"
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

        // grabFrame / probe / vdProbe / verifyDryRun / verifyRun 已删除。
        // 它们对应的 action 分支和实现方法也一并删了 —— 都是排查早期问题的
        // 临时工具，问题解决后没有调用方，留着只是死代码。

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
         * 运行脚本。
         *
         * @param dir `assets/pipeline/` 下的脚本目录名
         * @param entry 入口节点；null = 自动推断
         * @param dryRun true = 只识别不点击。首次跑新脚本保持 true
         */
        fun scriptRun(context: Context, dir: String, entry: String?, dryRun: Boolean) {
            val i = intent(context, ACTION_SCRIPT_RUN)
            i.putExtra(EXTRA_SCRIPT_DIR, dir)
            i.putExtra(EXTRA_SCRIPT_ENTRY, entry)
            i.putExtra(EXTRA_SCRIPT_DRY_RUN, dryRun)
            ContextCompat.startForegroundService(context, i)
        }

        /** 停止脚本。 */
        fun scriptStop(context: Context) {
            ContextCompat.startForegroundService(context, intent(context, ACTION_SCRIPT_STOP))
        }

        /**
         * 手动点击虚拟屏。用户在预览画面上点哪，就往哪注入。
         *
         * @param x 虚拟屏坐标（不是物理屏坐标），由 `PreviewTouchMapper` 换算好
         */
        fun manualTap(context: Context, x: Int, y: Int) {
            val i = intent(context, ACTION_MANUAL_TAP)
            i.putExtra(EXTRA_TAP_X, x)
            i.putExtra(EXTRA_TAP_Y, y)
            context.startService(i)
        }

        /**
         * 手动滑动虚拟屏。坐标同样是**虚拟屏坐标**，由 `PreviewTouchMapper` 换算好。
         *
         * 游戏里翻世界列表、拖动植物都靠它 —— 只有点击的话，
         * 用户在预览上怎么拖都没用。
         */
        fun manualSwipe(
            context: Context,
            x1: Int, y1: Int, x2: Int, y2: Int,
            durationMs: Int
        ) {
            val i = intent(context, ACTION_MANUAL_SWIPE)
            i.putExtra(EXTRA_SWIPE_X1, x1)
            i.putExtra(EXTRA_SWIPE_Y1, y1)
            i.putExtra(EXTRA_SWIPE_X2, x2)
            i.putExtra(EXTRA_SWIPE_Y2, y2)
            i.putExtra(EXTRA_SWIPE_MS, durationMs)
            context.startService(i)
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
