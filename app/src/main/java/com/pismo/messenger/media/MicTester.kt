package com.pismo.messenger.media

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.pismo.messenger.PismoApp
import com.pismo.messenger.call.MicProcessor
import com.pismo.messenger.core.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

/**
 * Проверка микрофона на ТОЙ ЖЕ цепочке, что и звонок — порт MicTestForm.cs.
 *
 * Зачем это, когда есть шкала уровня. Шкала отвечает на вопрос «громко ли», а
 * главный вопрос другой: «как это звучит у собеседника». Шумодав, порог
 * активации и усиление слышны, а не видны: по полоске нельзя понять, что
 * подавление съедает окончания слов или что порог режет начало фразы.
 *
 * ДВА РЕЖИМА, И ЭТО ГЛАВНОЕ ОТЛИЧИЕ ОТ ПК. Там проверка всегда сквозная:
 * говоришь — сразу слышишь себя. На телефоне так можно только в наушниках:
 * динамик рядом с микрофоном, и сквозной возврат заводится в свист за
 * полсекунды. Раньше вывод в любом случае шёл голосовым трактом, и без
 * наушников звук попадал в РАЗГОВОРНЫЙ динамик — тот, что у уха. Кнопку
 * нажимали, говорили в телефон, лежащий на столе, и не слышали ничего:
 * «кнопка не работает». Теперь режим выбирается сам:
 *
 *   * наушники подключены → сквозная проверка, как на ПК: говорите и сразу
 *     слышите себя, ползунки крутятся прямо во время неё;
 *   * наушников нет → записываем несколько секунд и тут же проигрываем их
 *     ВСЛУХ, обычным динамиком на медиа-громкости. Завестись нечему: пока
 *     идёт запись, ничего не играет, а пока играет — микрофон уже закрыт.
 *
 * ПОБОЧНАЯ ПОЛЬЗА, ради которой это стоило сделать даже отдельно от звука:
 * пока идёт запись, шкала порога получает НАСТОЯЩИЙ уровень — тот самый, с
 * которым сравнивается порог, — а не оценку по сырому микрофону. Вне звонка
 * это единственный способ выставить порог точно.
 */
object MicTester {

    /** 48 кГц — как в звонке: лимитер считает упреждение от частоты. */
    private const val SAMPLE_RATE = 48000

    /** Сколько писать в режиме «записать и прослушать». */
    const val RECORD_SECONDS = 6

    enum class Phase {
        /** Ничего не происходит. */
        IDLE,

        /** Сквозная проверка в наушниках: говорите — слышите себя. */
        LOOPBACK,

        /** Идёт запись; по её окончании начнётся проигрывание. */
        RECORDING,

        /** Проигрываем записанное вслух. */
        PLAYING,
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile
    private var job: Job? = null

    @Volatile
    private var processor: MicProcessor? = null

    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** Сколько секунд записи осталось — для отсчёта на кнопке. */
    private val _secondsLeft = MutableStateFlow(0)
    val secondsLeft: StateFlow<Int> = _secondsLeft.asStateFlow()

    val isRunning: Boolean get() = job != null

    /**
     * Уровень последнего блока в дБFS — ТОТ ЖЕ, с которым сравнивается порог
     * активации. −100, когда микрофон не пишется.
     */
    val levelDb: Float get() = processor?.lastLevelDb ?: -100f

    /**
     * Есть ли куда вернуть звук, не заводя свист: провод, USB или Bluetooth.
     *
     * Спрашиваем каждый раз, а не запоминаем: наушники втыкают ровно тогда,
     * когда собрались проверять микрофон.
     */
    fun headphonesConnected(): Boolean = runCatching {
        val am = PismoApp.appContext.getSystemService(AudioManager::class.java)
            ?: return@runCatching false
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            when (it.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                -> true

                else -> false
            }
        }
    }.getOrDefault(false)

    /**
     * Номер текущей проверки. Задание, завершившись само (микрофон отобрали,
     * запись доиграла), должно убрать за собой — но только если за это время
     * не запустили СЛЕДУЮЩУЮ проверку, иначе оно погасило бы чужую.
     */
    @Volatile
    private var token = 0

    @Synchronized
    fun start() {
        if (job != null) return
        // В разговоре микрофон занят звонком, и второй захват либо не
        // откроется, либо отберёт его у собеседников. Да и проверять нечего:
        // в звонке и так слышно, как ты звучишь.
        if (com.pismo.messenger.call.ActiveCall.engine != null) return

        val my = ++token
        job = if (headphonesConnected()) scope.launch { loopback(my) }
        else scope.launch { recordThenPlay(my) }
    }

    @Synchronized
    fun stop() {
        token++
        job?.cancel()
        job = null
        processor = null
        _phase.value = Phase.IDLE
        _secondsLeft.value = 0
    }

    @Synchronized
    private fun done(my: Int) {
        if (my != token) return
        job = null
        processor = null
        _phase.value = Phase.IDLE
        _secondsLeft.value = 0
    }

    /** Цепочка ровно та же, что вешается на дорожку в звонке. */
    private fun newProcessor() = MicProcessor(SAMPLE_RATE).apply {
        strength = if (Prefs.noiseSuppression) Prefs.denoiseStrength else 0f
        voiceGateAuto = Prefs.voiceAutoSensitivity
        voiceThresholdDb = Prefs.voiceThresholdDb
        outputGainPercent = Prefs.voiceOutputGain
        inputGain = Prefs.micGain
    }

    /** Настройки крутят прямо во время проверки — в этом весь смысл. */
    private fun refresh(mic: MicProcessor) {
        mic.strength = if (Prefs.noiseSuppression) Prefs.denoiseStrength else 0f
        mic.voiceGateAuto = Prefs.voiceAutoSensitivity
        mic.voiceThresholdDb = Prefs.voiceThresholdDb
        mic.outputGainPercent = Prefs.voiceOutputGain
        mic.inputGain = Prefs.micGain
    }

    @SuppressLint("MissingPermission")
    private fun openRecord(): AudioRecord? {
        val minIn = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val rec = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minIn * 2,
            )
        }.getOrNull() ?: return null

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return null
        }
        return rec
    }

    /**
     * [voice] = true — голосовой тракт (наушники, разговорный динамик), как в
     * звонке. false — обычный медиа-выход: громкий динамик и та громкость,
     * которой человек управляет качелькой, не заходя в разговор.
     */
    private fun openTrack(voice: Boolean): AudioTrack? {
        val minOut = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        return runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(
                            if (voice) AudioAttributes.USAGE_VOICE_COMMUNICATION
                            else AudioAttributes.USAGE_MEDIA
                        )
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minOut * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull()
    }

    /** Сквозная проверка: микрофон → цепочка → наушники, без задержки. */
    private suspend fun loopback(my: Int) {
        var record: AudioRecord? = null
        var track: AudioTrack? = null
        try {
            // Ссылки неизменяемые: read/write зовутся внутри runCatching, а
            // изменяемую локальную переменную из лямбды Kotlin не приводит к
            // ненулевому типу.
            val rec = openRecord() ?: return
            record = rec
            val out = openTrack(voice = true) ?: return
            track = out

            val mic = newProcessor()
            processor = mic
            _phase.value = Phase.LOOPBACK

            rec.startRecording()
            out.play()

            // Блок ~20 мс: у WebRTC он 10 мс, но на возврате в наушники
            // короче делать нечего — только лишние переключения потока.
            val bytes = ByteArray(SAMPLE_RATE / 50 * 2)
            // Порядок байт буферу не задаём: MicProcessor читает и пишет
            // отсчёты побайтно, как это делает WebRTC.
            val buf = ByteBuffer.wrap(bytes)

            while (coroutineContext.isActive) {
                val read = runCatching { rec.read(bytes, 0, bytes.size) }.getOrDefault(-1)
                if (read <= 0) break
                refresh(mic)
                runCatching { mic.process(buf, read, 1, SAMPLE_RATE) }
                runCatching { out.write(bytes, 0, read) }
            }
        } catch (_: Throwable) {
            // Проверка не критична: не поднялась — значит не поднялась.
        } finally {
            closeRecord(record)
            closeTrack(track)
            done(my)
        }
    }

    /** Без наушников: сначала пишем, потом проигрываем вслух. */
    private suspend fun recordThenPlay(my: Int) {
        try {
            record()
        } finally {
            done(my)
        }
    }

    private suspend fun record() {
        val pcm = ByteArrayOutputStream()
        var record: AudioRecord? = null
        try {
            val rec = openRecord() ?: return
            record = rec

            val mic = newProcessor()
            processor = mic
            _phase.value = Phase.RECORDING
            _secondsLeft.value = RECORD_SECONDS

            rec.startRecording()

            val bytes = ByteArray(SAMPLE_RATE / 50 * 2)
            val buf = ByteBuffer.wrap(bytes)
            val wanted = SAMPLE_RATE * RECORD_SECONDS * 2

            while (coroutineContext.isActive && pcm.size() < wanted) {
                val read = runCatching { rec.read(bytes, 0, bytes.size) }.getOrDefault(-1)
                if (read <= 0) break
                refresh(mic)
                runCatching { mic.process(buf, read, 1, SAMPLE_RATE) }
                pcm.write(bytes, 0, read)
                _secondsLeft.value =
                    ((wanted - pcm.size()).coerceAtLeast(0) / (SAMPLE_RATE * 2)) + 1
            }
        } catch (_: Throwable) {
        } finally {
            processor = null
            _secondsLeft.value = 0
            // Микрофон закрываем ДО проигрывания: он больше не нужен, а
            // открытый захват рядом с играющим динамиком — это и есть та
            // самая обратная связь, которой мы избегаем.
            closeRecord(record)
        }

        val data = pcm.toByteArray()
        if (data.isEmpty() || !coroutineContext.isActive) return

        var track: AudioTrack? = null
        try {
            val out = openTrack(voice = false) ?: return
            track = out
            _phase.value = Phase.PLAYING
            out.play()

            var off = 0
            while (coroutineContext.isActive && off < data.size) {
                val n = runCatching { out.write(data, off, minOf(8192, data.size - off)) }
                    .getOrDefault(-1)
                if (n <= 0) break
                off += n
            }
            // stop() отпускает уже записанное доиграть, а release() сразу за
            // ним обрубил бы хвост фразы — поэтому короткая пауза.
            runCatching { out.stop() }
            delay(300)
        } catch (_: Throwable) {
        } finally {
            closeTrack(track)
        }
    }

    private fun closeRecord(rec: AudioRecord?) {
        rec ?: return
        runCatching { rec.stop() }
        runCatching { rec.release() }
    }

    private fun closeTrack(track: AudioTrack?) {
        track ?: return
        runCatching { track.stop() }
        runCatching { track.release() }
    }
}
