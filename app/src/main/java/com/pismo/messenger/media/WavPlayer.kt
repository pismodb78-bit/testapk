package com.pismo.messenger.media

import android.media.AudioAttributes
import android.media.MediaPlayer
import com.pismo.messenger.PismoApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Проигрывание голосовых сообщений. Одновременно звучит только одно —
 * повторный тап по играющему сообщению останавливает его, как на ПК.
 *
 * ПОЧЕМУ ЗДЕСЬ ПОТОКИ, А НЕ ПРОСТЫЕ ПОЛЯ. Раньше состояние лежало в
 * обычном @Volatile-поле, а пузырь читал его напрямую. Compose такое
 * чтение не отслеживает, поэтому кнопка не менялась на «стоп» при запуске
 * и не возвращалась в «играть» по окончании: звук шёл, а сообщение
 * выглядело нетронутым. Слушатель для этого в классе был, но его никто
 * никогда не подключал.
 *
 * ЧЕМ ОТЛИЧАЕТСЯ ОТ ПК, сознательно. Там голосовое — это кнопка
 * «▶ Голосовое / ⏹ Остановить», и всё: ни позиции, ни перемотки. На
 * телефоне этого мало. Голосовые слушают на ходу, в них переспрашивают
 * середину, и «прослушать заново целиком, чтобы вернуться на десять
 * секунд назад» — не вариант. Поэтому наружу отдаётся позиция, а по
 * полосе можно перематывать.
 */
object WavPlayer {

    private val scope = CoroutineScope(Dispatchers.Main.immediate)

    private var player: MediaPlayer? = null
    private var ticker: Job? = null

    private val _playingId = MutableStateFlow(-1)

    /** id сообщения, которое сейчас играет; −1 — тишина. */
    val playingId: StateFlow<Int> = _playingId.asStateFlow()

    private val _positionMs = MutableStateFlow(0)
    val positionMs: StateFlow<Int> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0)
    val durationMs: StateFlow<Int> = _durationMs.asStateFlow()

    /** Для мест вне композиции, где поток не нужен. */
    val currentId: Int get() = _playingId.value

    /** Тумблер для голосового: играет это же — остановит, иначе начнёт заново. */
    fun toggle(messageId: Int, wav: ByteArray) {
        if (_playingId.value == messageId) {
            stop()
            return
        }
        runCatching {
            val file = File(PismoApp.appContext.cacheDir, "voice_play.wav")
            file.writeBytes(wav)
            toggleFile(messageId, file)
        }.onFailure { stop() }
    }

    /**
     * Тумблер для файла — музыкальные вложения ходят этим путём.
     *
     * Проигрыватель один на всё приложение, и это не экономия, а требование.
     * Раньше у каждого пузыря с музыкой был свой MediaPlayer внутри
     * композиции: два вложения играли разом поверх друг друга, а стоило
     * отлистать ленту — Compose выбрасывал пузырь вместе с проигрывателем, и
     * музыка обрывалась на середине. Здесь она переживает и прокрутку, и
     * переход в другой чат.
     */
    fun toggleFile(messageId: Int, file: File) {
        if (_playingId.value == messageId) {
            stop()
            return
        }
        stop()

        runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener { stop() }
                setOnErrorListener { _, _, _ -> stop(); true }
                prepare()
                start()
            }
            _durationMs.value = runCatching { player?.duration ?: 0 }.getOrDefault(0)
            _positionMs.value = 0
            _playingId.value = messageId
            startTicker()
        }.onFailure { stop() }
    }

    /** Перемотка внутри играющего сообщения. */
    fun seekTo(messageId: Int, ms: Int) {
        if (_playingId.value != messageId) return
        val target = ms.coerceIn(0, _durationMs.value.coerceAtLeast(0))
        runCatching { player?.seekTo(target) }
        _positionMs.value = target
    }

    fun stop() {
        ticker?.cancel()
        ticker = null
        // Каждое по отдельности: stop() у неподготовленного плеера кидает
        // исключение, и в одном общем runCatching release() уже не выполнился
        // бы — MediaPlayer утёк бы вместе с занятым им кодеком.
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        _playingId.value = -1
        _positionMs.value = 0
        _durationMs.value = 0
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive && _playingId.value >= 0) {
                _positionMs.value = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)

                // Начавшийся разговор голосовое глушит: слушать сообщение и
                // собеседника разом всё равно невозможно, а микрофон уже
                // отдан звонку.
                if (com.pismo.messenger.call.ActiveCall.engine != null) {
                    stop()
                    return@launch
                }
                delay(200)
            }
        }
    }

    /**
     * Длительность записи по её собственной шапке — до раскодирования, для
     * подписи в пузыре.
     *
     * Разбирается настоящий WAV-заголовок, а не «размер минус 44 байта на
     * нашей частоте»: голосовое, записанное на ПК, идёт с другой частотой и
     * числом каналов, и прежний расчёт врал на нём в разы.
     */
    fun durationSecondsOf(wav: ByteArray): Int {
        val pcm = WavData.parse(wav) ?: return 0
        val bytesPerSecond = pcm.sampleRate * pcm.channels * 2
        if (bytesPerSecond <= 0) return 0
        return (pcm.data.size / bytesPerSecond).coerceAtLeast(0)
    }
}
