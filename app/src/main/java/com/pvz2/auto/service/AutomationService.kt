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
import com.pvz2.auto.zjs.ZExecutor
import com.pvz2.auto.zjs.ZPosResolver
import com.pvz2.auto.zjs.ZRealMatcher
import com.pvz2.auto.zjs.ZScriptIO
import com.pvz2.auto.zjs.ZTouchInjectorAdapter
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
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

    /** 正在跑的自动精灵执行器。停止时用它打断 sleep。 */
    private var zjsExecutor: com.pvz2.auto.zjs.ZExecutor? = null

    /** 注入器就绪/销毁时同步到静态引用，供预览手势使用。 */
    private fun publishInjector(v: TouchInjector?) {
        injector = v
        sharedInjector = v
    }
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
                    scope.launch {
                        // ★ 先把注入器建起来。
                        //
                        //   预览上的触摸（点击/拖动）走 beginManualGesture，
                        //   它读 sharedInjector；而注入器只在 ensureInjector()
                        //   里创建。加了手势之后触摸不再经过 ACTION_MANUAL_TAP，
                        //   于是没人调 ensureInjector —— sharedInjector 一直是
                        //   null，按下永远返回 null，表现成"点也没反应、拖也没反应"。
                        //
                        //   在这里建：建屏和建注入器本来就该一起完成，
                        //   用户看见画面时，注入器必然已经就绪。
                        runCatching { ensureInjector() }
                            .onFailure { Log.e(TAG, "建屏后创建注入器失败", it) }
                        vdSession.start(pkg)
                    }
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


            // ---- 自动精灵格式
            ACTION_ZJS_RUN -> {
                Log.i(TAG, "[zjs] 收到运行请求")
                startForegroundCompat()
                val path = intent.getStringExtra(EXTRA_ZJS_PATH)
                Log.i(TAG, "[zjs] path=$path")
                if (path.isNullOrBlank()) {
                    ScriptRunState.finish("没有指定脚本文件")
                } else {
                    // ★ 先让旧执行器停手，再 cancelAndJoin。
                    //
                    //   等待循环用的是 Thread.sleep + stopped 标志，**不响应协程取消**。
                    //   只 cancel 不 stop 的话：job 立刻被标记为已取消、
                    //   join 马上返回，但旧循环还在后台轮询 ——
                    //   两个任务同时读帧、同时注入，界面上却只有一个"运行中"。
                    //   真机上就是这么出现「一个在等过渡、一个在等神器补给」的。
                    zjsExecutor?.stop()
                    zjsExecutor = null
                    val previous = scriptJob
                    scriptJob = scope.launch {
                        previous?.cancelAndJoin()
                        runZjs(path)
                    }
                }
            }

            ACTION_ZJS_STOP -> {
                startForegroundCompat()
                zjsExecutor?.stop()
                zjsExecutor = null
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
        publishInjector(null)
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
        // ★ 早退分支也必须同步 sharedInjector。
        //
        //   原来写 `injector?.let { return it }` —— 注入器已存在时直接返回，
        //   **不同步静态引用**。而预览手势读的是 sharedInjector，于是
        //   "注入器明明建好了、手势却永远说没就绪"，而且这条路径上
        //   一行日志都不打，看着像 ensureInjector 根本没被调用。
        injector?.let {
            sharedInjector = it
            return it
        }

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
        // 同步给预览手势用的静态引用。漏了这一步，预览里按下不会有任何反应。
        sharedInjector = created
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
    /**
     * 跑一个自动精灵脚本。
     *
     * ## 为什么在 Dispatchers.IO 之外还要自己起线程
     *
     * [ZExecutor.run] 是**阻塞**的（里面有 Thread.sleep 等待、同步的 OpenCV 匹配）。
     * 放在协程里跑，中途要停只能靠 cancel —— 但 sleep 期间 cancel 不生效，
     * 用户点了「停止」要等当前那条的等待跑完。所以执行器自己维护
     * `stopped` 标志，停止时置位，等待里检测到就退。
     */
    private suspend fun runZjs(path: String) {
        // ★ 这条链路的每一步都留日志。
        //
        //   之前这里静默失败过：用户点了运行、保存和校验都成功、然后什么都没发生，
        //   日志里也查不到原因 —— 这是最难排查的一类失败。宁可多几行日志。
        Log.i(TAG, "[zjs] runZjs 开始 path=$path")

        val loaded = withContext(Dispatchers.IO) { ZScriptIO.load(path) }
        if (loaded.error != null) {
            Log.e(TAG, "[zjs] 读脚本失败：${loaded.error}")
            ScriptRunState.finish("读不出脚本：${loaded.error}")
            return
        }
        val script = loaded.script!!
        Log.i(TAG, "[zjs] 读到 ${script.actions.size} 个动作")
        if (script.actions.isEmpty()) {
            ScriptRunState.finish("脚本里没有动作")
            return
        }

        /*
         * 虚拟屏没跑就别开始。
         *
         * 曾经不检查：脚本照常"跑完"，但每一下手势都被注入层拒绝
         * （日志里只有 W 级的「手势未开始：虚拟屏 displayId=-1」），
         * 界面上什么都不变 —— 用户看到的就是「点运行没反应」。
         *
         * 静默空转比报错难查得多，所以这里直接拒绝并说清原因。
         */
        val pre = VirtualDisplaySession.sharedState.value
        if (!pre.running || pre.displayId <= 0) {
            Log.e(
                TAG,
                "[zjs] 拒绝运行：虚拟屏没在跑 displayId=${pre.displayId} running=${pre.running}"
            )
            ScriptRunState.finish("虚拟屏没在运行：先回第 1 页点「在虚拟屏启动游戏」")
            return
        }

        val touch = ensureInjector()
        if (touch == null) {
            Log.e(TAG, "[zjs] 注入器不可用")
            ScriptRunState.finish("注入器没准备好（Shizuku 未就绪？）")
            return
        }

        // 屏幕参数从当前虚拟屏状态取 —— 用的是实际帧尺寸，
        // 不是硬编码的 1280×720，换分辨率不用改代码。
        val st = VirtualDisplaySession.sharedState.value
        val w = st.frameWidth.takeIf { it > 0 } ?: 1280
        val h = st.frameHeight.takeIf { it > 0 } ?: 720
        val screen = ZPosResolver.Screen(w, h, resources.displayMetrics.density)
        Log.i(TAG, "[zjs] 屏幕 ${w}x$h displayId=${st.displayId} running=${st.running}")

        val adapter = ZTouchInjectorAdapter(touch)
        // 按键和文字走 Shizuku 侧的另一条通道
        adapter.delegateKey = { code: Int, hold: Int ->
            runCatching {
                val svc = shizukuClient.peek() ?: error("Shizuku 服务不在")
                if (hold > 120) svc.exec("input keyevent --longpress $code")
                else svc.exec("input keyevent $code")
            }
        }
        adapter.delegateText = { text: String ->
            runCatching {
                val svc = shizukuClient.peek() ?: error("Shizuku 服务不在")
                // 单引号包裹并转义内部单引号，否则空格会被当成参数分隔
                svc.exec("input text '${text.replace("'", "'\\''")}'")
            }
        }

        val matcher = ZRealMatcher(
            TemplateMatcher().also { it.load(this) },
            frame = { VirtualDisplaySession.snapshotForMatch() },
            // Context 给 OCR 读 assets/ocr 模型用
            context = this
        )
        matcher.cacheDirProvider = { java.io.File(cacheDir, "zjs_templates").absolutePath }

        val executor = ZExecutor(
            inject = adapter,
            screen = screen,
            matcher = matcher,
            log = { com.pvz2.auto.core.ZjsLog.add(it) }
        )
        zjsExecutor = executor
        // ★ scriptName 必须是**脚本文件名**，不能是字面量 "zjs"。
        //
        //   第 2 页判断"这张卡是不是正在跑的那个"就是
        //   `runState.scriptName == item.name`。写死成 "zjs" 的话永远对不上，
        //   于是每张卡都显示「运行」—— 脚本在跑，但用户找不到「停止」。
        ScriptRunState.begin(
            java.io.File(path).nameWithoutExtension,
            "${script.actions.size} 个动作"
        )

        val result = withContext(Dispatchers.IO) {
            runCatching { executor.run(script) }
        }

        zjsExecutor = null
        result.onSuccess { r ->
            /*
             * ★ 失败必须以「失败：」开头。
             *
             *   原来不管成功还是失败都写「完成：执行 N 条；原因」——
             *   脚本在第 7 条被终止时，界面上读起来像跑完了，
             *   用户根本不知道任务已经失败（实测反馈：任务失败没有提示）。
             */
            val msg = if (r.error != null) {
                "失败：${r.error}"
            } else buildString {
                append("完成：执行 ${r.executed} 条")
                if (r.skipped.isNotEmpty()) append("，跳过 ${r.skipped.size} 条")
            }
            // 跳过明细写日志 —— 界面上放不下几十条，但排查时必须有。
            r.skipped.take(20).forEach {
                Log.w(TAG, "[zjs] 跳过第 ${it.index + 1} 个（${it.label}）：${it.reason}")
            }
            ScriptRunState.finish(msg)
        }.onFailure { t ->
            Log.e(TAG, "[zjs] 执行异常", t)
            ScriptRunState.finish("执行异常：${t.message}")
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
    private const val ACTION_ZJS_RUN = "com.pvz2.auto.ZJS_RUN"
    private const val ACTION_ZJS_STOP = "com.pvz2.auto.ZJS_STOP"
    private const val EXTRA_ZJS_PATH = "zjs_path"
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
         * 运行一个**自动精灵格式**的脚本。
         *
         * ## 为什么和 [scriptRun] 分开
         *
         * 那条路径跑的是 MaaFramework 格式（一个节点一个文件、靠 next 连边）。
         * 自动精灵是一串线性动作，执行模型完全不同，混在一起两边都难懂。
         *
         * @param path   `.zjs` 文件路径
         */
        fun zjsRun(context: Context, path: String) {
            val i = intent(context, ACTION_ZJS_RUN)
            i.putExtra(EXTRA_ZJS_PATH, path)
            ContextCompat.startForegroundService(context, i)
        }

        /** 停止自动精灵脚本。 */
        fun zjsStop(context: Context) {
            ContextCompat.startForegroundService(context, intent(context, ACTION_ZJS_STOP))
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

        /**
         * 开始一次跟随手指的手势。
         *
         * ## 为什么不走 Intent
         *
         * 每次 MOVE 都发一个 Intent 会走一遍 startService、序列化、进程调度 ——
         * 手指移动一秒几十次，这条路上每一跳都是延迟。而且事件顺序无法保证，
         * 可能出现 MOVE 比 DOWN 先到，游戏收到的是无头事件。
         *
         * 预览和注入器在同一个进程（都在 App 里），所以直接拿实例调用即可。
         *
         * @return null 表示注入器还没就绪（虚拟屏没启动）
         */
        fun beginManualGesture(
            context: Context,
            x: Int,
            y: Int
        ): com.pvz2.auto.core.StreamingGesture? {
            val injector = sharedInjector
            if (injector == null) {
                // 注入器在建屏成功后由会话创建（见 ACTION_VD_START）。
                // 走到这里说明还没建好 —— 报出来，别静默失败。
                Log.w(TAG, "手势未开始：注入器还没就绪")
                return null
            }
            val displayId = VirtualDisplaySession.sharedState.value.displayId
            if (displayId <= 0) {
                Log.w(TAG, "手势未开始：虚拟屏 displayId=$displayId")
                return null
            }
            // 坐标已经是虚拟屏坐标系（PreviewTouchMapper 换算过），直接转 Float。
            return runCatching {
                injector.beginGesture(x.toFloat(), y.toFloat())
            }.onFailure { Log.e(TAG, "开始手势失败", it) }.getOrNull()
        }
        /**
         * 当前注入器的静态引用。
         *
         * companion object 拿不到服务实例的字段，而预览的手势要从 Activity
         * 调到同一个注入器 —— 用一个 @Volatile 静态引用桥接。
         * 服务销毁时清空（见 publishInjector(null)），避免握着已释放的后端。
         */
        @Volatile
        var sharedInjector: TouchInjector? = null

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
