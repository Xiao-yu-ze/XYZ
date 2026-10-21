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
        val message: String = "未运行"
    ) {
        /** 最近命中的节点（命中最多的那个），给界面显示一行摘要用。 */
        val hottest: Pair<String, Long>?
            get() = hits.maxByOrNull { it.value }?.toPair()
    }

    private val shared = MutableStateFlow(State())

    /** 界面订阅这个。 */
    val state: StateFlow<State> = shared.asStateFlow()

    /** 服务侧更新。 */
    fun update(block: (State) -> State) {
        shared.updateAndGet(block)
    }

    /** 开始一次运行。 */
    fun begin(scriptName: String, entry: String) {
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
        shared.updateAndGet { State() }
    }
}
