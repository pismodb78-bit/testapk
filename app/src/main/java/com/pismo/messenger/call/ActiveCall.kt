package com.pismo.messenger.call

import com.pismo.messenger.data.repo.CallRepository
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

    /**
     * Звонок уже поднимается, но ещё не поднялся.
     *
     * Между нажатием и появлением движка проходит несколько запросов к
     * удалённой базе, и на плохой сети это секунды. Всё это время
     * ActiveCall пуст, поэтому второе нажатие выглядело как «звонка нет,
     * начинаем новый»: заводилась вторая сессия в базе, собеседнику уходило
     * второе приглашение, и на телефоне поднимался ВТОРОЙ движок — со своим
     * захватом микрофона.
     */
    @Volatile
    private var starting = false

    /** Идёт разговор или он прямо сейчас поднимается. */
    val isBusy: Boolean get() = starting || _current.value != null

    /**
     * Взять право начать звонок. false — начинать не надо: либо разговор уже
     * идёт, либо его поднимает кто-то другой.
     */
    @Synchronized
    fun claimStart(): Boolean {
        if (isBusy) return false
        starting = true
        return true
    }

    /** Начать не удалось — право отпускаем, иначе звонки больше не пойдут. */
    @Synchronized
    fun releaseStart() {
        starting = false
    }

    /** Флаги для дока — обновляются экраном звонка. */
    private val _micMuted = MutableStateFlow(false)
    val micMuted: StateFlow<Boolean> = _micMuted.asStateFlow()

    private val _deafened = MutableStateFlow(false)
    val deafened: StateFlow<Boolean> = _deafened.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    fun start(info: Info, engine: CallEngine) {
        _current.value = info
        this.engine = engine
        starting = false

        // Состояние для дока и heartbeat голосового канала крутятся ЗДЕСЬ, а
        // не в активити: со свёрнутым окном они обязаны продолжаться, иначе
        // остальные участники увидят, что вы вышли из канала.
        loops?.cancel()
        loops = scope.launch {
            // Флаги зеркалим подпиской, а не опросом раз в секунду: кнопки
            // микрофона и «наушников» есть и в самом доке, и значок обязан
            // перекрашиваться в тот же момент, когда по нему нажали, а не
            // когда-нибудь в течение секунды.
            launch { engine.micMuted.collect { _micMuted.value = it } }
            launch { engine.deafened.collect { _deafened.value = it } }
            launch {
                engine.state.collect { _connected.value = it == CallEngine.State.CONNECTED }
            }

            // А вот heartbeat голосового канала — именно опрос: он пишет в
            // базу, и чаще раза в 10 секунд там делать нечего.
            while (isActive) {
                if (info.channelId > 0) {
                    runCatching {
                        PresenceRepository.voiceHeartbeat(
                            info.channelId,
                            streaming = engine.screenSharing.value || engine.cameraOn.value,
                            micMuted = engine.micMuted.value,
                            deafened = engine.deafened.value,
                        )
                    }
                }
                delay(10_000)
            }
        }
    }

    fun updateState(micMuted: Boolean, connected: Boolean) {
        _micMuted.value = micMuted
        _connected.value = connected
    }

    /**
     * Положить трубку — единственная точка завершения разговора, общая для
     * окна звонка и для дока.
     *
     * Порядок важен: сначала отметиться в базе (выйти из голосового канала,
     * закрыть сессию звонка), и только потом рвать комнату. Наоборот —
     * и собеседник ещё какое-то время видит вас в канале, откуда вы уже
     * ушли.
     */
    fun hangUp() {
        val info = _current.value ?: return
        val e = engine

        // Heartbeat глушим ПЕРВЫМ делом, до удаления присутствия.
        //
        // Он пишет INSERT ... ON DUPLICATE KEY UPDATE last_seen = NOW(), то
        // есть возвращает строку обратно. Удаление уходит в базу и ждёт
        // ответа, а тик heartbeat в это время спокойно успевает выстрелить —
        // и человек, который только что вышел из голосового канала,
        // продолжал висеть в нём аватаркой ещё двадцать секунд, пока запись
        // не протухнет.
        loops?.cancel()
        loops = null

        scope.launch {
            runCatching {
                if (info.channelId > 0) PresenceRepository.voiceLeave(info.channelId)
                if (info.sessionId > 0) CallRepository.leave(info.sessionId)
            }
            e?.leave()
            clear()
            // После clear(), а не до: маршрут звука выбирается по тому, идёт
            // ли разговор, и «положили трубку» должно уйти уже обычным
            // трактом, а не в голосовой, которого больше нет.
            com.pismo.messenger.media.Sounds.hangup()
        }
    }

    /** Микрофон из дока — не открывая окно звонка. Порт ToggleMicGlobal. */
    fun toggleMic() {
        val e = engine ?: return
        scope.launch { runCatching { e.toggleMic() } }
    }

    /** «Наушники» из дока. Порт ToggleDeafenGlobal. */
    fun toggleDeafen() {
        val e = engine ?: return
        scope.launch { runCatching { e.toggleDeafen() } }
    }

    fun clear() {
        loops?.cancel()
        loops = null
        starting = false
        engine = null
        _current.value = null
        _micMuted.value = false
        _deafened.value = false
        _connected.value = false
    }

    val isActive: Boolean get() = _current.value != null
}
