package com.pvz2.auto.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 进程内共享的运行状态：前台服务、悬浮面板、主界面都读它。
 *
 * 刻意只有两个流 —— 这三方只需要知道「在不在跑」和「正在干什么」。
 *
 * 文案是**中文常量**而不是字符串资源：这个对象是单例、没有 Context，
 * 拿不到 Android 资源。默认值只有"空闲/已停止"两个词，为此引入 Context
 * 或做初始化注入都不划算。业务文案由调用方用 getString 传进来。
 */
object BotState {

    /** 空闲：还没启动过。 */
    const val STATUS_IDLE = "空闲"

    /** 已停止：启动过又被停掉。 */
    const val STATUS_STOPPED = "已停止"

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _status = MutableStateFlow(STATUS_IDLE)
    val status: StateFlow<String> = _status.asStateFlow()

    fun setRunning(value: Boolean) {
        _running.value = value
        if (!value) _status.value = STATUS_STOPPED
    }

    fun setStatus(value: String) {
        _status.value = value
    }
}
