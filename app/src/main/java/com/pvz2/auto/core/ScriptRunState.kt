package com.pvz2.auto.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet

/**
 * 脚本运行的实时状态。
 *
 * ## 为什么要有它
 *
 * 脚本跑起来之后，用户唯一能看到的只有游戏画面 —— 而画面"没动"有两种含义：
 * 脚本卡住了，或者脚本正常地在等一个一直没出现的画面。
 * 这两种情况在画面上完全一样，但对用户的处理方式完全相反
 * （前者要去修，后者只需等）。
 *
 * 所以必须让"脚本在想什么"可见：正在等哪个节点、已经等了几轮、
 * 每轮识别花多久、每个节点命中过几次。
 *
 * ## 为什么是进程级单例
 *
 * 界面与服务是两个独立组件。界面在服务没起来时拿不到服务实例
 * （`onBind` 返回 null），所以状态必须是进程级的 ——
 * 与 [BotState]、`VirtualDisplaySession.sharedState` 同一套做法。
 */
object ScriptRunState {

    /**
     * @param running 是否正在跑
     * @param scriptName 当前脚本名（目录名），null 表示没选
     * @param entry 入口节点名
     * @param currentNode **正在等待**的父节点名。空串表示还没开始
     * @param round 已经跑了多少轮识别
     * @param lastRoundMs 上一轮识别耗时（毫秒）。这是性能优化的唯一依据
     * @param hits 各节点累计命中次数，key = 节点名
     * @param message 面向用户的一句话
     */
    data class State(
        val running: Boolean = false,
        val scriptName: String? = null,
        val entry: String = "",
        val currentNode: String = "",
        val round: Long = 0,
        val lastRoundMs: Double = 0.0,
        val hits: Map<String, Long> = emptyMap(),
        val message: String = "未运行",
        /** 当前正在执行的动作下标（zjs）。-1 = 没在跑。 */
        val currentIndex: Int = -1,
        /** 当前动作的名字，界面直接显示。 */
        val currentLabel: String = "",
        /** 当前动作开始的时间（`SystemClock.uptimeMillis`）。 */
        val actionStartedAt: Long = 0L,
        /** 这个动作预计要花多久（毫秒）。0 = 估计不出来（例如候选等待）。 */
        val expectedMs: Int = 0,
        /** 每个动作已经执行过几次。key = 动作下标。 */
        val counts: Map<Int, Long> = emptyMap(),
        /**
         * 当前动作的识别重试次数。
         *
         * 参考管线 的 timeout + rate_limit 意味着一条识别可能要跑几百次
         * （实测「仿原葵1」重试了 392 次）。这个数字必须能在界面上看到 ——
         * 否则用户只看到"卡住"，分不清是没在跑还是在拼命识别。
         */
        val retryCount: Int = 0,
        /** 最近一次重试失败的具体原因（实测到的色值/文字）。 */
        val retryReason: String = "",
        /** 失败在第几条（0 基）。-1 = 没失败。界面用它把那条标红。 */
        val failedIndex: Int = -1
    ) {
        /**
         * 当前动作的完成度 0~1，界面拿它画进度条。
         *
         * 估计不出来（等待候选这类，时长取决于画面什么时候出现）就返回 -1，
         * 界面改画不确定进度——总比画一条永远停在 0 的条子强。
         */
        fun actionProgress(now: Long = android.os.SystemClock.uptimeMillis()): Float {
            if (actionStartedAt <= 0L || expectedMs <= 0) return -1f
            return ((now - actionStartedAt).toFloat() / expectedMs).coerceIn(0f, 1f)
        }
        /** 最近命中的节点（命中最多的那个），给界面显示一行摘要用。 */
        val hottest: Pair<String, Long>?
            get() = hits.maxByOrNull { it.value }?.toPair()
    }

    private val shared = MutableStateFlow(State())

    /** 界面订阅这个。 */
    val state: StateFlow<State> = shared.asStateFlow()

    /** 服务侧更新。 */
    /**
     * 「暂停」动作的闸门。true 时执行器停在这一条上等，直到用户点「继续」。
     *
     * 为什么不走 Service intent：暂停是**执行线程自己**的事 ——
     * 它已经在跑循环里了，用一个进程级标志最快，也不用担心
     * "暂停命令到达时脚本刚好结束"这类时序问题。
     */
    @Volatile
    var paused: Boolean = false
        private set

    /** 暂停并提示用户怎么恢复。 */
    fun requestPause() {
        paused = true
        update { it.copy(message = "已暂停：点「继续」恢复") }
    }

    /** 用户点了「继续」。 */
    fun requestResume() {
        paused = false
        update { it.copy(message = "继续运行") }
    }

    fun update(block: (State) -> State) {
        shared.updateAndGet(block)
    }

    /** 开始一次运行。 */
    /** 开始执行第 [index] 个动作。[expectedMs] 用来画进度条，估不出来传 0。 */
    fun enterAction(index: Int, label: String, expectedMs: Int) {
        update {
            it.copy(
                currentIndex = index,
                currentLabel = label,
                actionStartedAt = android.os.SystemClock.uptimeMillis(),
                expectedMs = expectedMs,
                retryCount = 0,
                retryReason = "",
                message = label
            )
        }
    }

    /** 任务失败：记下失败的那一条，界面标红并用它做顶部提示。 */
    fun fail(index: Int, message: String) {
        update { it.copy(running = false, failedIndex = index, message = message) }
    }

    /** 记录一次识别重试。界面据此显示"识别了多少次、为什么没中"。 */
    fun retry(count: Int, reason: String) {
        update { it.copy(retryCount = count, retryReason = reason) }
    }

    /** 第 [index] 个动作执行完一次（跳转、跳过、成功都算）。 */
    fun finishAction(index: Int) {
        update {
            it.copy(counts = it.counts + (index to (it.counts[index] ?: 0L) + 1L))
        }
    }

    fun begin(scriptName: String, entry: String) {
        /*
         * 开跑先清暂停闸门。
         *
         * 上一轮如果是在「暂停」动作上被停止的，`paused` 仍然是 true；
         * 而 run() 里的清空在 actions.isEmpty() 之后才执行，中间这段
         * 会让「继续」按钮闪一下（running=true 且 paused=true）。
         */
        paused = false
        shared.updateAndGet {
            State(
                running = true,
                scriptName = scriptName,
                entry = entry,
                message = "已开始"
            )
        }
    }

    /**
     * 结束（正常或被停止）。
     *
     * 用 `updateAndGet` 而不是直接赋值：脚本线程可能正在 [update]（报轮次），
     * 直接赋值会把那次更新整块覆盖掉 —— 表现为界面短暂显示旧的轮次数字。
     * 所有写入都走同一个原子操作就不会有这种交错。
     */
    fun finish(message: String) {
        shared.updateAndGet { it.copy(running = false, message = message) }
    }

    /** 清空回未运行态。 */
    fun reset() {
        paused = false
        shared.updateAndGet { State() }
    }
}
