package com.pvz2.auto.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide status shared between the automation service, the overlay panel and the
 * activity. Deliberately just two state flows - the three components only need to know
 * whether the loop runs and what it is doing.
 */
object BotState {

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status.asStateFlow()

    fun setRunning(value: Boolean) {
        _running.value = value
        if (!value) _status.value = "Stopped"
    }

    fun setStatus(value: String) {
        _status.value = value
    }
}
