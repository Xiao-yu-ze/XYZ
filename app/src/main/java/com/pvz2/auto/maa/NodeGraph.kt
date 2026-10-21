package com.pvz2.auto.maa

import android.util.Log
import com.pvz2.auto.core.BotState
import com.pvz2.auto.core.ScriptRunState
import com.pvz2.auto.core.ScreenCapturer
import com.pvz2.auto.core.TapJitter
import com.pvz2.auto.core.TouchInjector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.opencv.core.Core
import org.opencv.core.Mat
import kotlin.math.roundToInt

/**
 * 节点图执行器，按 MaaFramework Pipeline 协议语义逐步运行。
 *
 * 执行模型（对应官方"节点流程"）：
 *
 *   1. 对当前节点的 `next` 列表**按顺序**逐个识别，第一个命中的生效；
 *   2. 本轮全都没命中就进入下一轮，直到超过 `timeout`，然后走 `on_error`；
 *   3. 命中后先等 `pre_delay`，执行动作，再等 `post_delay`；
 *   4. 进入命中节点的 `next`，重复上述过程；
 *   5. `next` 为空即终止。
 *
 * `enabled=false` 和 `max_hit` 超限的节点会被从候选里跳过（官方语义）。
 *
 * 必须运行在 `Dispatchers.IO` 协程里：截图与注入都是阻塞操作。
 */
class NodeGraph(
    private val capturer: ScreenCapturer,
    private val injector: TouchInjector,
    private val recognizer: Recognizer,
    private val nodes: Map<String, NodeDef>,
    /** 自定义动作实现表，key = MaaPVZ 注册名（JobSetLoad / BatchSwipe / ...）。 */
    private val customActions: Map<String, CustomActionHandler> = emptyMap(),
    /**
     * true = 只走识别与决策，**不真正点击**，把「本该点哪里」打进日志。
     *
     * 首次在真机验证一条新链时务必打开：pipeline、模板、ROI、坐标任何一处错了，
     * 干跑都能看出来，而不会在游戏里乱点。
     */
    private val dryRun: Boolean = false
) {

    /**
     * 停止原因。
     *
     * ## 为什么需要它
     *
     * 图执行有多个"静默 return"的位置：节点不存在、next 为空、
     * 动作失败且无 on_error、识别超时。这些在日志里各有一条，
     * 但调用方只看得到"run() 返回了"，于是统统报成"流程结束" ——
     * 脚本第二步就挂了，界面却说正常跑完。
     *
     * 现在每个出口都写上具体原因，调用方用它决定报什么。
     */
    var stopReason: String? = null
        private set

    /** 节点显式要求结束（`StopTask`）。与"出错"区分开。 */
    private var stopRequested = false

    /** 每个节点在本轮任务里被命中的次数，用于 max_hit。 */
    private val hitCounts = HashMap<String, Long>()

    /** 当前帧；每轮识别前刷新一次。 */
    private var frame: Mat? = null

    private var frameOwner: Boolean = false

    /** 便于自定义动作读取当前帧（例如 BatchSwipe 需要截图做守卫判断）。 */
    val currentFrame: Mat? get() = frame

    val adapter get() = recognizer.adapter

    /** 运行直到 entry 链结束或被取消。 */
    suspend fun run(entry: String) {
        var current = entry
        Log.i(TAG, "从节点 [$entry] 开始执行")

        while (currentCoroutineContext().isActive) {
            val node = nodes[current]
            if (node == null) {
                stopReason = "节点不存在：$current"
                Log.w(TAG, stopReason!!)
                return
            }

            val next = resolveNext(node)
            if (next.isEmpty()) {
                stopReason = "节点 [$current] 没有可用的下一步（next 里引用的节点都不存在）"
                Log.w(TAG, stopReason!!)
                return
            }

            // 先在 lambda 外算好，避免在 run{} 内联 lambda 里写 continue
            // （那需要开启实验性的 "break continue in inline lambdas" 特性）。
            val hit = waitForHit(node, next)
            if (hit == null) {
                // 超时：走 on_error；没有 on_error 就结束。
                if (node.onError.isEmpty()) {
                    stopReason = "节点 [$current] 识别超时（${node.timeout}ms）且无 on_error"
                    Log.w(TAG, stopReason!!)
                    return
                }
                Log.i(TAG, "节点 [$current] 超时，转 on_error")
                current = node.onError.first()
                continue
            }

            if (!execute(hit, node)) {
                if (node.onError.isEmpty()) {
                    // stopReason 可能已经被 execute() 写过（例如缺少自定义动作），
                    // 那就保留更具体的那句；否则补一句通用的。
                    if (stopReason == null) {
                        stopReason = "节点 [$current] 动作执行失败且无 on_error"
                        Log.w(TAG, stopReason!!)
                    }
                    return
                }
                current = node.onError.first()
                continue
            }

            if (stopRequested) {
                stopReason = "流程结束"
                Log.i(TAG, "StopTask：流程正常结束")
                return
            }

            if (lastWasJumpBack) {
                // 回边上的兜底节点执行完了，**回到父节点重新判断**，不往前走。
                //
                // 推进 current 的话会跳过父节点原本要等的画面，
                // 表现是"兜底跑完直接往下走了"，链在错误的界面上继续 ——
                // 而回边的整个意义就是"修正完接着等原来那个"。
                Log.i(TAG, "JumpBack [${hit.name}] 已处理，回到父节点 [${node.name}] 重新判断")
                continue
            }

            current = hit.name
            // post_wait_freezes：等画面稳定再往下走（自适应等待，见函数说明）。
            // 必须在 post_delay 之前 —— 先等稳，再补一个固定的动作后间隔。
            if (hit.postWaitFreezes > 0) waitForFreeze(hit.postWaitFreezes)
            if (currentCoroutineContext().isActive) delay(hit.postDelay)
        }
    }

    /**
     * 在 [next] 里按优先级轮询识别，直到命中或超时。
     *
     * @return 命中的节点定义；超时返回 null。
     */
    private suspend fun waitForHit(parent: NodeDef, next: List<String>): NodeDef? {
        val deadline = System.currentTimeMillis() + parent.timeout
        var lastSleep = 0L

        while (currentCoroutineContext().isActive) {
            val roundStart = System.nanoTime()

            if (!refreshFrame()) {
                delay(RETRY_ON_CAPTURE_FAILURE)
                if (System.currentTimeMillis() > deadline) return null
                continue
            }

            val current = frame
            if (current != null) {
                // 用普通 for 而不是内联 lambda，避免依赖实验性的
                // "break/continue in inline lambdas" 特性。
                for (raw in next) {
                    val name = stripJumpBack(raw)
                    val candidate = nodes[name] ?: continue
                    if (!isEligible(candidate)) continue

                    val hit = try {
                        recognizer.recognize(current, candidate.recognition, candidate.orderBy)
                    } catch (t: Throwable) {
                        Log.w(TAG, "识别 [$name] 出错", t)
                        null
                    }
                    if (hit != null) {
                        lastHit = hit
                        reportHit(candidate.name)
                        // 记录边类型：回边执行完要回到父节点，普通边才是前进。
                        lastWasJumpBack = NodeEdges.isJumpBack(raw)
                        return candidate
                    }
                }
            }

            // 把"这一轮在等谁、花了多久"报给界面。
            //
            // 没有这个，用户看到画面不动时分不清两件事：脚本卡死了，
            // 还是它在正常地等一个还没出现的画面 —— 处理方式完全相反。
            reportRound(parent.name, roundStart, next)

            val elapsed = System.currentTimeMillis() - (deadline - parent.timeout)
            // rate_limit 是"每轮识别的最低耗时"，不足则补睡（官方语义）。
            val need = parent.rateLimit - elapsed
            lastSleep = need.coerceAtLeast(0L)
            if (lastSleep > 0) delay(lastSleep)
            // repeat_delay：本节点重试前的额外间隔。
            // 用于低频轮询的节点（等一个很久才出现的东西），
            // 没必要每 16ms 全屏找一遍。真实脚本里出现的值是 0，即不额外等。
            if (parent.repeatDelay > 0) delay(parent.repeatDelay)

            if (System.currentTimeMillis() > deadline) return null
        }
        return null
    }

    /**
     * 等画面**稳定**下来：连续 [freezeMs] 毫秒没有明显变化就返回。
     *
     * ## 为什么不用固定的 post_delay
     *
     * 动作引起的画面变化时长不固定：一次滑动可能 150ms 就停了，
     * 也可能因为惯性滚 800ms。死等固定值要么白等，要么在画面还在动的
     * 时候就去做下一次识别 —— 后者会识别到中间态，然后点错位置。
     *
     * ## 怎么判断"没变化"
     *
     * 用 OpenCV 的无穷范数（两帧逐像素最大绝对差）。它一次遍历就能算完，
     * 而且不额外分配整帧内存 —— 全屏 1280×720 也只要 1ms 量级。
     * 阈值不能是 0：视频解码和采集本身就有极小的抖动，永远等不到完全相等。
     */
    private suspend fun waitForFreeze(freezeMs: Long) {
        if (freezeMs <= 0) return
        val deadline = System.currentTimeMillis() + FREEZE_MAX_WAIT_MS
        var previous: Mat? = null
        var stableSince = 0L

        try {
            while (currentCoroutineContext().isActive) {
                if (!refreshFrame()) {
                    delay(RETRY_ON_CAPTURE_FAILURE)
                    if (System.currentTimeMillis() > deadline) return
                    continue
                }
                val current = frame ?: return

                val prev = previous
                if (prev != null && prev.rows() == current.rows() && prev.cols() == current.cols()) {
                    val diff = runCatching { Core.norm(current, prev, Core.NORM_INF) }
                        .getOrDefault(Double.MAX_VALUE)
                    if (diff <= FREEZE_DIFF_THRESHOLD) {
                        if (stableSince == 0L) stableSince = System.currentTimeMillis()
                        if (System.currentTimeMillis() - stableSince >= freezeMs) return
                    } else {
                        // 又动了，重新计时
                        stableSince = 0L
                    }
                }

                // 必须克隆：frame 归 graph 所有，下一次 refreshFrame 会 release 它。
                prev?.release()
                previous = current.clone()

                if (System.currentTimeMillis() > deadline) return
                delay(FREEZE_POLL_MS)
            }
        } finally {
            previous?.release()
        }
    }

    /** 最近一次识别结果，供动作解析点击目标。 */
    private var lastHit: Hit? = null

    /** 执行节点动作。@return false 表示动作失败，调用方应转 on_error。 */
    private suspend fun execute(node: NodeDef, parent: NodeDef): Boolean {
        val hit = lastHit
        val adapter = recognizer.adapter

        if (node.preDelay > 0) delay(node.preDelay)

        node.focus?.let { BotState.setStatus(it) }

        val ok = when (val action = node.action) {
            is Action.DoNothing -> true

            is Action.Click -> {
                if (hit == null || adapter == null) {
                    false
                } else {
                    val (lx, ly) = if (action.target != null) {
                        Targets.resolve(hit, action.target)
                    } else {
                        Targets.applyOffset(hit, action.targetOffset)
                    }
                    // 逻辑坐标 -> 物理像素，再叠加防检测抖动。
                    val p = adapter.toScreen(lx.toFloat(), ly.toFloat())
                    val px = p.x.roundToInt() + TapJitter.dx()
                    val py = p.y.roundToInt() + TapJitter.dy()
                    if (dryRun) {
                        Log.i(TAG, "[干跑] 点击 [${node.name}] " +
                            "命中分数=%.4f 逻辑(${lx.roundToInt()},${ly.roundToInt()}) " +
                            "-> 本该点屏幕($px,$py)（未执行）".format(hit.score))
                        BotState.setStatus("[干跑] ${node.name} %.2f".format(hit.score))
                    } else {
                        Log.i(TAG, "点击 [${node.name}] 逻辑(${lx.roundToInt()},${ly.roundToInt()}) -> 屏幕($px,$py)")
                        injector.tap(px.toFloat(), py.toFloat())
                    }
                    true
                }
            }

            is Action.Swipe -> {
                if (adapter == null) {
                    false
                } else {
                    val a = action.begin
                    val b = action.end
                    val from = adapter.toScreen(a[0].toFloat(), a[1].toFloat())
                    val to = adapter.toScreen(b[0].toFloat(), b[1].toFloat())
                    if (dryRun) {
                        Log.i(TAG, "[干跑] 滑动 [${node.name}] " +
                            "(${a[0]},${a[1]}) -> (${b[0]},${b[1]}) ${action.durationMs}ms（未执行）")
                    } else {
                        Log.i(TAG, "滑动 [${node.name}] (${a[0]},${a[1]}) -> (${b[0]},${b[1]}) ${action.durationMs}ms")
                        injector.swipe(from.x, from.y, to.x, to.y, action.durationMs)
                    }
                    if (action.endHoldMs > 0) delay(action.endHoldMs)
                    true
                }
            }

            is Action.Custom -> {
                val handler = customActions[action.name]
                if (handler == null) {
                    // 自定义动作没注册就静默跳过会让流程空转，必须显式报错。
                    //
                    // 这不是理论问题：`Endless_ref` 实际用了 8 个自定义动作
                    // （JobSetLoad / BatchSwipe / SelectPlants …），而入口节点的
                    // next 第一个就是 JobSetLoad。它没有 recognition → 按 DirectHit
                    // 必然命中 → 走到这里 → 返回 false → on_error 为空 → 整个
                    // run() 返回。界面上看到的是"流程结束"，实际上第二步就断了。
                    stopReason = "缺少自定义动作「${action.name}」（节点 [${node.name}]）"
                    Log.e(TAG, stopReason!!)
                    false
                } else {
                    handler.execute(
                        CustomContext(
                            node = node,
                            param = action.param,
                            hit = hit,
                            frame = frame,
                            graph = this
                        )
                    )
                }
            }

            is Action.StopTask -> {
                // ★ 返回 true 并置结束标志，不是 false。
                //
                //   返回 false 会被上层当成"动作失败" → 走 on_error → 没有 on_error
                //   就报"动作执行失败"。而 StopTask 明明是**流程正常收尾**，
                //   报成故障会让用户以为脚本出错。
                Log.i(TAG, "节点 [${node.name}] 请求停止任务")
                stopRequested = true
                true
            }
        }

        if (ok) {
            hitCounts[node.name] = (hitCounts[node.name] ?: 0L) + 1
        }
        return ok
    }

    /** enabled + max_hit 过滤。 */
    private fun isEligible(node: NodeDef): Boolean {
        if (!node.enabled) return false
        val hits = hitCounts[node.name] ?: 0L
        // max_hit = 0 在 MaaPVZ 里被用作"无限"的哨兵值，按官方默认上限处理。
        if (node.maxHit == 0L) return true
        return hits < node.maxHit
    }

    /**
     * 过滤掉不存在或不可用的后继节点。
     *
     * 官方语义：`enabled=false` 或超过 `max_hit` 的节点若出现在 next 里，
     * 会被直接跳过（既不是错误，也不终止流程）。
     *
     * ★ 必须认识 `[JumpBack]` 前缀。
     *
     *   自动精灵用 `[JumpBack]名字` 表示一条**回边**：这个节点命中后，
     *   执行完要回到父节点重新判断，而不是往下走。典型用法是状态兜底 ——
     *   "我其实不在战斗界面，先去修正，再回来继续等"。
     *
     *   不认识前缀的话，`nodes["[JumpBack]X"]` 查不到，整条边被静默过滤掉，
     *   兜底逻辑失效且日志毫无痕迹。真实脚本里有 2 处这种边。
     *
     *   这里保留原始字符串（含前缀），因为调用方需要知道这是不是回边。
     */
    private fun resolveNext(node: NodeDef): List<String> =
        node.next.filter { raw ->
            val name = stripJumpBack(raw)
            val candidate = nodes[name]
            if (candidate == null) {
                // ★ 必须显式报出来。
                //
                //   之前这里只是 candidate != null 的过滤，断链被静默丢掉，
                //   运行时表现是"走到这个节点就正常结束了" —— 看起来像跑完，
                //   实际链在中间断了。素材里就有 3 处这种引用（来自没同步进来的
                //   脚本），不报的话永远查不到。
                Log.w(TAG, "节点 [${node.name}] 引用了不存在的 [$name]，这条边被跳过")
                false
            } else {
                isEligible(candidate)
            }
        }

    /** 去掉 `[JumpBack]` 前缀。 */
    private fun stripJumpBack(name: String): String = NodeEdges.strip(name)

    /** 当前这次命中是不是通过回边到达的。由 [waitForHit] 设置。 */
    private var lastWasJumpBack = false

    /** 抓一帧。失败返回 false，调用方稍后重试。 */
    private fun refreshFrame(): Boolean {
        releaseFrame()
        val mat = capturer.capture() ?: return false
        frame = mat
        frameOwner = true
        // 用真实帧尺寸校准缩放，旋转/分屏自动适应。
        recognizer.setScreen(mat.width(), mat.height())
        return true
    }

    private fun releaseFrame() {
        if (frameOwner) {
            runCatching { frame?.release() }
        }
        frame = null
        frameOwner = false
    }

    /** 任务结束时释放当前帧。 */
    fun release() {
        releaseFrame()
    }

    /** 当前已命中的节点次数（供调试 / 状态显示）。 */
    fun hitsOf(node: String): Long = hitCounts[node] ?: 0L

    /** 已跑过多少轮识别。 */
    private var roundCount = 0L

    /**
     * 报一轮识别给界面。
     *
     * 只在**界面需要**的粒度上报（每轮一次），不因某个节点内部细节打扰用户。
     * 写进程级单例而不是回调，是因为界面与服务是两个组件，
     * 界面可能在任何时刻才订阅上来。
     */
    private fun reportRound(parentName: String, roundStartNanos: Long, waiting: List<String>) {
        roundCount++
        val costMs = (System.nanoTime() - roundStartNanos) / 1_000_000.0
        ScriptRunState.update { prev ->
            prev.copy(
                currentNode = parentName,
                round = roundCount,
                lastRoundMs = costMs,
                message = "等待：${waiting.joinToString(" / ").take(60)}"
            )
        }
    }

    /** 报一次命中。命中次数是 [max_hit] 判据的来源，所以在这里统一记账。 */
    private fun reportHit(nodeName: String) {
        ScriptRunState.update { prev ->
            prev.copy(hits = prev.hits + (nodeName to (prev.hits[nodeName] ?: 0L) + 1))
        }
    }

    private companion object {
        const val TAG = "NodeGraph"
        const val RETRY_ON_CAPTURE_FAILURE = 100L


        /**
         * 判定"画面变了"的阈值（两帧逐像素最大绝对差）。
         *
         * 不能是 0：采集和解码本身有极小抖动，要求完全相等会永远等不到稳定。
         */
        const val FREEZE_DIFF_THRESHOLD = 2.0

        /** 等稳定时的采帧间隔。比 rate_limit 粗，因为这里是在等而不是在找。 */
        const val FREEZE_POLL_MS = 30L

        /** 等稳定的最长时长兜底 —— 画面一直动（比如游戏在放动画）时不能卡死。 */
        const val FREEZE_MAX_WAIT_MS = 3_000L
    }
}

/** 自定义动作的输入。 */
data class CustomContext(
    val node: NodeDef,
    val param: String?,
    val hit: Hit?,
    val frame: Mat?,
    val graph: NodeGraph
)

/** 自定义动作实现。对应 MaaPVZ 里 `@AgentServer.custom_action("名字")` 注册的类。 */
fun interface CustomActionHandler {
    /** @return true 表示成功；false 会触发 on_error。 */
    suspend fun execute(context: CustomContext): Boolean
}
