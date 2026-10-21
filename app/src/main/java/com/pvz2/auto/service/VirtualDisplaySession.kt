package com.pvz2.auto.service

import com.pvz2.auto.core.GameFps

import android.os.ParcelFileDescriptor
import org.opencv.core.CvType
import org.opencv.imgproc.Imgproc
import android.graphics.Bitmap
import org.opencv.android.Utils
import android.os.Build
import android.util.Log
import com.pvz2.auto.IPvz2Service
import com.pvz2.auto.shizuku.VirtualDisplayCreator
import com.pvz2.auto.shizuku.VirtualDisplayFlags
import com.pvz2.auto.shizuku.VirtualDisplaySurface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.updateAndGet
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
        /**
         * 实时帧率（帧/秒）。
         *
         * 统计的是**实际取到的帧数**，窗口 1 秒、上限 30。
         * 之前这里是"累计帧数"，但那个数字只增不减，看不出画面是不是卡住了 ——
         * 帧率才能立刻反映"采集还活着吗"。
         */
        val fps: Int = 0,
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


    /** 本进程建屏的句柄（@hide 类型，只能按 Any 持有）。 */
    private var localDisplay: Any? = null

    /** shell 侧建屏时用的 service，释放时要还给它。 */
    private var shellService: IPvz2Service? = null

    private var frameJob: Job? = null

    /** 上一帧的采样指纹，用来区分"真活帧"和"残影"。 */

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

        // ★ 建屏与采集必须在**同一个进程**（UserService）。
        //
        // 之前采集端建在 App 进程、屏建在 shell 进程，两边各持一个 AImageReader：
        // 屏接的是 shell 那块，App 读的是自己那块空的 —— 预览永远是黑的。
        // 现在一次性在 shell 侧完成"建 reader + 建屏"。
        val service = shizukuServiceProvider()
        if (service == null) {
            update { it.copy(running = false, message = "Shizuku 服务不可用（无法建屏）") }
            return false
        }
        // ★ 存进字段，否则 cleanup() 里的释放是死代码。
        //
        //   之前 service 只是个局部变量，`shellService` 从头到尾没被赋过值，
        //   于是 cleanup() 里 `shellService?.releaseVirtualDisplay()` 永远不执行，
        //   屏只能等 UserService 进程退出才被系统回收。start() 中途失败时
        //   屏会残留（虽然 UserService 重建时会先释放自己缓存的旧屏，不累积，
        //   但那是兜底，不该依赖）。
        shellService = service

        val displayId = runCatching {
            // 建屏前把刷新率告诉特权侧 —— 游戏的帧率跟着虚拟屏刷新率走。
            // ★ 不能 runCatching 静默吞掉。
            //
            //   之前这里包了 runCatching，结果"设了刷新率但没生效"完全看不出来 ——
            //   真机 dumpsys 里 `mRequestedRefreshRate=0.0` 才暴露出来。
            //   最可能的原因是**上一版 APK 留下的旧 UserService 进程还在**，
            //   它没有这个方法，调用直接抛异常。
            val wantFps = GameFps.prefs(callerContext)
            val wantRate = if (wantFps <= 0) 0f else wantFps.toFloat()
            try {
                service.setPendingRefreshRate(wantRate)
                Log.i(TAG, "请求虚拟屏刷新率 ${if (wantRate <= 0f) "系统默认" else "${wantRate}Hz"}")
            } catch (t: Throwable) {
                Log.e(TAG, "设置刷新率失败（多半是旧 UserService 进程没有这个方法）", t)
            }
            service.createDisplayWithCapture(
                VD_NAME, VD_WIDTH, VD_HEIGHT, VD_DPI,
                VirtualDisplayFlags.forSdk(Build.VERSION.SDK_INT)
            )
        }.onFailure { Log.e(TAG, "createDisplayWithCapture 失败", it) }
            .getOrDefault(-1)
        if (displayId <= 0) {
            update {
                it.copy(
                    running = false,
                    message = "建虚拟屏失败",
                    diagnostics = "见 logcat 标签 Pvz2UserService / $TAG",
                )
            }
            cleanup()
            return false
        }
        // displayId 是系统分配的、每次都不一样，对用户毫无意义 —— 只写进
        // 事件流水备查，**不进 message**。message 会直接显示在状态行上，
        // 之前用户就看到过"虚拟屏 59 运行中"里的那个 59。
        appendHistory("虚拟屏就绪 displayId=$displayId")

        // ★ 建好屏立刻接上共享缓冲。
        //
        //   不接的话读帧会走"每帧写 3.7MB 文件再读回来"的老路 ——
        //   720p@60fps 就是 440MB/s 的磁盘 I/O，实测只有 20fps。
        //   失败也不要紧：readFrameFromService 有文件路径兜底。
        if (!attachSharedFrame(service, VD_WIDTH, VD_HEIGHT)) {
            appendHistory("共享帧缓冲不可用，回落到文件搬运（帧率会明显偏低）")
        }
        update { it.copy(displayId = displayId, message = "虚拟屏就绪") }

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

    /**
     * 从 UserService 拉一帧，转成 Mat（BGR）。
     *
     * 链路：AIDL 要 fd → map 成 ByteBuffer → 按 RGBA 读进 Mat → 转 BGR。
     * 帧是紧凑排列的（native 侧已按 rowStride 裁过补白），所以可以直接整块读。
     */
    // ------------------------------------------------------------ 取帧缓冲
    //
    // 每帧 1280×720×4 = 3.7 MB。之前每次取帧都重新分配，加上 cvtColor，
    // 一帧要分配+拷贝约 15 MB，30fps 就是每秒 450 MB 的垃圾。
    // 下面这些缓冲在会话期内复用，只在尺寸变化时重建。

    /** 读文件用的字节缓冲。 */
    private var frameBytes: ByteArray = ByteArray(0)

    /**
     * 帧本体（native）。**CV_8UC4，通道顺序 RGBA。**
     *
     * ## 为什么不再转 BGR
     *
     * 之前这里会 `cvtColor(RGBA2BGR)` 转成 3 通道，然后再给预览转回 RGBA
     * —— 转过去又转回来，两次全图拷贝。
     *
     * 而识别侧根本不需要 BGR：
     *  - `AImageReader` 的格式是 `RGBA_8888`，读出来就是 R,G,B,A；
     *  - 模板是 `Utils.bitmapToMat(Bitmap ARGB_8888)`，同样是 R,G,B,A；
     *  - `matchTemplate` 只要求 `type == _templ.type()`（都是 CV_8UC4）。
     *
     * 所以这一帧可以**直接**既是预览源、又是识别源，零转换。
     */
    private var frameRgba: Mat? = null

    /** 确保缓冲尺寸够用。尺寸没变就直接返回，不做任何分配。 */
    private fun ensureFrameBuffers(w: Int, h: Int): Boolean {
        val need = w * h * 4
        if (need <= 0) return false

        // ★ OpenCV 必须先加载，否则下面的 Mat() 会抛 UnsatisfiedLinkError。
        //
        //   `initLocal()` 原本只在"启动自动化"和"识别自检"里调过，而采集帧
        //   是第三条路径。于是只建虚拟屏、没启动自动化时，每帧都在这里失败，
        //   现象是游戏在跑、声音都有，但预览永远黑屏 ——
        //   异常被吞成 W 级"读帧失败"，界面上看不出任何原因。
        if (!com.pvz2.auto.core.OpenCv.ensureLoaded()) return false

        if (frameBytes.size != need) frameBytes = ByteArray(need)

        val rgba = frameRgba
        if (rgba == null || rgba.width() != w || rgba.height() != h) {
            rgba?.release()
            frameRgba = Mat(h, w, CvType.CV_8UC4)
        }
        return true
    }

    private fun releaseFrameBuffers() {
        frameRgba?.release()
        frameRgba = null
        frameBytes = ByteArray(0)
    }

    /**
     * 保护 [frameRgba] 的**读写互斥**。
     *
     * ## 为什么必须有
     *
     * [frameRgba] 有两个使用者，跑在不同线程：
     *  - 帧轮询协程每 16ms 调一次 `rgba.put(0, 0, bytes, 0, need)` 写入 3.7MB；
     *  - 识别线程调 [latestFrameCopy] 做 `clone()` 读走 3.7MB。
     *
     * `Mat.put` 是**逐行 memcpy，不是原子的**。两者重叠时 `clone()` 会读到
     * 一个"上半新、下半旧"的撕裂帧 —— 拿这种帧做模板匹配不会报错，
     * 只会偶发地识别不到东西，是最难查的那类 bug。
     *
     * ## 代价
     *
     * 两边各持锁 1~2ms（一次 3.7MB 拷贝）。60fps 的预算是 16ms，
     * 串行化最多让采集掉一两帧，换来的是"绝不会读到撕裂帧"。
     * 相比每次识别错都要重新怀疑一遍坐标/阈值/模板，这个交换很划算。
     */
    private val frameLock = Any()

    /**
     * 供**识别**使用的帧副本。
     *
     * 为什么返回副本而不是共享缓冲：[frameRgba] 由帧轮询协程持续覆写，
     * 识别侧拿到引用后如果跨轮持有，会读到被改写一半的数据（见 [frameLock]）。
     * `clone()` 是一次 3.7MB 拷贝（约 1~2ms），相比原来 `screencap -p` 的
     * 40~90ms 可以忽略。
     *
     * 调用方**负责 release** 返回的 Mat。
     *
     * @return 没有帧或还没建屏时返回 null
     */
    fun latestFrameCopy(): Mat? = synchronized(frameLock) {
        val src = frameRgba ?: return@synchronized null
        if (src.empty()) return@synchronized null
        runCatching { src.clone() }.getOrNull()
    }

    // -------------------------------------------------------- 共享内存帧缓冲

    /**
     * 帧数据走共享内存，不再走文件。
     *
     * ## 为什么必须改
     *
     * 原来每帧：服务端写 3.7MB 临时文件 → rename → App 读回 3.7MB。
     * 720p@60fps 就是写 221MB/s + 读 221MB/s 的磁盘 I/O，实测把帧率
     * 死死压在 **20fps**。而虚拟屏本身是 60Hz，帧是够的 ——
     * 瓶颈全在这条搬运路径上。
     *
     * 共享内存是同一块物理页：服务端写，App 立刻可见。每帧只剩一次 memcpy
     * （3.7MB，约 0.5ms），没有 syscall 风暴、没有文件系统。
     */
    private var sharedFrame: java.nio.ByteBuffer? = null
    private var sharedFile: java.io.RandomAccessFile? = null

    /** 头长度，与 Pvz2UserService.FRAME_HEADER_BYTES 必须一致。 */
    private val frameHeaderBytes = 16

    /** 帧缓冲文件；目录与服务端 FRAME_DIR 一致，文件由服务端创建。 */
    private val FRAME_PATH = framesDir() + "/frame.shm"

    /** 建共享内存并交给服务端。失败返回 false，调用方回落到文件路径。 */
    /** 帧缓冲文件所在目录，必须与 Pvz2UserService.FRAME_DIR 一致。 */
    private fun framesDir(): String = "/data/local/tmp/pvz2"

    private fun attachSharedFrame(service: IPvz2Service, w: Int, h: Int): Boolean {
        val need = w * h * 4
        if (need <= 0) return false
        return try {
            detachSharedFrame()
            val total = need + frameHeaderBytes
            // ★ 顺序很关键：**先让服务端建并映射**，App 再打开。
            //
            //   `/data/local/tmp` 属于 shell，App（不同 uid）建不了目录，
            //   所以文件必须由服务端创建、把权限放开，App 才能打开同一路径，
            //   映射到同一份 page cache。
            val path = FRAME_PATH
            service.attachFrameBuffer(path, w, h)

            val raf = java.io.RandomAccessFile(path, "rw")
            if (raf.length() < total) {
                Log.e(TAG, "共享帧文件长度不对：" + raf.length() + " < " + total)
                runCatching { raf.close() }
                return false
            }
            val mapped = raf.channel.map(
                java.nio.channels.FileChannel.MapMode.READ_WRITE, 0, total.toLong()
            )
            sharedFile = raf
            sharedFrame = mapped
            Log.i(TAG, "共享帧缓冲就绪 ${w}x$h（$total 字节），不再走文件搬运")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "共享帧缓冲建立失败，回落到文件路径", t)
            detachSharedFrame()
            false
        }
    }

    private fun detachSharedFrame() {
        sharedFrame = null
        runCatching { sharedFile?.close() }
        sharedFile = null
    }

    private fun readFrameFromService(service: IPvz2Service): Mat? {
        // ★ pfd 必须在**所有**返回路径上关闭。
        //
        //   之前的写法是 `pfd.statSize <= 0` 和异常路径都直接 `return null`，
        //   pfd 就漏了。屏上没内容（黑帧）时每 120ms 漏一个 fd，
        //   长跑会耗尽每进程 1024 的文件描述符上限 —— 表现是采集突然全挂，
        //   且不会有一条"fd 用尽"的日志。
        //
        //   用 use{} 包住：它保证 close，包括提前 return 和抛异常。
        // ★ 优先走共享内存。文件路径只在共享内存不可用时兜底。
        val shared = sharedFrame
        if (shared != null) {
            val w = VD_WIDTH
            val h = VD_HEIGHT
            val need = w * h * 4
            if (!service.grabFrame()) return null
            if (!ensureFrameBuffers(w, h)) return null

            // seqlock：奇数说明服务端正在写，丢掉这一帧。
            // 硬读的话会拿到"上半新下半旧"的撕裂帧，识别结果莫名其妙且偶发。
            val seq1 = shared.getInt(0)
            if (seq1 and 1 == 1) return null
            val bytes = frameBytes
            shared.position(frameHeaderBytes)
            shared.get(bytes, 0, need)
            val seq2 = shared.getInt(0)
            if (seq1 != seq2) return null

            synchronized(frameLock) {
                val rgba = frameRgba ?: return null
                rgba.put(0, 0, bytes, 0, need)
            }
            return frameRgba
        }

        val pfd = service.acquireFrameFd() ?: return null
        return try {
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                val length = pfd.statSize
                if (length <= 0L) return null

                val w = VD_WIDTH
                val h = VD_HEIGHT
                val need = w * h * 4
                if (length.toInt() < need) return null
                if (!ensureFrameBuffers(w, h)) return null

                val bytes = frameBytes
                var read = 0
                while (read < need) {
                    val n = input.read(bytes, read, need - read)
                    if (n <= 0) break
                    read += n
                }
                if (read < need) return null

                // 写入也要持锁 —— 与 latestFrameCopy 的 clone 互斥，
                // 否则识别会读到撕裂帧（见 frameLock 的注释）。
                synchronized(frameLock) {
                    val rgba = frameRgba ?: return null
                    rgba.put(0, 0, bytes, 0, need)
                }
                // 不转 BGR —— 这一帧本身就是识别和预览都要的 RGBA（见 frameRgba 注释）。
                frameRgba
            }
        } catch (t: Throwable) {
            Log.w(TAG, "读帧失败", t)
            null
        }
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

    /**
     * 查这个包**所有**窗口分别在哪块屏上。
     *
     * ## 为什么不能用 `grep -A2 | grep -m1`
     *
     * 之前是这么写的：
     * ```bash
     * dumpsys window windows | grep -A2 '$packageName' | grep -m1 'mDisplayId='
     * ```
     * 两个问题：
     *  1. `-A2` 会连带匹配到**相邻窗口**的行，`mDisplayId` 可能是别人的；
     *  2. `-m1` 只取第一个，而一个应用通常有多个窗口
     *     （主 Activity、splash、输入法、Toast），只要有一个在主屏就会误判。
     *
     * 真机上就踩到了：报告说「已在副屏 ✓」，用户看到的却是整个物理屏被游戏占满。
     * 所以这里改成**把该包的每个窗口都列出来**，并给出「有没有窗口还在主屏」的结论。
     */
    private fun queryWindowDisplay(service: IPvz2Service?, packageName: String): WindowInfo {
        // 用 awk 按窗口分段解析：每个 `Window #N Window{... pkg}` 块里取它的 mDisplayId。
        // 比 grep 上下文匹配可靠得多。
        val script = """
            dumpsys window windows 2>/dev/null | awk '
              /^  Window #/ { win=${'$'}0 }
              /mDisplayId=/ {
                if (win != "" && win ~ /$packageName/) {
                  match(${'$'}0, /mDisplayId=[0-9]+/)
                  if (RSTART > 0) print substr(${'$'}0, RSTART, RLENGTH) " " win
                  win = ""
                }
              }'
        """.trimIndent()

        val raw = runCatching { service?.exec(script) }.getOrNull().orEmpty().trim()

        val lines = raw.lines().filter { it.contains("mDisplayId=") }
        if (lines.isEmpty()) {
            return WindowInfo("窗口归属：查不到（应用可能没启动，或窗口列表为空）", -1)
        }

        val ids = lines.mapNotNull {
            Regex("mDisplayId=(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull()
        }
        if (ids.isEmpty()) {
            return WindowInfo("窗口归属：解析失败", -1)
        }

        // 主屏（0）上还有窗口 = 没搬干净，用户会看到游戏占着物理屏。
        val onPrimary = ids.count { it == 0 }
        val onTarget = ids.count { it > 0 }
        val distinct = ids.distinct().sorted()

        val text = buildString {
            append("窗口分布：共 ${ids.size} 个窗口，display=$distinct")
            if (onPrimary > 0) {
                append("；⚠️ $onPrimary 个仍在主屏(0)")
            }
            if (onTarget > 0) {
                append("；$onTarget 个在副屏")
            }
        }
        // 只要还有窗口在主屏，就判定为「没完全搬过去」——
        // 这正是「整个屏幕变成游戏」的形态。
        return WindowInfo(text, if (onPrimary > 0) 0 else distinct.firstOrNull() ?: -1)
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

        // ★ 先杀掉现有任务 —— 这是「整个屏幕被游戏占满」的根因。
        //
        // 之前直接 launchAppOnDisplay()。但游戏若已经在主屏有 task，
        // 系统的行为是**把那个 task 提到前台**，而不是新建一个落在副屏上的
        // —— `launchDisplayId` 在这条路径上被完全忽略。
        // 用户看到的就是：点一下，整个物理屏变成游戏在跑，几秒后消失。
        //
        // MaaFwApp 的做法更细：已在目标屏就跳过 force-stop（避免反复杀进程
        // 导致应用永远停在启动阶段）。这里照抄。
        val alreadyThere = service.getTaskDisplayId(packageName) == displayId
        if (alreadyThere) {
            Log.i(TAG, "$packageName 已在 display $displayId，跳过 force-stop")
        } else {
            // ⚠️ 不能用 runCatching{...}.isSuccess 判断成败：
            //    exec() 内部已经包了 runCatching，永不抛异常，
            //    isSuccess 恒为 true —— 之前的日志里 `-> true` 是假的。
            //    要判断得看输出内容。
            val output = runCatching { service.exec("am force-stop $packageName") }
                .getOrDefault("")
            Log.i(TAG, "force-stop $packageName 输出：${output.trim().take(200)}")
            update { it.copy(message = "已停止旧任务，准备在副屏重新启动…") }

            // 等它真的消失。固定 delay 不够 —— 进程退出是异步的，
            // 600ms 在慢设备上常常不够，于是紧接着的 launch 又会复用旧 task。
            var gone = false
            for (i in 0 until FORCE_STOP_POLL_TIMES) {
                delay(FORCE_STOP_POLL_INTERVAL_MS)
                if (service.getTaskDisplayId(packageName) == NO_TASK) {
                    gone = true
                    Log.i(TAG, "第 ${i + 1} 次确认：$packageName 的任务已消失")
                    break
                }
            }
            if (!gone) Log.w(TAG, "force-stop 后任务仍在，继续尝试启动")
        }

        val launched = service.launchAppOnDisplay(packageName, displayId)
        Log.i(TAG, "launchAppOnDisplay($packageName, $displayId) -> $launched")
        update { it.copy(message = "启动指令已发出（$launched），等待任务出现…") }

        // ★ 启动**瞬间**就采一次窗口分布。
        //
        //   用户反馈"游戏启动时会在主屏闪一下"。任务级 API（getTaskDisplayId）
        //   只回答"任务在哪块屏"，看不到启动窗口（splash / starting window）——
        //   而闪现很可能正是它造成的：系统在游戏进程冷启动期间先挂一个
        //   starting window，那一刻任务还没落到副屏上。
        //
        //   这里在 launch 刚返回、游戏进程还在冷启动时采一次，日志里就能看到
        //   "启动瞬间有没有窗口在主屏(0)"。这是唯一能证实/推翻该假设的证据，
        //   不采就只能猜。
        val windowsAtLaunch = queryWindowDisplay(service, packageName)
        Log.i(TAG, "启动瞬间窗口分布：${windowsAtLaunch.raw}")
        appendHistory("启动瞬间：${windowsAtLaunch.raw}")

        // 给系统时间把任务建起来。
        //
        // ## 为什么要分两段轮询
        //
        // 真机上观察到：游戏启动时会**在主屏闪一下**，然后才出现在虚拟屏上。
        // 之前的轮询间隔是 500ms —— 第一枪打在 500ms，而闪现就发生在最初
        // 这半秒里，等于完全看不见，也没机会纠正。
        //
        // 现在前 [LAUNCH_POLL_FAST_TIMES] 次每 [LAUNCH_POLL_FAST_INTERVAL_MS]
        // 打一枪（合计约 1 秒），把轨迹记进事件流水；之后退回慢速轮询。
        //
        // ★ 快速段还有一个作用：**发现落在主屏就立刻搬走**。
        //   等它自己过去要等好几枪，每一枪都是用户能看到的一次闪现。
        //   一发现 current==0 就马上 moveTaskToDisplay，能把可见时间压到最短。
        var onDisplay = false
        var everSawPrimary = false
        var correctedCount = 0
        val trajectory = StringBuilder()

        for (i in 0 until LAUNCH_POLL_FAST_TIMES) {
            delay(LAUNCH_POLL_FAST_INTERVAL_MS)
            val current = service.getTaskDisplayId(packageName)
            trajectory.append(if (i == 0) "$current" else "→$current")

            if (current == displayId) {
                onDisplay = true
                Log.i(TAG, "第 ${i + 1} 次（快速）：$packageName 已在 display $displayId，轨迹 $trajectory")
                break
            }

            // 落在主屏 = 用户正看着游戏盖住物理屏。立刻搬，不等下一枪。
            if (current == 0) {
                everSawPrimary = true
                correctedCount++
                Log.w(TAG, "第 ${i + 1} 次（快速）：$packageName 落在主屏，立即搬移到 $displayId")
                runCatching { service.moveTaskToDisplay(packageName, displayId) }
            }
        }

        if (!onDisplay) {
            for (i in 0 until LAUNCH_POLL_TIMES) {
                delay(LAUNCH_POLL_INTERVAL_MS)
                val current = service.getTaskDisplayId(packageName)
                trajectory.append("→$current")
                if (current == 0) everSawPrimary = true
                Log.i(TAG, "第 ${i + 1} 次（慢速）：$packageName 在 display $current（期望 $displayId）")
                if (current == displayId) {
                    onDisplay = true
                    break
                }
            }
        }

        // 把轨迹写进事件流水 —— 这是判断"主屏闪现"到底发生了多久的唯一依据。
        appendHistory("任务轨迹：${trajectory.ifEmpty { "未出现" }}")

        if (!onDisplay) {
            Log.w(TAG, "启动后任务没到副屏，尝试 moveTaskToDisplay 兜底")
            update { it.copy(message = "任务不在副屏，尝试强制搬移…") }
            val moved = service.moveTaskToDisplay(packageName, displayId)
            delay(800)
            onDisplay = service.getTaskDisplayId(packageName) == displayId
            Log.i(TAG, "兜底搬移 taskId=$moved，最终在副屏=$onDisplay")
        } else if (everSawPrimary) {
            // 任务曾经落在主屏（用户看到的那一下闪现）。虽然现在已经过去了，
            // 仍要记一笔 —— 这是"启动时闪主屏"的直接证据。
            Log.w(TAG, "$packageName 启动过程中曾在主屏出现过，轨迹 $trajectory")
            appendHistory("⚠️ 启动时曾在主屏出现（轨迹 $trajectory）")
        }

        // ★ 再采一次**窗口**分布。
        //
        //   `getTaskDisplayId` 只回答"任务在哪块屏"，看不到启动窗口（splash）。
        //   用户看到的"游戏在主屏闪一下"，很可能是游戏进程冷启动时系统先给它
        //   挂了一个 starting window，而那一刻任务还没落到副屏上。
        //   只有窗口级采样才能证实或推翻这个假设 —— 所以这里记一笔，
        //   下次日志里能看到 `窗口分布：...` 这一行。
        if (onDisplay) {
            val windows = queryWindowDisplay(service, packageName)
            Log.i(TAG, "启动后窗口分布：${windows.raw}")
            appendHistory("窗口分布：${windows.raw}")
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
                // 本帧的起始时刻。节流要用它算 —— 用"处理完的时刻"会让
                // 每帧白睡一个完整间隔，帧率被处理时间拖低（见下面的注释）。
                val frameStartedAt = android.os.SystemClock.uptimeMillis()

                val svc = shizukuServiceProvider()
                if (svc == null) {
                    delay(POLL_INTERVAL_MS)
                    continue
                }
                // native 采集端本身就是虚拟屏的输出面，不需要切换 surface —— 直接取。
                val frame = runCatching { readFrameFromService(svc) }.getOrNull()

                if (frame == null) {
                    // ★ 这里**不能**用 POLL_INTERVAL_MS(120ms)。
                    //
                    //   这是帧率的真正瓶颈，而且很难看出来：120ms 原本是
                    //   "服务不可用时的重试间隔"，却被顺手用在了"这一轮没取到
                    //   新帧"上。
                    //
                    //   实测：生产者 120fps（帧间隔 8.3ms，SurfaceFlinger 时间戳
                    //   证明）。我们取到一帧、处理约 4ms、再取 —— 这时新帧还没到
                    //   （还差 4ms），于是 frame == null，睡 120ms。
                    //   结果每 124ms 才取一帧 = 8fps。界面读数 10fps 就是这么来的。
                    //
                    //   换成 2ms（远小于一帧间隔）即可每帧都接住：
                    //   轮询开销可以忽略，又不会忙等烧 CPU。
                    delay(FRAME_RETRY_INTERVAL_MS)
                    continue
                }

                // ★ 这里**不能** release frame —— 它是复用的缓冲（见 frameRgba）。
                //   release 掉的话下一帧往同一块内存写就会崩，或者 OpenCV 内部
                //   重新分配、复用彻底失效。缓冲的生命周期归 ensureFrameBuffers /
                //   releaseFrameBuffers 管。

                val w = frame.width()
                val h = frame.height()

                // ★ 每帧只做"记账"，**界面固定 1 秒才刷新一次**。
                //
                //   之前每帧都 update { }，StateFlow 每秒被推 30~60 次，
                //   界面跟着重组同样次数 —— 数字乱跳、看着晃眼，白占主线程。
                //
                //   现在：帧时间戳每帧都记（保证算法准确），但 State 只在
                //   距上次刷新超过 1 秒时才推一次，界面稳定在 1Hz。
                //   不能改成"数值变化才刷新" —— fps 在 33/34/35 之间抖的时候
                //   那等于每帧都变，还是会刷屏。
                val fps = recordFrameAndComputeFps()

                val now = android.os.SystemClock.uptimeMillis()
                val dueForUiUpdate = now - lastUiPublishAt >= UI_PUBLISH_INTERVAL_MS
                val sizeChanged = w != _state.value.frameWidth || h != _state.value.frameHeight
                if (dueForUiUpdate || sizeChanged) {
                    lastUiPublishAt = now
                    update { prev ->
                        prev.copy(
                            frameWidth = w,
                            frameHeight = h,
                            fps = fps,
                        )
                    }
                }
                publishPreview(frame)

                // ★ 上限 [MAX_FPS]。
                //
                // ## 时间基准必须是「本帧**开始取**的时刻」
                //
                // 之前 lastCaptureAt 是在 `recordFrameAndComputeFps()` 里设的，
                // 也就是"处理完之后"。于是 `elapsed = now - lastCaptureAt ≈ 0`，
                // 每帧都固定睡满 16ms —— 处理时间（约 13ms）全加在上面，
                // 周期 = 13 + 16 = 29ms，实测正好卡在 **34fps**，从来上不去。
                //
                // 现在用帧开始的时刻算：周期 = max(16ms, 处理耗时)。
                // 处理 13ms 时就是 16ms = 60fps，处理 20ms 时才是 50fps。
                // 节流只在"这一轮比目标还快"时才补睡。
                val elapsed = android.os.SystemClock.uptimeMillis() - frameStartedAt
                val remain = MIN_FRAME_INTERVAL_MS - elapsed
                if (remain > 0) delay(remain)
            }
        }
    }

    // ---------------------------------------------------------------- 帧率

    /**
     * 最近一段时间内每帧的时间戳，用来算实时帧率。
     *
     * 只保留 [FPS_WINDOW_MS] 窗口内的，超期的从头部移除 —— 于是
     * `size` 直接就是"过去一秒收到了多少帧"。
     */
    private val frameTimes = java.util.ArrayDeque<Long>()

    /** 上次推送界面状态的时间。界面刷新固定 1Hz，不跟采集帧率走。 */
    private var lastUiPublishAt = 0L


    /**
     * 记一帧并返回当前实时帧率。
     *
     * 统计的是**实际取到的帧数**（不管内容有没有变化）—— 这才是"帧率"。
     * 而"画面是否在动"是另一件事 —— 采集停了 fps 会掉到 0，卡住则 fps 正常但内容不动。
     */
    private fun recordFrameAndComputeFps(): Int {
        val now = android.os.SystemClock.uptimeMillis()

        frameTimes.addLast(now)
        while (frameTimes.isNotEmpty() && now - frameTimes.first() > FPS_WINDOW_MS) {
            frameTimes.removeFirst()
        }
        return Fps.compute(frameTimes, now)
    }


    // ---------------------------------------------------------------- 预览

    /** 上次发布预览的时间戳。用来节流 —— 60fps 全量转 Bitmap 会把主线程拖死。 */
    private var lastPreviewAt = 0L

    /**
     * 把一帧发布给界面。
     *
     * ## 为什么之前预览一直是黑的
     *
     * 采集链路其实是通的（真机日志里 `画面更新次数: 740` 证明取到了 740 帧），
     * 但**没有任何代码把帧送到 ImageView** —— `ivVdPreview` 从定义至今
     * 一次都没被赋过值。采集端取到的像素全留在内存里，界面上什么都看不到。
     *
     * ## 为什么要节流
     *
     * Mat → Bitmap 是全量像素拷贝（720p 约 3.7 MB）。每个采集帧都转一次
     * （最高 60fps）会吃掉大量 CPU 并让 GC 抖动。预览给人看，10fps 足够。
     */
    private fun publishPreview(frame: Mat) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastPreviewAt < PREVIEW_INTERVAL_MS) return
        lastPreviewAt = now

        // 全分辨率转换。
        //
        // 曾经以为这里是瓶颈（缩到 1/3 试过），**实测帧率一点没变** ——
        // 说明转换不是瓶颈，缩略只是把画面弄糊了。真正的瓶颈在
        // [POLL_INTERVAL_MS] 那个 120ms 的空转等待，见下面的注释。
        val bitmap = runCatching {
            val bmp = Bitmap.createBitmap(frame.width(), frame.height(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(frame, bmp)
            bmp
        }.getOrNull() ?: return

        // ★ 不主动 recycle 旧 bitmap。
        //
        //   之前是 `old?.recycle()`。但 ImageView 可能仍持有上一帧：Activity
        //   退到后台时 collect 停止、轮询却继续，ImageView 里存的就是那张
        //   已被回收的 Bitmap，回到前台重绘直接崩 "Canvas: trying to use a
        //   recycled bitmap"。
        //
        //   每秒 10 张 720p ARGB 约 37 MB/s，看起来很多，但这些是**托管内存**，
        //   ART 会正常回收；而手动 recycle 换来的是随机崩溃。宁可让 GC 处理。
        previewFlow.value = bitmap
    }

    // fingerprint() 已删除。
    //
    // 它原本用来判断"画面是否在动"（内容指纹变化才算活帧）。改成实时帧率后
    // 这个判断不再需要，而它每帧要对整帧做 192 次 `mat.get()` —— 每次都是
    // 一次 JNI 调用，在 34fps 下就是每秒 6500 次。留着纯属白烧 CPU。

    /**
     * 停止：先停轮询，再释放屏。
     *
     * ## 不能把诊断信息一起清掉
     *
     * 之前这里直接造一个 `State(message = "已停止")` 全新对象 ——
     * buildReport / displayId / displayTrusted / history 全部归零。
     * 用户建好屏、点停止、再导出日志，看到的就是
     * 「建屏是否成功: 否」「没有事件记录」，把真正发生的成功覆盖掉了。
     *
     * 诊断信息应当**保留到下一次建屏**，而不是跟随运行状态重置。
     */
    fun stop() {
        // ★ 必须**等到帧循环真正退出**之后才能释放缓冲。
        //
        //   `cancel()` 是协作式的：帧循环可能正卡在不可取消的段里 ——
        //   Binder 取帧（跨进程）+ 3.7MB 读 + cvtColor，一次 15~40ms。
        //   cancel 下去它不会立刻停，还会把这一轮跑完，包括往
        //   frameRgba 里写数据。
        //
        //   而 cleanup() 紧接着就把这两个 Mat release 掉了 —— 于是
        //   cvtColor 作用在已释放的 native 内存上：轻则 CvException 丢帧，
        //   重则 OpenCV 在已释放的 Mat 上 memcpy 到空指针。
        //
        //   这个窗口在 60fps 下**几乎每次停止都会命中**。
        //   （上一轮我把缓冲从"每帧新分配"改成"复用"之后，这里就从浪费
        //     变成了 use-after-free —— 是我的回归。）
        val job = frameJob
        frameJob = null
        if (job != null) {
            runBlocking {
                // 给个上限：万一 binder 调用卡死，也不能让 UI 永远转圈。
                withTimeoutOrNull(STOP_JOIN_TIMEOUT_MS) { job.cancelAndJoin() }
            }
        }
        cleanup()

        val prev = _state.value
        // displayId 只写日志。停止后它还会从 message 漏到状态行上，
        // 用户会看到"已停止（上次用过 displayId=59）"—— 同样是噪音。
        if (prev.displayId > 0) appendHistory("已停止（上次用过 displayId=${prev.displayId}）")
        else appendHistory("已停止")

        val next = _state.value.copy(
            running = false,
            message = "已停止",
            diagnostics = "",
            history = eventLog.toList(),
        )
        _state.value = next
        shared.value = next

        // 清掉最后一帧。否则停屏后画面上还挂着停止前的那张图（3.7MB 位图
        // 被静态持有），看起来像"还在跑"。
        previewFlow.value = null

        Log.i(TAG, "虚拟屏会话已停止（保留 buildReport / history 供导出）")
    }

    private fun cleanup() {
        // 先停轮询再释放资源。顺序反了的话，轮询协程会对着已释放的缓冲 /
        // 已关闭的采集端继续跑，每轮一次 JNI 调用，直到进程结束。
        frameJob?.cancel()
        frameJob = null

        runCatching { shellService?.releaseVirtualDisplay() }
        runCatching { VirtualDisplayCreator.release(localDisplay) }
        // 采集端在 shell 侧，随 releaseVirtualDisplay 一起释放（见 Pvz2UserService）

        // 帧缓冲是 native 内存（两个 Mat 共约 7.4 MB），必须显式释放，
        // 否则每次开关虚拟屏都漏一块。
        releaseFrameBuffers()

        shellService = null
        localDisplay = null
    }

    /**
     * 更新状态，并把 message 的变化记进事件流水。
     *
     * ## 为什么自己记流水
     *
     * 之前诊断全靠 logcat。但真机上游戏会把 logcat 缓冲刷得飞快，
     * 抓出来只剩三行（第一次真机日志就是这样），整个建屏过程全丢。
     * 自己的流水存在内存里，不受别人刷屏影响。
     */
    private fun update(transform: (State) -> State) {
        // ★ 用 update{}（CAS 重试）而不是「读 → 改 → 写」。
        //
        //   之前的写法是 `transform(_state.value)` 然后赋值。帧循环、
        //   start()、launchOrMove()、stop() 跑在不同的 IO 线程上，两个
        //   调用可能交错：
        //      线程A 读 prev(running=true) → 加 fps
        //      线程B 读 prev(running=true) → 置 running=false
        //      线程A 回写 running=true     ← stop() 白做了
        //   反过来 stop() 也可能把帧循环刚写的 fps 覆盖掉。
        //
        //   MutableStateFlow.update{} 内部是 compareAndSet 重试，能保证
        //   每次变换都基于最新的值。
        var messageChanged = false
        var newMessage = ""
        val withLog = _state.updateAndGet { prev ->
            val next = transform(prev)
            if (next.message != prev.message) {
                messageChanged = true
                newMessage = next.message
            }
            next.copy(history = eventLog.toList())
        }
        // history 的记录放在 CAS 之后：appendHistory 会改 eventLog，
        // 不能放在 updateAndGet 的 lambda 里（重试时会重复写入）。
        if (messageChanged) appendHistory(newMessage)
        shared.value = withLog
    }

    /** 事件流水本体。只在 [update] 与 [stop] 里写。 */
    /**
     * 事件流水本体。
     *
     * 用 [CopyOnWriteArrayList] 而不是 ArrayList：本类的 scope 是
     * `Dispatchers.IO`（多线程池），[stop] 与帧轮询协程可能在不同线程上
     * 同时读写这个列表 —— `stop()` 里 `frameJob.cancel()` 之后没有 join，
     * 而帧循环可能正卡在不可取消的 binder 读取里，返回后仍会调 [update]
     * → [appendHistory] 写入。普通 ArrayList 在这种交错下会抛
     * ConcurrentModificationException。
     *
     * 写入频率是"状态变化时"，每次一条，量极小，CopyOnWrite 的写放大无所谓。
     */
    private val eventLog = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun appendHistory(message: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date())
        eventLog += "[$stamp] $message"
        while (eventLog.size > MAX_HISTORY) eventLog.removeAt(0)
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

        /**
         * 预览帧。界面直接 collect 它设进 ImageView。
         *
         * 用单独的 flow 而不是塞进 [State]：Bitmap 是可变大对象，
         * 放进 State 会让每次状态变化都把它复制一份引用，也容易忘了回收。
         */
        val previewFlow = MutableStateFlow<Bitmap?>(null)

        /** 界面读它。只读，不暴露 MutableStateFlow。 */
        val sharedState: kotlinx.coroutines.flow.StateFlow<VirtualDisplaySession.State> = shared.asStateFlow()

        const val TAG = "VirtualDisplaySession"
        const val VD_NAME = "pvz2-virtual"
        const val VD_WIDTH = 1280
        const val VD_HEIGHT = 720
        const val VD_DPI = 240
        const val POLL_INTERVAL_MS = 120L

        /**
         * 这一轮没取到新帧时的重试间隔，毫秒。
         *
         * 必须**远小于**一帧的间隔（120fps 时是 8.3ms），否则每次错过一帧
         * 都要白等一整个周期 —— 那正是帧率被压到 8fps 的原因。
         * 2ms 足够细，又不会像 0ms 那样忙等。
         */
        const val FRAME_RETRY_INTERVAL_MS = 2L

        /**
         * 停止时等待帧循环退出的上限。
         *
         * 必须等 —— 帧循环可能正卡在不可取消的 Binder 取帧里，此时释放
         * 复用缓冲就是 use-after-free。但也不能无限等：万一 binder 真的
         * 卡死，UI 不能永远转圈。超过这个时间就放弃等待、直接释放
         * （此时冒一次风险，好过整个应用无响应）。
         */
        const val STOP_JOIN_TIMEOUT_MS = 1500L

        /**
         * 界面状态刷新间隔。
         *
         * 采集可以跑到 60fps，但界面没必要跟着刷 —— 用户看的是一个"帧率多少"
         * 的读数，每秒更新一次就够了。跟着刷只会让数字乱跳、白占主线程。
         */
        const val UI_PUBLISH_INTERVAL_MS = 1000L

        /** 帧率统计窗口：过去 1 秒内收到多少帧就是多少 fps。 */
        const val FPS_WINDOW_MS = 1000L

        /** 帧率上限，同时也是采集节流目标。 */
        const val MAX_FPS = 60

        /** 两帧之间的最小间隔，用来把采集压在 30fps 以内。 */
        /**
         * 采集节流下限，毫秒。
         *
         * ## 为什么现在是 0
         *
         * 以前是 `1000 / 60 = 16ms`，也就是采集端自己给自己设了个 60fps 上限。
         * 但虚拟屏出帧有多快是**系统决定**的，采集端再补一次睡只会白等 ——
         * 而且这个上限会让人误以为"游戏只能跑 60fps"，掩盖真正的瓶颈。
         *
         * 现在不节流：`AImageReader` 出多少帧就取多少帧。
         * 游戏帧率该由**虚拟屏的刷新率**决定（这是用户可以选的设置），
         * 而不是被采集端偷偷截断。
         */
        const val MIN_FRAME_INTERVAL_MS = 0L

        /** 窗口数据少于这个时长时不做折算，避免刚启动时报出假的低帧率。 */
        const val MIN_FPS_SPAN_MS = 300L

        /** 预览刷新间隔。60fps 全转 Bitmap 太重，10fps 看着已经够流畅。 */
        /**
         * 预览画面的最小更新间隔，毫秒。
         *
         * ## 这个值曾经让"看着卡"和"读数很高"同时成立
         *
         * 采集是 120fps，但预览每 100ms 才更新一次 —— 于是状态行写着 120 fps，
         * 屏幕上那幅图每秒只换 10 次，看着当然卡。
         *
         * **读数抓的是采集，眼睛看的是预览**，两个是独立的。
         * 原来写 100 是因为"全量转 Bitmap 很贵，怕拖死主线程"；
         * 但实测把转换缩到 1/9 像素后采集帧率一点没变，说明转换的真实成本
         * 远没有当初估的那么高，100ms 属于过度保守。
         *
         * 33ms ≈ 30fps，肉眼已经连续；同时给采集留出余量。
         */
        const val PREVIEW_INTERVAL_MS = 33L

        /** 预览缩小倍数。1/3 时像素量只剩 1/9，视觉上几乎无差别。 */
        const val PREVIEW_DOWNSCALE = 3

        /** 事件流水最多保留多少条。 */
        const val MAX_HISTORY = 200

        /** 队列空时的让出时长。取到帧时不 sleep，所以这个值不影响帧率上限。 */
        const val IDLE_POLL_MS = 8L
        const val FRAME_WAIT_MS = 800L
        /** 慢速轮询次数（快速段之后继续等）。 */
        const val LAUNCH_POLL_TIMES = 10

        /**
         * 启动后的快速采样次数。
         *
         * 主屏闪现发生在最初半秒内，500ms 的第一枪根本看不见。
         * 20 次 × 50ms ≈ 1 秒，足够覆盖"进程冷启动 → 窗口创建"这段。
         */
        const val LAUNCH_POLL_FAST_TIMES = 20
        const val LAUNCH_POLL_FAST_INTERVAL_MS = 50L
        const val LAUNCH_POLL_INTERVAL_MS = 500L

        /** force-stop 后等任务消失的轮询次数（每次 200ms）。 */
        const val FORCE_STOP_POLL_TIMES = 15
        const val FORCE_STOP_POLL_INTERVAL_MS = 200L

        /** `getTaskDisplayId` 表示"该包没有任务"的返回值（与 DisplayTaskMover 一致）。 */
        const val NO_TASK = -2
    }
}
