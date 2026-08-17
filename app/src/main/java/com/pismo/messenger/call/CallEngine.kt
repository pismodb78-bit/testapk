package com.pismo.messenger.call

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.pismo.messenger.core.Prefs
import com.pismo.messenger.core.PresenceReporter
import com.pismo.messenger.core.UserSession
import com.pismo.messenger.service.CallForegroundService
import com.pismo.messenger.service.Notifications
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.audio.ScreenAudioCapturer
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.LocalAudioTrackOptions
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.room.track.RemoteTrackPublication
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.room.track.screencapture.ScreenCaptureParams
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

        /**
         * Частота, под которую настраиваются коэффициенты шумодава.
         * WebRTC на Android почти всегда захватывает в 48 кГц; ошибка в
         * пару килогерц сдвинула бы срез фильтра, но не сломала обработку.
         */
        private const val SAMPLE_RATE_HINT = 48000
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

    /** Идёт ли захват системного звука вместе с демонстрацией. */
    private val _screenAudioOn = MutableStateFlow(false)
    val screenAudioOn: StateFlow<Boolean> = _screenAudioOn.asStateFlow()

    private val _frontCamera = MutableStateFlow(Prefs.frontCamera)
    val frontCamera: StateFlow<Boolean> = _frontCamera.asStateFlow()

    /**
     * Обработка исходящего звука: шумодав, мьют микрофона и подмешивание
     * звука демонстрации. Один объект на весь звонок — слот
     * setAudioBufferCallback у дорожки ровно один.
     */
    private val audio = AudioPipeline()

    /** Захватчик системного звука. Не null — значит демка идёт со звуком. */
    private var screenCapturer: ScreenAudioCapturer? = null

    // Громкости входящего звука — модель один в один с NativeCallTransport.
    // Голос и звук демки живут по РАЗНЫМ правилам, и путать их нельзя:
    // «наушники» (deafen) глушат голос, но НЕ демку — стрим должно быть
    // слышно даже с выключенным звуком, ровно как на ПК.
    private val voiceVolume = HashMap<String, Float>()
    private val voiceMutedBy = HashSet<String>()
    private val demoVolume = HashMap<String, Float>()
    private val demoMutedBy = HashSet<String>()
    private val watchedDemo = HashSet<String>()
    private var globalVoiceVolume = 1f
    private var globalDemoVolume = 1f

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

        // Без foreground-сервиса Android 14 убивает микрофон, как только
        // приложение уходит с экрана: собеседник перестаёт тебя слышать
        // ровно в момент сворачивания, без всякой ошибки.
        IncomingCallMonitor.inCall = true
        // Разговор — это активность, даже если экран телефона погас или
        // приложение свёрнуто. Без этого ПК через 90 секунд показывал
        // «бездействует» собеседнику, с которым в этот момент говорил.
        PresenceReporter.inCall = true
        runCatching { CallForegroundService.start(appContext, "Звонок PISMO") }

        val identity = UserSession.effectiveId.toString()
        val display = UserSession.effectiveName.ifBlank { identity }
        val token = LiveKitToken.create(roomName, identity, display)

        try {
            // Обработка звука задаётся ТОЛЬКО здесь: WebRTC собирает цепочку
            // AEC/NS/AGC при создании комнаты и на лету её не пересобирает.
            val r = LiveKit.create(
                appContext,
                options = RoomOptions(
                    adaptiveStream = true,
                    dynacast = true,
                    audioTrackCaptureDefaults = LocalAudioTrackOptions(
                        noiseSuppression = Prefs.noiseSuppression,
                        echoCancellation = Prefs.echoCancellation,
                        autoGainControl = Prefs.autoGainControl,
                        highPassFilter = true,
                        typingNoiseDetection = true,
                    ),
                    videoTrackCaptureDefaults = LocalVideoTrackOptions(
                        position = if (Prefs.frontCamera) CameraPosition.FRONT
                        else CameraPosition.BACK,
                    ),
                ),
            )
            room = r
            observe(r)
            r.connect(Prefs.liveKitUrl, token)

            r.localParticipant.setMicrophoneEnabled(true)
            installAudioPipeline(r)
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
        // Захват системного звука SDK не закрывает сам — забыть про release
        // означает, что AudioRecord продолжит писать звук после конца звонка.
        runCatching { screenCapturer?.releaseAudioResources() }
        screenCapturer = null
        audio.screenMixer = null
        _screenAudioOn.value = false
        runCatching { CallForegroundService.stop(appContext) }
        IncomingCallMonitor.inCall = false
        PresenceReporter.inCall = false
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
        setMicMuted(r, !_micMuted.value)
        publishVoiceState()
        refreshParticipants()
    }

    /**
     * Мьют микрофона.
     *
     * Когда идёт демонстрация со звуком, дорожку глушить НЕЛЬЗЯ: системный
     * звук едет внутри неё же (на Android вторую аудиодорожку опубликовать
     * невозможно — см. AudioPipeline). Поэтому в этом режиме мьютим
     * только сам микрофон, обнуляя его сэмплы в микшере, а дорожка
     * продолжает нести звук демки.
     */
    private suspend fun setMicMuted(r: Room, muted: Boolean) {
        _micMuted.value = muted
        audio.micMuted = muted
        if (screenCapturer != null) {
            // Демка со звуком едет по этой же дорожке — глушим только
            // микрофон в конвейере, саму дорожку оставляем живой.
            runCatching { r.localParticipant.setMicrophoneEnabled(true) }
        } else {
            runCatching { r.localParticipant.setMicrophoneEnabled(!muted) }
            if (!muted) installAudioPipeline(r)
        }
    }

    /**
     * Вешает конвейер на микрофонную дорожку.
     *
     * Вызывать после КАЖДОГО включения микрофона: setMicrophoneEnabled(false)
     * снимает публикацию, и при повторном включении дорожка создаётся заново
     * — вместе с пустым слотом обработчика.
     */
    private fun installAudioPipeline(r: Room) {
        audio.denoiser = if (Prefs.noiseSuppression) {
            (audio.denoiser ?: MicDenoiser(SAMPLE_RATE_HINT)).apply {
                strength = Prefs.denoiseStrength
            }
        } else null

        runCatching {
            val mic = r.localParticipant
                .getTrackPublication(Track.Source.MICROPHONE)?.track as? LocalAudioTrack
            mic?.setAudioBufferCallback(audio)
        }
    }

    /** «Наушники»: глушим входящий ГОЛОС и сообщаем об этом остальным. */
    suspend fun toggleDeafen() {
        val r = room ?: return
        _deafened.value = !_deafened.value

        // Заглушив вход, принято глушить и свой микрофон — как в Discord и на ПК.
        if (_deafened.value && !_micMuted.value) setMicMuted(r, true)

        applyRemoteVolumes()
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

    /**
     * Переключение фронтальная / основная камера.
     *
     * Раньше здесь брался «первый попавшийся» видеотрек — им могла
     * оказаться дорожка демонстрации экрана, у которой камеры нет вовсе,
     * и кнопка просто ничего не делала. Теперь берём именно CAMERA и
     * задаём позицию явно, а не «переключи на другую».
     */
    fun switchCamera() {
        val front = !_frontCamera.value
        _frontCamera.value = front
        Prefs.frontCamera = front
        runCatching {
            val track = room?.localParticipant?.videoTrackPublications
                ?.firstOrNull { (pub, _) -> pub.source == Track.Source.CAMERA }
                ?.second as? LocalVideoTrack
            track?.switchCamera(position = if (front) CameraPosition.FRONT else CameraPosition.BACK)
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  ДЕМОНСТРАЦИЯ ЭКРАНА
    // ════════════════════════════════════════════════════════════════

    /**
     * Демонстрация экрана. [projectionData] — Intent из
     * MediaProjectionManager.createScreenCaptureIntent(), полученный
     * через startActivityForResult; без него Android захват не разрешит.
     *
     * [withAudio] — передавать ли системный звук. Второго диалога разрешения
     * при этом НЕ будет: ScreenAudioCapturer берёт MediaProjection прямо у
     * созданного трека демонстрации.
     */
    suspend fun startScreenShare(projectionData: Intent, withAudio: Boolean = Prefs.shareScreenAudio) {
        val r = room ?: return
        runCatching {
            r.localParticipant.setScreenShareEnabled(
                true,
                ScreenCaptureParams(
                    mediaProjectionPermissionResultData = projectionData,
                    notificationId = Notifications.ID_SCREEN,
                    // Своё уведомление, а не дефолтное из SDK: у того нет
                    // иконки, и startForeground падает на старте демки.
                    notification = Notifications.screenShareNotification(appContext),
                ),
            )
            _screenSharing.value = true
        }.onFailure {
            Log.e(TAG, "screen share failed", it)
            _error.value = "Не удалось начать демонстрацию: ${it.message}"
            return
        }

        if (withAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) attachScreenAudio(r)
        refreshParticipants()
    }

    suspend fun stopScreenShare() {
        val r = room ?: return
        detachScreenAudio(r)
        runCatching { r.localParticipant.setScreenShareEnabled(false) }
        _screenSharing.value = false
        refreshParticipants()
    }

    /**
     * Захват системного звука Android появился только в 10 (API 29) —
     * на более старых версиях демонстрация идёт без звука, и это ограничение
     * ОС, а не приложения.
     */
    private suspend fun attachScreenAudio(r: Room) {
        runCatching {
            // Микрофонная дорожка — единственный канал, куда звук демки можно
            // положить. Если микрофон замьючен, дорожки нет вовсе, поэтому
            // включаем её и глушим микрофон программно, в микшере.
            if (r.localParticipant.getTrackPublication(Track.Source.MICROPHONE) == null) {
                r.localParticipant.setMicrophoneEnabled(true)
            }
            val micTrack = r.localParticipant
                .getTrackPublication(Track.Source.MICROPHONE)?.track as? LocalAudioTrack
                ?: return@runCatching
            val screenTrack = r.localParticipant
                .getTrackPublication(Track.Source.SCREEN_SHARE)?.track ?: return@runCatching

            val capturer = ScreenAudioCapturer.createFromScreenShareTrack(screenTrack)
                ?: return@runCatching
            capturer.gain = Prefs.screenAudioGain

            audio.screenMixer = capturer
            audio.micMuted = _micMuted.value
            micTrack.setAudioBufferCallback(audio)
            screenCapturer = capturer
            _screenAudioOn.value = true
        }.onFailure {
            Log.w(TAG, "звук демонстрации недоступен: ${it.message}")
            _screenAudioOn.value = false
        }
    }

    private suspend fun detachScreenAudio(r: Room) {
        val capturer = screenCapturer ?: return
        screenCapturer = null
        audio.screenMixer = null
        _screenAudioOn.value = false

        // Сам конвейер с дорожки НЕ снимаем: в нём остаётся шумодав.
        // Освобождаем только захват системного звука — SDK этого не делает,
        // и AudioRecord продолжил бы писать после конца демки.
        runCatching { capturer.releaseAudioResources() }

        // Пока шла демка, дорожка была принудительно включена. Возвращаем
        // микрофон в то состояние, которое выбрал пользователь.
        if (_micMuted.value) runCatching { r.localParticipant.setMicrophoneEnabled(false) }
    }

    /** Громкость системного звука в исходящей демке, 0..2. */
    fun setScreenAudioGain(gain: Float) {
        Prefs.screenAudioGain = gain
        screenCapturer?.gain = gain
    }

    /** Шумодав можно щёлкать прямо в звонке — он наш, не из WebRTC. */
    fun setNoiseSuppression(enabled: Boolean) {
        Prefs.noiseSuppression = enabled
        audio.denoiser = if (enabled) {
            (audio.denoiser ?: MicDenoiser(SAMPLE_RATE_HINT)).apply {
                strength = Prefs.denoiseStrength
            }
        } else null
    }

    /** Сила подавления, 0..1. Меняется прямо в разговоре. */
    fun setDenoiseStrength(value: Float) {
        Prefs.denoiseStrength = value
        audio.denoiser?.strength = value
    }

    // ════════════════════════════════════════════════════════════════
    //  ГРОМКОСТЬ ВХОДЯЩЕГО ЗВУКА (порт NativeCallTransport)
    // ════════════════════════════════════════════════════════════════

    /**
     * Раскладывает громкости по дорожкам.
     *
     * Голос и звук демки разведены намеренно, как на ПК:
     *  • голос — глушится «наушниками», множится на общий ползунок голоса;
     *  • демка — «наушниками» НЕ глушится, но слышна только если её смотрят.
     * Перепутать эти два правила — и стрим будет либо всегда молчать, либо
     * орать из всех вкладок сразу.
     */
    private fun applyRemoteVolumes() {
        val r = room ?: return
        runCatching {
            r.remoteParticipants.values.forEach { p ->
                val id = p.identity?.value.orEmpty()
                p.audioTrackPublications.forEach { (pub, track) ->
                    // remoteAudio, а не audio: поле audio — это наш конвейер
                    // обработки исходящего звука, и затенять его локальной
                    // переменной здесь слишком легко перепутать.
                    val remoteAudio = track as? RemoteAudioTrack ?: return@forEach
                    val isScreen = pub.source == Track.Source.SCREEN_SHARE_AUDIO
                    val volume = if (isScreen) {
                        if (!watchedDemo.contains(id) || demoMutedBy.contains(id)) 0.0
                        else ((demoVolume[id] ?: 1f) * globalDemoVolume)
                            .coerceIn(0f, 4f).toDouble()
                    } else {
                        if (_deafened.value || voiceMutedBy.contains(id)) 0.0
                        else ((voiceVolume[id] ?: 1f) * globalVoiceVolume)
                            .coerceIn(0f, 4f).toDouble()
                    }
                    runCatching { remoteAudio.setVolume(volume) }
                }
            }
        }
    }

    /** Смотрим/не смотрим демку участника — порт SetScreenAudioWatched. */
    fun setScreenAudioWatched(identity: String, watched: Boolean) {
        if (watched) watchedDemo.add(identity) else watchedDemo.remove(identity)

        // Дополнительно (пере)подписываемся на видеотрек демки: при adaptive
        // stream сервер ставит его на паузу, если никто не смотрит, и после
        // возобновления кадры не идут, пока подписку не тронешь. Тот же
        // приём в SetScreenSubscribed на ПК.
        runCatching {
            room?.remoteParticipants?.values
                ?.firstOrNull { it.identity?.value == identity }
                ?.videoTrackPublications
                ?.firstOrNull { (pub, _) -> pub.source == Track.Source.SCREEN_SHARE }
                ?.first
                ?.let { (it as? RemoteTrackPublication)?.setSubscribed(watched) }
        }
        applyRemoteVolumes()
    }

    /** Громкость ГОЛОСА конкретного участника, 0..3. */
    fun setParticipantVolume(identity: String, volume: Float) {
        voiceVolume[identity] = volume
        applyRemoteVolumes()
    }

    fun setParticipantMuted(identity: String, muted: Boolean) {
        if (muted) voiceMutedBy.add(identity) else voiceMutedBy.remove(identity)
        applyRemoteVolumes()
    }

    /** Громкость ДЕМКИ конкретного участника, 0..3. */
    fun setScreenShareVolume(identity: String, volume: Float) {
        demoVolume[identity] = volume
        applyRemoteVolumes()
    }

    fun volumeOf(identity: String): Float = voiceVolume[identity] ?: 1f
    fun demoVolumeOf(identity: String): Float = demoVolume[identity] ?: 1f
    fun isMutedFor(identity: String): Boolean = voiceMutedBy.contains(identity)

    /** Общая громкость всех демок (ползунок «Громкость демонстрации»). */
    fun setScreenAudioVolumeAll(volume: Float) {
        globalDemoVolume = volume
        applyRemoteVolumes()
    }

    /** Общая громкость голоса. */
    fun setPlaybackVolume(volume: Float) {
        globalVoiceVolume = volume
        applyRemoteVolumes()
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

        // Новая дорожка приходит с громкостью 1.0 — если её не привести к
        // нашим правилам сразу, чужая демка заорёт до того, как её начали
        // смотреть, а заглушённый участник снова станет слышен.
        applyRemoteVolumes()
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
