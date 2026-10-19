package com.pvz2.auto.service

import android.os.Build
import android.util.Log
import com.pvz2.auto.IPvz2Service
import com.pvz2.auto.shizuku.VirtualDisplayCreator
import com.pvz2.auto.shizuku.VirtualDisplayFlags
import com.pvz2.auto.shizuku.VirtualDisplaySurface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.opencv.core.Mat

/**
 * 虚拟屏会话：**持有**一块虚拟屏，直到被显式 [stop]。
 *
 * ## 这个类存在的理由（也是之前踩的最大的坑）
 *
 * 之前的做法是"命令行探针里建屏 → 跑实验 → 进程退出"。探针一退，屏就没了，
 * 系统还会把游戏任务挪回主屏 —— 于是每次实验都从零开始，
 * 永远观察不到"屏活着的时候游戏在稳定运行"这个状态。
 *
 * 现在屏由前台服务持有：[start] 之后它一直在，直到用户点停止。
 * 这才是产品该有的形态，也是唯一能真正验证注入/脚本的环境。
 *
 * ## 生命周期
 *
 * ```
 * start()  -> 建屏(shell 身份) -> 启动(或搬)应用 -> 开始出帧
 * stop()   -> 停轮询 -> 释放屏 -> 应用回主屏
 * ```
 *
 * 状态通过 [state] 暴露给界面 —— 界面只读它，不直接碰任何反射/Binder 细节。
 */
class VirtualDisplaySession(
    /** 建屏要用本进程的 Context —— 由 service 注入，别在类里自己找。 */
    private val callerContext: android.content.Context,
    private val scope: CoroutineScope,
    private val shizukuServiceProvider: suspend () -> IPvz2Service?,
) {

    /** 界面要显示的一切状态都在这一个对象里。 */
    data class State(
        val running: Boolean = false,
        /** 一句话描述当前进展，直接显示在界面上。 */
        val message: String = "未启动",
        /** 虚拟屏 id；-1 表示没有。 */
        val displayId: Int = -1,
        /** 应用是否已经被搬到这块屏上（按 dumpsys 的客观判定）。 */
        val appOnDisplay: Boolean = false,
        /** 最近一帧的尺寸，界面用来判断"有没有画面"。 */
        val frameWidth: Int = 0,
        val frameHeight: Int = 0,
        /** 累计收到的帧数。**只在内容变化时 +1**，避免把残影当活帧。 */
        val liveFrameCount: Int = 0,
        /** 判定依据的原文，出问题时直接看这一行。 */
        val diagnostics: String = "",

        /**
         * 建屏那一刻的关键结论，**一直保留到下次建屏**。
         *
         * 为什么要单独存：导出日志时屏往往已经被释放了（服务被回收），
         * 那时候再去 dumpsys 什么也查不到。所以建屏当下就要把结论记下来。
         */
        val buildReport: String = "",
        /** 建屏用的 flags 是哪个候选成功的（1-4），0 表示还没建过。 */
        val flagsCandidate: Int = 0,
        /** 屏是不是 TRUSTED —— 窗口能不能挂上去的唯一前提。 */
        val displayTrusted: Boolean = false,
        /** 内部用：上一条已记入流水的 message，避免重复记录。 */
        val lastLoggedMessage: String = "",

        /** 事件流水（最近 MAX_HISTORY 条）。导出日志时原样带出。 */
        val history: List<String> = emptyList(),
    )

    private val _state = MutableStateFlow(State())

    /** 实例内可读的状态（服务自己用）。 */
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * 采集端。**必须是 NDK 版**（[NativeCaptureSource]）。
     *
     * Java 的 ImageReader / SurfaceTexture 给的 Surface 不满足 WMS 对可信显示端
     * 的要求，用它们建出来的屏会被标 untrusted：窗口留在主屏、IME 策略设不上、
     * 注入全被丢。这块 Surface 同时充当显示端与采集端。
     */
    private var captureSource: NativeCaptureSource? = null

    /** 本进程建屏的句柄（@hide 类型，只能按 Any 持有）。 */
    private var localDisplay: Any? = null

    /** shell 侧建屏时用的 service，释放时要还给它。 */
    private var shellService: IPvz2Service? = null

    private var frameJob: Job? = null

    /** 上一帧的采样指纹，用来区分"真活帧"和"残影"。 */
    private var lastFingerprint: Long = 0

    /**
     * 建屏并把 [targetPackage] 搬上去。
     *
     * @param targetPackage 要搬到副屏的应用；null 表示只建屏不放应用
     * @return true 表示屏建出来了（不代表应用已经在上面渲染）
     */
    suspend fun start(targetPackage: String?): Boolean {
        if (_state.value.running) {
            Log.i(TAG, "已经在一块虚拟屏上了，先停再启")
            stop()
        }
        update { it.copy(running = true, message = "正在建虚拟屏…") }

        val source = NativeCaptureSource(VD_WIDTH, VD_HEIGHT)
        captureSource = source
        if (source.surface == null) {
            update {
                it.copy(
                    running = false,
                    message = "native 采集端创建失败（见 logcat 标签 Pvz2Capture）",
                )
            }
            cleanup()
            return false
        }

        val displayId = createDisplay(source)
        if (displayId <= 0) {
            update {
                it.copy(
                    running = false,
                    message = "建虚拟屏失败（displayId=$displayId）",
                    diagnostics = "见 logcat 标签 Pvz2UserService / $TAG",
                )
            }
            cleanup()
            return false
        }
        update { it.copy(displayId = displayId, message = "虚拟屏就绪 displayId=$displayId") }

        // 拿一次 service，后面几步都用它 —— 不再反复经过 provider（那会重复 bind）。
        val service = shizukuServiceProvider()

        // ★ 建屏**当下**就把这块屏的客观状态记下来。
        //   导出日志时屏往往已被释放（服务被回收），那时再 dumpsys 什么也查不到。
        val displayInfo = describeDisplaySafely(service, displayId)
        update {
            it.copy(
                buildReport = displayInfo.raw,
                displayTrusted = displayInfo.trusted,
            )
        }
        Log.i(TAG, "建屏报告：${displayInfo.raw}")

        if (targetPackage != null) {
            launchOrMove(targetPackage, displayId)
            // 窗口归属才是"成没成"的唯一判据，搬完再查一次。
            val window = queryWindowDisplay(service, targetPackage)
            Log.i(TAG, "窗口归属：${window.raw}")
            update {
                it.copy(
                    buildReport = it.buildReport + "\n" + window.raw,
                    // 只要窗口不在主屏，就算搬成功了 —— 这是比 task 归属更硬的证据。
                    appOnDisplay = it.appOnDisplay && window.displayId != 0,
                )
            }
        }

        startFramePolling()
        return true
    }

    /** 屏的客观状态，解析好之后再判断，避免用 `contains` 做脆弱的字符串匹配。 */
    private data class DisplayInfo(val raw: String, val trusted: Boolean)

    private fun describeDisplaySafely(service: IPvz2Service?, displayId: Int): DisplayInfo {
        val raw = runCatching { service?.describeDisplay(displayId) }.getOrNull().orEmpty()
        // describeDisplay 返回形如 `display=42 type=VIRTUAL trusted=true owner=...`，
        // 按 `trusted=` 取值，而不是 contains("trusted=true") —— 后者在字段顺序变化时就错。
        val trusted = Regex("trusted=(true|false)").find(raw)?.groupValues?.get(1) == "true"
        return DisplayInfo(raw.ifBlank { "（查不到屏信息）" }, trusted)
    }

    /** 窗口归属：`mDisplayId=N` 里的 N，查不到返回 -1。 */
    private data class WindowInfo(val raw: String, val displayId: Int)

    private fun queryWindowDisplay(service: IPvz2Service?, packageName: String): WindowInfo {
        // 只取该包 Activity 的窗口那一段。用 -A2 抓它下面两行里的 mDisplayId。
        val raw = runCatching {
            service?.exec(
                "dumpsys window windows | grep -A2 '$packageName' | grep -m1 'mDisplayId='"
            )
        }.getOrNull().orEmpty().trim()

        val id = Regex("mDisplayId=(\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val text = if (id >= 0) {
            "窗口 mDisplayId=$id" + if (id == 0) "（仍在主屏 —— 未成功）" else "（已在副屏 ✓）"
        } else {
            "窗口归属：查不到（应用可能没启动）"
        }
        return WindowInfo(text, id)
    }

    /**
     * 建屏：先试**本进程**，失败再退回 shell 身份。
     *
     * 两条路的意义（都是实测出来的）：
     *  · 本进程：`createVirtualDisplay` 会校验「调用者包的 getPackageName() 属于本 uid」，
     *    本进程包名天然匹配，最省事；
     *  · shell：App 进程反射 @hide 的 `DisplayManager.<init>` 会被隐藏 API 限制拦住，
     *    所以必须有一条 shell 退路。而 shell 侧要传 `com.android.shell` 才过校验。
     */
    private suspend fun createDisplay(source: NativeCaptureSource): Int {
        val flags = VirtualDisplayFlags.forSdk(Build.VERSION.SDK_INT)

        val localId = runCatching {
            val created = VirtualDisplayCreator.create(
                callerContext = callerContext,
                name = VD_NAME,
                width = VD_WIDTH,
                height = VD_HEIGHT,
                dpi = VD_DPI,
                surface = source.surface!!,
                flags = flags,
            )
            localDisplay = created.virtualDisplay
            created.displayId
        }.onFailure { Log.w(TAG, "本进程建屏失败，改走 shell 身份", it) }
            .getOrDefault(-1)

        if (localId > 0) {
            Log.i(TAG, "本进程建屏成功 displayId=$localId")
            return localId
        }

        val service = shizukuServiceProvider() ?: run {
            Log.e(TAG, "shell 身份也不可用（拿不到 UserService）")
            return -1
        }
        shellService = service
        return runCatching {
            service.createVirtualDisplay(VD_NAME, VD_WIDTH, VD_HEIGHT, VD_DPI, source.surface!!, flags)
        }.onFailure { Log.e(TAG, "shell 身份建屏失败", it) }
            .getOrDefault(-1)
    }

    /**
     * 把应用搬到副屏：**先启动时指定 display**，再用客观状态校验。
     *
     * 顺序很重要：
     *  1. `launchAppOnDisplay` —— `ActivityOptions.launchDisplayId` 在启动那一刻指定副屏，
     *     这是唯一能让窗口跟过去的路径（`am start --display` 不吃 display）；
     *  2. 轮询校验 —— 只认 `getTaskDisplayId` 的结果，不做"应该成功了"的假设；
     *  3. 兜底 —— 仍不在副屏才 `moveTaskToDisplay` 硬搬一次。
     *
     * ★ 这三个调用**全部走 AIDL 到 UserService**：它们是隐藏 API 反射，
     *   在 App 进程里会被拦（`NoSuchMethodException: asInterface`），
     *   只有 app_process 里能用。
     */
    private suspend fun launchOrMove(packageName: String, displayId: Int) {
        update { it.copy(message = "正在把 $packageName 搬到虚拟屏…") }

        val service = shizukuServiceProvider()
        if (service == null) {
            update { it.copy(message = "Shizuku 服务不可用，无法搬应用") }
            return
        }

        if (service.getTaskDisplayId(packageName) == displayId) {
            Log.i(TAG, "$packageName 已经在这块屏上")
            update { it.copy(appOnDisplay = true, message = "$packageName 已在虚拟屏上") }
            return
        }

        val launched = service.launchAppOnDisplay(packageName, displayId)
        Log.i(TAG, "launchAppOnDisplay($packageName, $displayId) -> $launched")

        // 给系统时间把任务建起来，再决定要不要补一次"搬"。
        var onDisplay = false
        repeat(LAUNCH_POLL_TIMES) { i ->
            delay(LAUNCH_POLL_INTERVAL_MS)
            val current = service.getTaskDisplayId(packageName)
            if (current == displayId) {
                onDisplay = true
                Log.i(TAG, "第 ${i + 1} 次轮询：$packageName 已在 display $displayId")
            }
        }

        if (!onDisplay) {
            Log.w(TAG, "启动后任务没到副屏，尝试 moveTaskToDisplay 兜底")
            val moved = service.moveTaskToDisplay(packageName, displayId)
            delay(800)
            onDisplay = service.getTaskDisplayId(packageName) == displayId
            Log.i(TAG, "兜底搬移 taskId=$moved，最终在副屏=$onDisplay")
        }

        val tasks = runCatching { service.dumpTasks() }.getOrDefault("")
        update {
            it.copy(
                appOnDisplay = onDisplay,
                message = if (onDisplay) {
                    "$packageName 已在虚拟屏上"
                } else {
                    "$packageName 没能搬到虚拟屏（画面会是空的）"
                },
                diagnostics = tasks,
            )
        }
    }

    /**
     * 开始轮询帧。
     *
     * ## 取帧的正确姿势（照 ShadowAuto）
     *
     * 屏平时接在 [sink]（显示端）上；要取帧时才 `setSurface(采集端)`，
     * 取一帧立刻切回去。**不能让屏长期挂着 ImageReader** ——
     * 那样这块屏就没有显示端了，窗口不愿意往上挂。
     *
     * ## 判"活帧"用内容指纹，不用亮度
     *
     * 以前拿亮度当判据，而队列里的残留旧帧会让亮度一直非零，
     * 导致误判"有内容渲染"（在副屏上什么都没画时也报 7.12）。
     */
    private fun startFramePolling() {
        frameJob?.cancel()
        frameJob = scope.launch {
            while (isActive) {
                val source = captureSource
                if (source == null) {
                    delay(POLL_INTERVAL_MS)
                    continue
                }
                // native 采集端本身就是虚拟屏的输出面，不需要切换 surface —— 直接取。
                val frame = runCatching { source.acquireFrame() }.getOrNull()

                if (frame == null) {
                    delay(POLL_INTERVAL_MS)
                    continue
                }
                try {
                    val fp = fingerprint(frame)
                    val isNew = fp != lastFingerprint
                    lastFingerprint = fp
                    val w = frame.width()
                    val h = frame.height()
                    update { prev ->
                        prev.copy(
                            frameWidth = w,
                            frameHeight = h,
                            // 只有内容真的变了才算"活帧"
                            liveFrameCount = if (isNew) prev.liveFrameCount + 1 else prev.liveFrameCount,
                        )
                    }
                } finally {
                    frame.release()
                }
                // 取到帧后不 sleep：立刻回去取下一帧（acquireLatest 会自动丢旧帧）。
                // 需要节流时由采集端本身的帧率决定，而不是靠这里人为限速。
            }
        }
    }

    /** 对整帧稀疏采样算一个指纹。全黑帧的指纹恒定，所以"一直全黑"会被识别出来。 */
    private fun fingerprint(mat: Mat): Long {
        var hash = 1125899906842597L
        val stepY = (mat.rows() / 12).coerceAtLeast(1)
        val stepX = (mat.cols() / 16).coerceAtLeast(1)
        var y = 0
        while (y < mat.rows()) {
            var x = 0
            while (x < mat.cols()) {
                val p = mat.get(y, x)
                if (p != null) {
                    for (v in p) hash = hash * 31 + v.toLong()
                }
                x += stepX
            }
            y += stepY
        }
        return hash
    }

    /** 停止：先停轮询，再释放屏（顺序反了会让画面线程读到已释放的 buffer）。 */
    fun stop() {
        frameJob?.cancel()
        frameJob = null
        cleanup()
        update {
            State(message = "已停止", diagnostics = "")
        }
        Log.i(TAG, "虚拟屏会话已停止")
    }

    private fun cleanup() {
        runCatching { shellService?.releaseVirtualDisplay() }
        runCatching { VirtualDisplayCreator.release(localDisplay) }
        runCatching { captureSource?.close() }
        shellService = null
        localDisplay = null
        captureSource = null
        lastFingerprint = 0
    }

    private fun update(transform: (State) -> State) {
        val next = transform(_state.value)
        _state.value = next
        shared.value = next
    }


    companion object {
        /**
         * 进程级共享状态。
         *
         * 界面（MainActivity）与服务是两个独立组件，界面在服务没起来时**拿不到它的实例**
         * （`onBind` 返回 null），所以状态必须是进程级单例 ——
         * 沿用这个项目里 [com.pvz2.auto.core.BotState] 的做法。
         */
        val shared = MutableStateFlow(VirtualDisplaySession.State())

        /** 界面读它。只读，不暴露 MutableStateFlow。 */
        val sharedState: kotlinx.coroutines.flow.StateFlow<VirtualDisplaySession.State> = shared.asStateFlow()

        const val TAG = "VirtualDisplaySession"
        const val VD_NAME = "pvz2-virtual"
        const val VD_WIDTH = 1280
        const val VD_HEIGHT = 720
        const val VD_DPI = 240
        const val POLL_INTERVAL_MS = 120L

        /** 事件流水最多保留多少条。 */
        const val MAX_HISTORY = 200

        /** 队列空时的让出时长。取到帧时不 sleep，所以这个值不影响帧率上限。 */
        const val IDLE_POLL_MS = 8L
        const val FRAME_WAIT_MS = 800L
        const val LAUNCH_POLL_TIMES = 6
        const val LAUNCH_POLL_INTERVAL_MS = 500L
    }
}
