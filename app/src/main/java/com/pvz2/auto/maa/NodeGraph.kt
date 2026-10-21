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

            current = hit.name
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
                for (name in next) {
                    val candidate = nodes[name] ?: continue
                    if (!isEligible(candidate)) continue

                    val hit = try {
                        recognizer.recognize(current, candidate.recognition)
                    } catch (t: Throwable) {
                        Log.w(TAG, "识别 [$name] 出错", t)
                        null
                    }
                    if (hit != null) {
                        lastHit = hit
                        reportHit(candidate.name)
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

            if (System.currentTimeMillis() > deadline) return null
        }
        return null
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
                Log.i(TAG, "节点 [${node.name}] 请求停止任务")
                false
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
     */
    private fun resolveNext(node: NodeDef): List<String> =
        node.next.filter { name ->
            val candidate = nodes[name]
            candidate != null && isEligible(candidate)
        }

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
