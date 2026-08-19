package com.pismo.messenger.call

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.os.Build
import android.util.Log
import com.pismo.messenger.core.Prefs
import com.pismo.messenger.media.Sounds
import com.pismo.messenger.core.PresenceReporter
import com.pismo.messenger.core.UserSession
import com.pismo.messenger.service.CallForegroundService
import com.pismo.messenger.service.Notifications
import io.livekit.android.LiveKit
import com.twilio.audioswitch.AudioDevice
import com.twilio.audioswitch.AudioDeviceChangeListener
import io.livekit.android.RoomOptions
import io.livekit.android.audio.ScreenAudioCapturer
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.participant.VideoTrackPublishDefaults
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.LocalAudioTrackOptions
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.room.track.ScreenSharePresets
import io.livekit.android.room.track.RemoteTrackPublication
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoEncoding
import io.livekit.android.room.track.VideoCaptureParameter
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.room.track.video.BitmapFrameCapturer
import io.livekit.android.room.participant.VideoTrackPublishOptions
import io.livekit.android.room.track.screencapture.ScreenCaptureParams
import livekit.org.webrtc.VideoSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

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

        /**
         * Сколько молчать потоку кадров, чтобы счесть демонстрацию
         * оборванной. Полторы секунды: на неподвижном экране кадры и так
         * идут редко, а вот полутора секунд тишины при живом захвате не
         * бывает — WebRTC шлёт повтор кадра, даже когда картинка не менялась.
         */
        private const val FRAME_SILENCE_MS = 1_500L

        /**
         * Как часто пере-подписываться на чужую демонстрацию, пока кадры не
         * пошли, и когда сдаться. Значения с ПК: 3 секунды между попытками,
         * 15 секунд всего.
         */
        private const val RESUBSCRIBE_EVERY_MS = 3_000L
        private const val RESUBSCRIBE_GIVE_UP_MS = 15_000L
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

    // ── Живучесть демонстрации ────────────────────────────────────────
    //
    // Android умеет прекращать захват экрана без нашего участия. Самый
    // частый случай: в системном диалоге выбрана ОДНА программа, а не весь
    // экран, — стоит её свернуть, и проекция останавливается. Дальше по
    // цепочке: кадры перестают идти, дорожка уходит в mute, и у зрителя на
    // ПК демонстрация сначала чернеет, а потом плитка пропадает совсем.
    //
    // Обрывать показ из-за того, что человек на секунду свернул окно,
    // неправильно. Поэтому за потоком кадров следит сторож, и если они
    // прекратились, на место мёртвой дорожки встаёт живая с чёрным кадром:
    // зритель видит чёрный экран, но плитка на месте, звук демонстрации
    // продолжает идти, и возвращение к показу не требует переподключения.
    private var screenFrameSink: VideoSink? = null
    private val lastScreenFrameAt = AtomicLong(0)
    private var screenWatchdog: Job? = null
    private var blackCapturer: BitmapFrameCapturer? = null
    private var blackTrack: LocalVideoTrack? = null
    private var blackJob: Job? = null
    private var lastScreenWidth = 720
    private var lastScreenHeight = 1280

    /** true — идут чёрные кадры вместо настоящего экрана. */
    private val _screenFrozen = MutableStateFlow(false)
    val screenFrozen: StateFlow<Boolean> = _screenFrozen.asStateFlow()

    // ── Авто-переподключение к ЧУЖОЙ демонстрации ─────────────────────
    //
    // Порт сторожа ArmWatchTimeout из CallForm_Tiles.cs. Ситуация с ПК
    // дословно: «сервер поставил трек на паузу после перезапуска демки /
    // смены кодека» — подписка есть, дорожка есть, а кадры не идут. Лечится
    // повторной подпиской: она ничего не рвёт, а просто будит сервер, и тот
    // возобновляет отправку. На ПК это делается каждые 3 секунды до 15,
    // здесь ровно так же.
    //
    // На Android это нужнее, чем кажется: наша же заглушка чёрным кадром
    // ПЕРЕПУБЛИКУЕТ дорожку демонстрации, а для зрителя это и есть
    // «перезапуск демки» — тот самый случай, под который сторож и написан.
    private class ScreenWatch(
        val track: VideoTrack,
        /** Пишется из потока WebRTC, читается из сторожа — отсюда Atomic. */
        val lastFrameAt: AtomicLong,
        val startedAt: Long,
        var gaveUp: Boolean = false,
    ) {
        lateinit var sink: VideoSink
    }

    private val screenWatches = HashMap<String, ScreenWatch>()
    private var remoteScreenWatchdog: Job? = null

    /** Кто был в комнате в прошлый раз — чтобы озвучить приход и уход. */
    private var knownPeers: Set<String> = emptySet()
    private var peersSeeded = false

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

    // ── Куда выводится звук разговора ──────────────────────────────────
    //
    // Своё перечисление, а не типы audioswitch: наружу не должен торчать
    // класс из транзитивной зависимости SDK, а списку в интерфейсе нужны
    // человеческие названия и порядок.

    enum class AudioOutput { BLUETOOTH, WIRED, SPEAKER, EARPIECE }

    private val _audioOutputs = MutableStateFlow<List<AudioOutput>>(emptyList())

    /** Что вообще доступно сейчас: гарнитура появляется и исчезает на ходу. */
    val audioOutputs: StateFlow<List<AudioOutput>> = _audioOutputs.asStateFlow()

    private val _audioOutput = MutableStateFlow<AudioOutput?>(null)
    val audioOutput: StateFlow<AudioOutput?> = _audioOutput.asStateFlow()

    // Тип библиотечный, а не свой функциональный: audioswitch волен объявить
    // слушателя и через typealias, и через fun interface, а переменной
    // собственного функционального типа второй вариант уже не принял бы.
    private var audioDeviceListener: AudioDeviceChangeListener? = null

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
                    // adaptiveStream и dynacast НАМЕРЕННО выключены.
                    // Dynacast перестаёт публиковать слои, на которые, по
                    // мнению SDK, никто не подписан, — и ПК видел у нашей
                    // демонстрации вечное «Подключение…». Раньше демка
                    // работала именно потому, что обе опции были выключены
                    // по умолчанию; я включил их вместе с настройками звука
                    // и сам же сломал показ экрана.
                    adaptiveStream = false,
                    dynacast = false,
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

                    // ── Качество демонстрации экрана ──
                    //
                    // Захват оставляем ORIGINAL: это родное разрешение
                    // экрана без масштабирования И БЕЗ ОБРЕЗКИ. Пресеты вроде
                    // H1080_FPS15 трогать нельзя — у них adaptOutputToDimensions,
                    // то есть кадр подгоняется под 16:9 обрезанием, а экран
                    // телефона вертикальный: у зрителя пропали бы верх и низ.
                    // Меняем только частоту кадров.
                    screenShareTrackCaptureDefaults = LocalVideoTrackOptions(
                        isScreencast = true,
                        captureParams = ScreenSharePresets.ORIGINAL.capture.copy(
                            maxFps = Prefs.screenShareFps,
                        ),
                    ),
                    screenShareTrackPublishDefaults = VideoTrackPublishDefaults(
                        videoEncoding = VideoEncoding(
                            maxBitrate = Prefs.screenShareBitrate,
                            maxFps = Prefs.screenShareFps,
                        ),
                        // SIMULCAST ВЫКЛЮЧЕН, и это главная правка про
                        // качество. С ним телефон кодирует экран в НЕСКОЛЬКО
                        // потоков разом: на вертикальном экране это три
                        // энкодера по паре мегапикселей каждый. Аппаратный
                        // кодировщик такого не тянет, начинает пропускать
                        // кадры и резать битрейт, и картинка расплывается.
                        // Зрителей у демки один-два, слои никому не нужны —
                        // отдаём весь битрейт одному потоку.
                        simulcast = false,
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

            // Индивидуальные громкости — до первого applyRemoteVolumes:
            // настройка должна действовать и на тех, кто был в комнате до
            // нас, иначе слишком громкого собеседника снова слышно в полную
            // силу первые секунды.
            restoreUserAudioPrefs()
            watchAudioDevices(r)

            _state.value = State.CONNECTED
            Sounds.callConnected()
            // Демонстрацию может начать кто угодно и когда угодно, а пауза
            // трека случается как раз в момент подключения к нему — поэтому
            // сторож живёт весь звонок, а не только пока смотрим.
            startRemoteScreenWatchdog()
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
        screenWatchdog?.cancel()
        screenWatchdog = null
        screenFrameSink = null
        blackJob?.cancel()
        blackJob = null
        runCatching { blackTrack?.stopCapture() }
        runCatching { blackTrack?.dispose() }
        blackTrack = null
        blackCapturer = null
        _screenFrozen.value = false
        stopRemoteScreenWatchdog()
        audio.screenMixer = null
        _screenAudioOn.value = false
        runCatching { CallForegroundService.stop(appContext) }
        IncomingCallMonitor.inCall = false
        PresenceReporter.inCall = false
        stopWatchingAudioDevices()
        _audioOutputs.value = emptyList()
        _audioOutput.value = null
        runCatching { room?.disconnect() }
        // release освобождает нативные ресурсы (EglBase, аудиоустройство);
        // без него повторный звонок в той же сессии течёт памятью.
        runCatching { room?.release() }
        room = null
        eventJob = null
        currentRoom = ""
        _participants.value = emptyList()
        knownPeers = emptySet()
        peersSeeded = false
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
        val willUnmute = _micMuted.value
        setMicMuted(r, !_micMuted.value)
        if (willUnmute) Sounds.micOn() else Sounds.micOff()

        // Включение микрофона ИЗ ПОЛНОГО МУТА снимает и «наушники» — порт
        // ToggleMute с ПК. Логика простая: человек, который снова хочет
        // говорить, хочет и слышать; иначе он говорит в пустоту и не понимает,
        // почему ему не отвечают.
        if (willUnmute && _deafened.value) {
            _deafened.value = false
            applyRemoteVolumes()
        }

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
        audio.mic = newMicProcessor()

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
        // Тот же отклик, что на ПК (SetDeafenState). Он же и ответ на
        // вопрос «а кнопка вообще работает?»: увидеть глушение входящего
        // звука нельзя, услышать — можно.
        if (_deafened.value) Sounds.micOff() else Sounds.micOn()

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
        if (on) Sounds.cameraOn() else Sounds.cameraOff()
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

        Sounds.screenOn()
        if (withAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) attachScreenAudio(r)
        watchScreenFrames(r)
        refreshParticipants()
    }

    suspend fun stopScreenShare() {
        val r = room ?: return
        stopScreenWatchdog()
        stopBlackKeepalive(r)
        detachScreenAudio(r)
        runCatching { r.localParticipant.setScreenShareEnabled(false) }
        _screenSharing.value = false
        _screenFrozen.value = false
        Sounds.screenOff()
        refreshParticipants()
    }

    // ── Живучесть демонстрации ────────────────────────────────────────

    /**
     * Сторож потока кадров.
     *
     * Узнать «проекция остановлена» напрямую нельзя: MediaProjection создаёт
     * и держит внутри себя SDK, наружу объект не отдаётся. Зато видно
     * следствие — кадры перестают приходить. Вешаем на дорожку лёгкий приёмник,
     * который только отмечает время последнего кадра, и раз в секунду
     * проверяем, не затянулась ли тишина.
     */
    private fun watchScreenFrames(r: Room) {
        stopScreenWatchdog()

        val track = r.localParticipant
            .getTrackPublication(Track.Source.SCREEN_SHARE)?.track as? VideoTrack ?: return

        lastScreenFrameAt.set(System.currentTimeMillis())
        val sink = VideoSink { frame ->
            lastScreenFrameAt.set(System.currentTimeMillis())
            // Запоминаем геометрию: чёрный кадр должен быть той же формы,
            // иначе у зрителя на месте вертикального экрана вдруг окажется
            // горизонтальный прямоугольник.
            val w = frame.rotatedWidth
            val h = frame.rotatedHeight
            if (w > 0 && h > 0) {
                lastScreenWidth = w
                lastScreenHeight = h
            }
        }
        runCatching { track.addRenderer(sink) }
        screenFrameSink = sink

        screenWatchdog = scope.launch {
            while (isActive && _screenSharing.value) {
                delay(1_000)
                if (!_screenSharing.value || blackTrack != null) continue
                val silence = System.currentTimeMillis() - lastScreenFrameAt.get()
                if (silence > FRAME_SILENCE_MS) {
                    Log.w(TAG, "кадры экрана не идут $silence мс — переходим на чёрный кадр")
                    startBlackKeepalive(r)
                }
            }
        }
    }

    /**
     * Снимает только приёмник кадров, не трогая корутину сторожа.
     *
     * Отдельный метод нужен именно потому, что подмена дорожки происходит
     * ВНУТРИ сторожа: отменить там его собственную корутину — значит
     * оборвать самого себя на первой же точке приостановки, посреди
     * публикации новой дорожки.
     */
    private fun detachScreenSink() {
        val sink = screenFrameSink ?: return
        screenFrameSink = null
        val track = room?.localParticipant
            ?.getTrackPublication(Track.Source.SCREEN_SHARE)?.track as? VideoTrack
        runCatching { track?.removeRenderer(sink) }
    }

    private fun stopScreenWatchdog() {
        screenWatchdog?.cancel()
        screenWatchdog = null
        detachScreenSink()
    }

    /**
     * Ставит на место умершей демонстрации дорожку с чёрным кадром.
     *
     * Порядок «сначала снять мёртвую, потом опубликовать живую» вынужденный:
     * источник SCREEN_SHARE у участника один, и две дорожки под ним
     * одновременно не живут. Пауза выходит меньше секунды — несравнимо с
     * тем, что было раньше, когда плитка пропадала насовсем.
     */
    private suspend fun startBlackKeepalive(r: Room) {
        if (blackTrack != null) return

        val w = (lastScreenWidth / 2) * 2
        val h = (lastScreenHeight / 2) * 2

        runCatching {
            // Только приёмник: сторож — это корутина, из которой мы сюда и
            // пришли, отменять её отсюда нельзя.
            detachScreenSink()
            r.localParticipant.setScreenShareEnabled(false)

            val capturer = BitmapFrameCapturer()
            val track = r.localParticipant.createVideoTrack(
                name = "screen_keepalive",
                capturer = capturer,
                options = LocalVideoTrackOptions(
                    isScreencast = true,
                    // adaptOutputToDimensions = false: подгонять нечего,
                    // кадр уже нужного размера, а включённая подгонка
                    // прогнала бы его через масштабирование впустую.
                    captureParams = VideoCaptureParameter(w, h, 2, adaptOutputToDimensions = false),
                ),
            )
            track.startCapture()

            blackCapturer = capturer
            blackTrack = track

            // Кадры начинаем гнать ДО публикации: дорожка, которая ни разу
            // не отдала кадр, уходит в mute сразу после подписки.
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                eraseColor(AndroidColor.BLACK)
            }
            blackJob = scope.launch {
                while (isActive) {
                    runCatching { capturer.pushBitmap(bitmap, 0) }
                    delay(500)
                }
            }

            r.localParticipant.publishVideoTrack(
                track,
                VideoTrackPublishOptions(
                    source = Track.Source.SCREEN_SHARE,
                    simulcast = false,
                    // Чёрный кадр раз в полсекунды сжимается почти в ничто,
                    // но потолок ставим явно: незачем резервировать под него
                    // полосу, нужную голосу.
                    videoEncoding = VideoEncoding(150_000, 2),
                ),
            )

            _screenFrozen.value = true
            refreshParticipants()
        }.onFailure {
            Log.e(TAG, "не удалось поставить чёрную заглушку демонстрации", it)
            _screenFrozen.value = false
        }
    }

    /**
     * Сторож чужих демонстраций — порт ArmWatchTimeout.
     *
     * Запускается на весь звонок: демонстрацию может начать кто угодно и
     * когда угодно, а пауза трека случается как раз в момент подключения к
     * нему. Держать сторож только на время просмотра, как это сделано на
     * ПК с кнопкой «Смотреть», здесь нельзя — у нас плитка подключается
     * сама, отдельного «начала просмотра» просто нет.
     */
    private fun startRemoteScreenWatchdog() {
        remoteScreenWatchdog?.cancel()
        remoteScreenWatchdog = scope.launch {
            while (isActive) {
                delay(RESUBSCRIBE_EVERY_MS)
                runCatching { pollRemoteScreens() }
            }
        }
    }

    private fun pollRemoteScreens() {
        val r = room ?: return
        val now = System.currentTimeMillis()
        val alive = HashSet<String>()

        r.remoteParticipants.values.forEach { p ->
            val identity = p.identity?.value.orEmpty()
            if (identity.isEmpty()) return@forEach

            val pub = runCatching {
                p.getTrackPublication(Track.Source.SCREEN_SHARE) as? RemoteTrackPublication
            }.getOrNull() ?: return@forEach
            alive.add(identity)

            val track = pub.track as? VideoTrack
            val watch = screenWatches[identity]

            // Дорожки ещё не было или она сменилась — вешаем приёмник заново.
            // Смена дорожки и есть перезапуск демонстрации у показывающего.
            if (track != null && (watch == null || watch.track !== track)) {
                watch?.let { old -> runCatching { old.track.removeRenderer(old.sink) } }
                val stamp = AtomicLong(0L)
                val fresh = ScreenWatch(track = track, lastFrameAt = stamp, startedAt = now)
                fresh.sink = VideoSink { stamp.set(System.currentTimeMillis()) }
                runCatching { track.addRenderer(fresh.sink) }
                screenWatches[identity] = fresh
                // Только что подписались — дать кадрам шанс прийти.
                return@forEach
            }

            if (watch == null || track == null) return@forEach
            if (watch.gaveUp) return@forEach

            val stampValue = watch.lastFrameAt.get()
            val silentSince = if (stampValue > 0) stampValue else watch.startedAt
            val silence = now - silentSince
            if (silence < RESUBSCRIBE_EVERY_MS) return@forEach

            if (now - watch.startedAt > RESUBSCRIBE_GIVE_UP_MS && stampValue == 0L) {
                Log.w(TAG, "демонстрация $identity молчит ${silence} мс — прекращаем будить")
                watch.gaveUp = true
                return@forEach
            }

            // Пере-подписка БЕЗ отписки: ничего не рвём, просто будим сервер,
            // чтобы он возобновил поставленный на паузу трек. Ровно то же
            // делает WatchScreen на ПК.
            Log.d(TAG, "демонстрация $identity без кадров $silence мс — пере-подписка")
            runCatching { pub.setSubscribed(true) }
        }

        // Участник ушёл или закончил показ — снимаем приёмник.
        val gone = screenWatches.keys.filter { it !in alive }
        gone.forEach { id ->
            val w = screenWatches.remove(id) ?: return@forEach
            runCatching { w.track.removeRenderer(w.sink) }
        }
    }

    private fun stopRemoteScreenWatchdog() {
        remoteScreenWatchdog?.cancel()
        remoteScreenWatchdog = null
        screenWatches.values.forEach { w -> runCatching { w.track.removeRenderer(w.sink) } }
        screenWatches.clear()
    }

    private suspend fun stopBlackKeepalive(r: Room) {
        blackJob?.cancel()
        blackJob = null
        val track = blackTrack ?: return
        blackTrack = null
        blackCapturer = null
        runCatching { r.localParticipant.unpublishTrack(track) }
        runCatching { track.stopCapture() }
        runCatching { track.dispose() }
        _screenFrozen.value = false
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

    private fun outputKindOf(d: AudioDevice): AudioOutput = when (d) {
        is AudioDevice.BluetoothHeadset -> AudioOutput.BLUETOOTH
        is AudioDevice.WiredHeadset -> AudioOutput.WIRED
        is AudioDevice.Speakerphone -> AudioOutput.SPEAKER
        else -> AudioOutput.EARPIECE
    }

    /**
     * Подписка на список звуковых выходов.
     *
     * Список именно живой: наушники втыкают и вытаскивают посреди разговора,
     * bluetooth-гарнитура отваливается сама. Снимок на входе в комнату
     * устарел бы через минуту.
     */
    private fun watchAudioDevices(r: Room) {
        val h = r.audioSwitchHandler ?: return
        stopWatchingAudioDevices()

        val listener: AudioDeviceChangeListener = { devices, selected ->
            _audioOutputs.value = devices.map { outputKindOf(it) }.distinct()
            _audioOutput.value = selected?.let { outputKindOf(it) }
        }
        audioDeviceListener = listener
        h.registerAudioDeviceChangeListener(listener)
        // Первый снимок руками: слушатель срабатывает только на изменения.
        listener.invoke(h.availableAudioDevices, h.selectedAudioDevice)
    }

    private fun stopWatchingAudioDevices() {
        val h = room?.audioSwitchHandler
        audioDeviceListener?.let { h?.unregisterAudioDeviceChangeListener(it) }
        audioDeviceListener = null
    }

    /**
     * Переключить вывод: bluetooth-гарнитура, проводные наушники, динамик
     * телефона или разговорный динамик у уха.
     *
     * Выбор «липкий» — SDK сам восстановит его, если устройство пропало и
     * вернулось (наушники выдернули и воткнули обратно).
     */
    fun selectAudioOutput(out: AudioOutput) {
        val h = room?.audioSwitchHandler ?: return
        val device = h.availableAudioDevices.firstOrNull { outputKindOf(it) == out } ?: return
        h.selectDevice(device)
        _audioOutput.value = out
    }

    /**
     * Переносит сохранённые громкости и мьюты в карты звонка.
     *
     * Списком целиком, а не по одному участнику при появлении: LiveKit
     * сообщает о входе, но о тех, кто уже сидел в комнате, событий не
     * присылает — их настройки иначе не подхватились бы вовсе.
     */
    private fun restoreUserAudioPrefs() {
        UserAudioPrefs.snapshot().forEach { (id, e) ->
            val (volume, muted) = e
            voiceVolume[id] = volume
            if (muted) voiceMutedBy.add(id) else voiceMutedBy.remove(id)
        }
    }

    /** Громкость системного звука в исходящей демке, 0..2. */
    fun setScreenAudioGain(gain: Float) {
        Prefs.screenAudioGain = gain
        screenCapturer?.gain = gain
    }

    /**
     * Собирает обработчик микрофона по текущим настройкам.
     *
     * Цепочка стоит на дорожке ВСЕГДА, даже когда всё выключено. Раньше при
     * «шумодав выкл + авточувствительность + усиление 100 %» она не ставилась
     * вовсе, и вместе с ней пропадал micLevelDb — шкала порога в настройках
     * замирала ровно в том случае, ради которого её и открывают: посмотреть
     * свой уровень и выставить по нему порог. Пустая цепочка почти ничего не
     * стоит: один проход RMS по блоку, дальше ранний выход по flat.
     */
    private fun newMicProcessor(): MicProcessor? {
        return (audio.mic ?: MicProcessor(SAMPLE_RATE_HINT)).apply {
            strength = if (Prefs.noiseSuppression) Prefs.denoiseStrength else 0f
            voiceGateAuto = Prefs.voiceAutoSensitivity
            voiceThresholdDb = Prefs.voiceThresholdDb
            outputGainPercent = Prefs.voiceOutputGain
        }
    }

    /** Шумодав можно щёлкать прямо в звонке — он наш, не из WebRTC. */
    fun setNoiseSuppression(enabled: Boolean) {
        Prefs.noiseSuppression = enabled
        audio.mic = newMicProcessor()
    }

    /** Сила подавления, 0..1. Меняется прямо в разговоре. */
    fun setDenoiseStrength(value: Float) {
        Prefs.denoiseStrength = value
        audio.mic = newMicProcessor()
    }

    /**
     * Порог активации голоса — порт SetVoiceGate(bool, int).
     * [auto] = true — порога нет, звук передаётся всегда.
     */
    fun setVoiceGate(auto: Boolean, thresholdDb: Int) {
        Prefs.voiceAutoSensitivity = auto
        Prefs.voiceThresholdDb = thresholdDb
        audio.mic = newMicProcessor()
    }

    /**
     * Порог активации без записи в настройки — для живого перетаскивания
     * ползунка. Прямая правка полей уже работающей цепочки: SharedPreferences
     * на каждый пиксель хода ползунка писать нельзя, а слышать результат
     * хочется сразу, не отпуская палец.
     */
    fun previewVoiceGate(auto: Boolean, thresholdDb: Int) {
        audio.mic?.let {
            it.voiceGateAuto = auto
            it.voiceThresholdDb = thresholdDb
        }
    }

    /** Сила подавления на лету, без записи в настройки. */
    fun previewDenoiseStrength(value: Float) {
        audio.mic?.strength = if (Prefs.noiseSuppression) value else 0f
    }

    /** Makeup-усиление на лету, без записи в настройки. */
    fun previewVoiceOutputGain(percent: Int) {
        audio.mic?.outputGainPercent = percent
    }

    /**
     * Уровень микрофона в дБFS, каким его видит порог активации.
     *
     * Отдаётся шкале в настройках: сравнивать имеет смысл только с тем же
     * сигналом, по которому принимается решение, иначе цифры на экране и
     * поведение гейта живут отдельными жизнями.
     */
    val micLevelDb: Float
        get() = when {
            // На мьюте цепочка не вызывается вовсе (буфер обнуляется раньше),
            // и lastLevelDb застыл бы на последнем сказанном слове — шкала
            // показывала бы речь у выключенного микрофона.
            room == null || _micMuted.value -> -100f
            else -> audio.mic?.lastLevelDb ?: -100f
        }

    /** Makeup-усиление голоса на выходе цепи, 0..300 % — порт SetOutputGain. */
    fun setVoiceOutputGain(percent: Int) {
        Prefs.voiceOutputGain = percent
        audio.mic = newMicProcessor()
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

                    // Полная тишина по голосу закрепляется ещё и на уровне
                    // подписки. setVolume(0) идёт в libwebrtc через
                    // RemoteAudioSource → AudioRtpReceiver, и до тех пор пока
                    // у приёмника не проставлен ssrc, громкость просто теряется
                    // — «наушники» жались, а собеседника было слышно.
                    // setEnabled ничего не теряет: сервер перестаёт слать
                    // дорожку вообще. Заодно экономится трафик.
                    //
                    // Только для голоса: демку «наушники» не трогают (см. выше).
                    if (!isScreen) {
                        runCatching {
                            (pub as? RemoteTrackPublication)?.setEnabled(volume > 0.0)
                        }
                    }
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

    /** Громкость ГОЛОСА конкретного участника, 0..3. Запоминается навсегда. */
    fun setParticipantVolume(identity: String, volume: Float) {
        voiceVolume[identity] = volume
        UserAudioPrefs.setVolume(identity, volume)
        applyRemoteVolumes()
    }

    fun setParticipantMuted(identity: String, muted: Boolean) {
        if (muted) voiceMutedBy.add(identity) else voiceMutedBy.remove(identity)
        UserAudioPrefs.setMuted(identity, muted)
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

        // Кто пришёл и кто ушёл — «буп↑»/«буп↓», как на ПК. В свёрнутом
        // окне это единственный способ узнать, что собеседник отвалился.
        val ids = list.filterNot { it.isLocal }.map { it.identity }.toSet()
        if (peersSeeded) {
            if (ids.any { it !in knownPeers }) Sounds.userJoined()
            if (knownPeers.any { it !in ids }) Sounds.userLeft()
        }
        // Первый список — это те, кто УЖЕ был в комнате, когда мы вошли (в
        // голосовом канале часто пустой). Пропеть им «зашёл» значило бы
        // устроить очередь бупов на входе, поэтому его только запоминаем.
        peersSeeded = true
        knownPeers = ids

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
