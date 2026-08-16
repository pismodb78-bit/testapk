package com.pismo.messenger.call

import android.content.Context
import android.content.Intent
import android.util.Log
import com.pismo.messenger.core.Prefs
import com.pismo.messenger.core.UserSession
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Движок звонков поверх LiveKit — Android-аналог NativeCallBridge/
 * NativeCallTransport, которые на ПК работают через livekit_ffi.dll.
 *
 * Совместимость с ПК держится на трёх вещах:
 *  1) имя комнаты — id call-сессии, либо "vch_<id>" для голосового канала;
 *  2) identity участника — id пользователя строкой (по нему ПК сопоставляет плитки);
 *  3) атрибуты "mic" и "deaf" для значков мьюта.
 *
 * ВНИМАНИЕ на инверсию: в атрибуте "mic" значение "1" означает, что
 * микрофон ВКЛЮЧЁН, а "0" — что замьючен (ровно так пишет и читает ПК:
 * `micMuted ? "0" : "1"`). Перепутать местами — значки молча перестанут
 * совпадать, без единой ошибки.
 */
class CallEngine(
    private val appContext: Context,
    private val scope: CoroutineScope,
) {

    companion object {
        private const val TAG = "CallEngine"
        const val ATTR_MIC = "mic"
        const val ATTR_DEAF = "deaf"
    }

    /** Участник звонка в виде, пригодном для отрисовки плитки. */
    data class ParticipantState(
        val identity: String,
        val name: String,
        val isLocal: Boolean,
        val speaking: Boolean = false,
        val micMuted: Boolean = false,
        val deafened: Boolean = false,
        val cameraTrack: VideoTrack? = null,
        val screenTrack: VideoTrack? = null,
    ) {
        val userId: Int get() = identity.toIntOrNull() ?: -1
        val isStreaming: Boolean get() = screenTrack != null || cameraTrack != null
    }

    enum class State { IDLE, CONNECTING, CONNECTED, FAILED, DISCONNECTED }

    private var room: Room? = null
    private var eventJob: Job? = null

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _participants = MutableStateFlow<List<ParticipantState>>(emptyList())
    val participants: StateFlow<List<ParticipantState>> = _participants.asStateFlow()

    private val _micMuted = MutableStateFlow(false)
    val micMuted: StateFlow<Boolean> = _micMuted.asStateFlow()

    private val _deafened = MutableStateFlow(false)
    val deafened: StateFlow<Boolean> = _deafened.asStateFlow()

    private val _cameraOn = MutableStateFlow(false)
    val cameraOn: StateFlow<Boolean> = _cameraOn.asStateFlow()

    private val _screenSharing = MutableStateFlow(false)
    val screenSharing: StateFlow<Boolean> = _screenSharing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Имя текущей комнаты — нужно, чтобы понять, это голосовой канал или звонок. */
    var currentRoom: String = ""
        private set

    val currentRoomInstance: Room? get() = room

    // ════════════════════════════════════════════════════════════════
    //  ПОДКЛЮЧЕНИЕ
    // ════════════════════════════════════════════════════════════════

    suspend fun join(roomName: String, withVideo: Boolean) {
        if (!Prefs.liveKitConfigured) {
            _error.value = "LiveKit не настроен: укажите URL, API key и secret в настройках."
            _state.value = State.FAILED
            return
        }

        _state.value = State.CONNECTING
        currentRoom = roomName

        val identity = UserSession.effectiveId.toString()
        val display = UserSession.effectiveName.ifBlank { identity }
        val token = LiveKitToken.create(roomName, identity, display)

        try {
            val r = LiveKit.create(appContext)
            room = r
            observe(r)
            r.connect(Prefs.liveKitUrl, token)

            r.localParticipant.setMicrophoneEnabled(true)
            if (withVideo) {
                r.localParticipant.setCameraEnabled(true)
                _cameraOn.value = true
            }
            publishVoiceState()

            _state.value = State.CONNECTED
            refreshParticipants()
        } catch (e: Exception) {
            Log.e(TAG, "connect failed", e)
            _error.value = e.message ?: "не удалось подключиться к звонку"
            _state.value = State.FAILED
        }
    }

    fun leave() {
        runCatching { eventJob?.cancel() }
        runCatching { room?.disconnect() }
        // release освобождает нативные ресурсы (EglBase, аудиоустройство);
        // без него повторный звонок в той же сессии течёт памятью.
        runCatching { room?.release() }
        room = null
        eventJob = null
        currentRoom = ""
        _participants.value = emptyList()
        _cameraOn.value = false
        _screenSharing.value = false
        _micMuted.value = false
        _deafened.value = false
        _state.value = State.DISCONNECTED
    }

    // ════════════════════════════════════════════════════════════════
    //  УПРАВЛЕНИЕ
    // ════════════════════════════════════════════════════════════════

    suspend fun toggleMic() {
        val r = room ?: return
        val muted = !_micMuted.value
        _micMuted.value = muted
        runCatching { r.localParticipant.setMicrophoneEnabled(!muted) }
        publishVoiceState()
        refreshParticipants()
    }

    /** «Наушники»: глушим весь входящий звук и сообщаем об этом остальным. */
    suspend fun toggleDeafen() {
        val r = room ?: return
        val deaf = !_deafened.value
        _deafened.value = deaf

        // Глушим всех удалённых участников.
        runCatching {
            r.remoteParticipants.values.forEach { p ->
                p.audioTrackPublications.forEach { (_, track) ->
                    (track as? io.livekit.android.room.track.RemoteAudioTrack)?.let {
                        it.setVolume(if (deaf) 0.0 else 1.0)
                    }
                }
            }
        }

        // Заглушив вход, принято глушить и свой микрофон — как в Discord и на ПК.
        if (deaf && !_micMuted.value) {
            _micMuted.value = true
            runCatching { r.localParticipant.setMicrophoneEnabled(false) }
        }
        publishVoiceState()
        refreshParticipants()
    }

    suspend fun toggleCamera() {
        val r = room ?: return
        val on = !_cameraOn.value
        _cameraOn.value = on
        runCatching { r.localParticipant.setCameraEnabled(on) }
        refreshParticipants()
    }

    fun switchCamera() {
        runCatching {
            val track = room?.localParticipant?.videoTrackPublications
                ?.firstOrNull()?.second as? io.livekit.android.room.track.LocalVideoTrack
            track?.switchCamera()
        }
    }

    /**
     * Демонстрация экрана. [projectionData] — Intent из
     * MediaProjectionManager.createScreenCaptureIntent(), полученный
     * через startActivityForResult; без него Android захват не разрешит.
     */
    suspend fun startScreenShare(projectionData: Intent) {
        val r = room ?: return
        runCatching {
            r.localParticipant.setScreenShareEnabled(true, projectionData)
            _screenSharing.value = true
        }.onFailure {
            Log.e(TAG, "screen share failed", it)
            _error.value = "Не удалось начать демонстрацию: ${it.message}"
        }
        refreshParticipants()
    }

    suspend fun stopScreenShare() {
        val r = room ?: return
        runCatching { r.localParticipant.setScreenShareEnabled(false) }
        _screenSharing.value = false
        refreshParticipants()
    }

    /** Публикует значки мьюта так, как их читает ПК. */
    private suspend fun publishVoiceState() {
        val r = room ?: return
        runCatching {
            r.localParticipant.updateAttributes(
                mapOf(
                    ATTR_MIC to if (_micMuted.value) "0" else "1",
                    ATTR_DEAF to if (_deafened.value) "1" else "0",
                )
            )
        }.onFailure {
            // Молчаливый отказ здесь означает, что в токене нет
            // canUpdateOwnMetadata — значки просто не обновятся.
            Log.w(TAG, "updateAttributes отклонён: ${it.message}")
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  СОБЫТИЯ КОМНАТЫ
    // ════════════════════════════════════════════════════════════════

    /**
     * Подписка на события комнаты.
     *
     * Перечислять все интересные подклассы RoomEvent смысла нет: их
     * десятки, и любой из них может повлиять на плитки. Поэтому разбираем
     * только два состояния разрыва, а всё остальное просто пересобирает
     * список участников — это дешёвая операция над уже загруженным
     * состоянием комнаты, без обращений к сети.
     */
    private fun observe(r: Room) {
        eventJob = scope.launch {
            r.events.collect { event ->
                when (event) {
                    is RoomEvent.Disconnected -> {
                        _state.value = State.DISCONNECTED
                        _participants.value = emptyList()
                    }
                    is RoomEvent.FailedToConnect -> {
                        _state.value = State.FAILED
                        _error.value = event.error.message ?: "не удалось подключиться к звонку"
                    }
                    else -> refreshParticipants()
                }
            }
        }
    }

    private fun refreshParticipants() {
        val r = room ?: run { _participants.value = emptyList(); return }
        val speakers = runCatching { r.activeSpeakers.map { it.identity?.value }.toSet() }
            .getOrDefault(emptySet())

        val list = ArrayList<ParticipantState>()

        runCatching {
            val local = r.localParticipant
            list.add(
                ParticipantState(
                    identity = local.identity?.value ?: UserSession.effectiveId.toString(),
                    name = local.name?.ifBlank { null } ?: UserSession.effectiveName,
                    isLocal = true,
                    speaking = local.identity?.value in speakers && !_micMuted.value,
                    micMuted = _micMuted.value,
                    deafened = _deafened.value,
                    cameraTrack = firstCameraTrack(local),
                    screenTrack = firstScreenTrack(local),
                )
            )
        }

        runCatching {
            r.remoteParticipants.values.forEach { p ->
                val attrs = runCatching { p.attributes }.getOrDefault(emptyMap())
                val micMuted = attrs[ATTR_MIC] == "0"
                val deaf = attrs[ATTR_DEAF] == "1"
                list.add(
                    ParticipantState(
                        identity = p.identity?.value.orEmpty(),
                        name = p.name?.ifBlank { null } ?: p.identity?.value.orEmpty(),
                        isLocal = false,
                        speaking = p.identity?.value in speakers && !micMuted,
                        micMuted = micMuted,
                        deafened = deaf,
                        cameraTrack = firstCameraTrack(p),
                        screenTrack = firstScreenTrack(p),
                    )
                )
            }
        }

        _participants.value = list
    }

    private fun firstCameraTrack(p: Participant): VideoTrack? = runCatching {
        p.videoTrackPublications.firstOrNull { (pub, _) ->
            pub.source == io.livekit.android.room.track.Track.Source.CAMERA
        }?.second as? VideoTrack
    }.getOrNull()

    private fun firstScreenTrack(p: Participant): VideoTrack? = runCatching {
        p.videoTrackPublications.firstOrNull { (pub, _) ->
            pub.source == io.livekit.android.room.track.Track.Source.SCREEN_SHARE
        }?.second as? VideoTrack
    }.getOrNull()

    /** Привязка рендерера к треку — вызывать перед показом видео. */
    fun initRenderer(renderer: io.livekit.android.renderer.TextureViewRenderer) {
        runCatching { room?.initVideoRenderer(renderer) }
    }

    /** Есть ли среди участников кто-то, кроме меня (для «ожидание собеседника»). */
    val hasRemote: Boolean get() = _participants.value.any { !it.isLocal }

    @Suppress("unused")
    private fun remoteById(id: String): RemoteParticipant? =
        room?.remoteParticipants?.values?.firstOrNull { it.identity?.value == id }
}
