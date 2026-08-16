package com.pismo.messenger.call

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Сведения о текущем звонке, доступные всему приложению.
 *
 * Сам движок живёт внутри CallActivity, но остальным экранам нужно знать,
 * что звонок идёт — чтобы показать «док» с кнопкой возврата, как это
 * делает MainForm_VoiceDock на ПК. Держим здесь только описание, без
 * ссылок на Activity, чтобы не утекал контекст.
 */
object ActiveCall {

    data class Info(
        val title: String,
        val sessionId: Int,
        val channelId: Int,
        val peerId: Int,
        val groupId: Int,
        val withVideo: Boolean,
        val startedAtMs: Long = System.currentTimeMillis(),
    ) {
        val isVoiceChannel: Boolean get() = channelId > 0
        val elapsedSeconds: Long get() = (System.currentTimeMillis() - startedAtMs) / 1000
    }

    private val _current = MutableStateFlow<Info?>(null)
    val current: StateFlow<Info?> = _current.asStateFlow()

    /** Флаги для дока — обновляются экраном звонка. */
    private val _micMuted = MutableStateFlow(false)
    val micMuted: StateFlow<Boolean> = _micMuted.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    fun start(info: Info) {
        _current.value = info
    }

    fun updateState(micMuted: Boolean, connected: Boolean) {
        _micMuted.value = micMuted
        _connected.value = connected
    }

    fun clear() {
        _current.value = null
        _micMuted.value = false
        _connected.value = false
    }

    val isActive: Boolean get() = _current.value != null
}
