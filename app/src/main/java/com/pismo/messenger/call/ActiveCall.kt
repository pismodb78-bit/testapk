package com.pismo.messenger.call

import com.pismo.messenger.data.repo.PresenceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Текущий звонок, доступный всему приложению.
 *
 * Здесь же живёт и САМ ДВИЖОК. Раньше он принадлежал CallActivity вместе с
 * её lifecycleScope, и закрытие окна означало конец разговора — свернуть
 * звонок, чтобы ответить в чате или подкрутить шумодав, было нельзя в
 * принципе. На ПК окно звонка закрывается свободно, разговор продолжается,
 * а вернуться можно из дока; чтобы так же было и здесь, движок обязан
 * пережить активити.
 *
 * Ссылок на Activity по-прежнему не держим — только applicationContext
 * внутри самого движка, иначе утёк бы весь экран.
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

    /**
     * Область для всего, что должно пережить закрытие окна звонка: сам
     * движок, его подписки на события комнаты, сторожа демонстрации и
     * heartbeat голосового канала.
     */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Движок текущего разговора. null — звонка нет. */
    @Volatile
    var engine: CallEngine? = null
        private set

    private var loops: Job? = null

    /** Флаги для дока — обновляются экраном звонка. */
    private val _micMuted = MutableStateFlow(false)
    val micMuted: StateFlow<Boolean> = _micMuted.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    fun start(info: Info, engine: CallEngine) {
        _current.value = info
        this.engine = engine

        // Состояние для дока и heartbeat голосового канала крутятся ЗДЕСЬ, а
        // не в активити: со свёрнутым окном они обязаны продолжаться, иначе
        // остальные участники увидят, что вы вышли из канала.
        loops?.cancel()
        loops = scope.launch {
            var tick = 0
            while (isActive) {
                updateState(
                    micMuted = engine.micMuted.value,
                    connected = engine.state.value == CallEngine.State.CONNECTED,
                )
                if (info.channelId > 0 && tick % 10 == 0) {
                    runCatching {
                        PresenceRepository.voiceHeartbeat(
                            info.channelId,
                            streaming = engine.screenSharing.value || engine.cameraOn.value,
                            micMuted = engine.micMuted.value,
                            deafened = engine.deafened.value,
                        )
                    }
                }
                tick++
                delay(1000)
            }
        }
    }

    fun updateState(micMuted: Boolean, connected: Boolean) {
        _micMuted.value = micMuted
        _connected.value = connected
    }

    fun clear() {
        loops?.cancel()
        loops = null
        engine = null
        _current.value = null
        _micMuted.value = false
        _connected.value = false
    }

    val isActive: Boolean get() = _current.value != null
}
