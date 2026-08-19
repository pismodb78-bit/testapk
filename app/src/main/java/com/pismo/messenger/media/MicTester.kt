package com.pismo.messenger.media

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.pismo.messenger.call.MicProcessor
import com.pismo.messenger.core.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

/**
 * Проверка микрофона на ТОЙ ЖЕ цепочке, что и звонок — порт MicTestForm.cs.
 *
 * Зачем это, когда есть шкала уровня. Шкала отвечает на вопрос «громко ли»,
 * а главный вопрос другой: «как это звучит у собеседника». Шумодав, порог
 * активации и makeup-усиление слышны, а не видны: по полоске нельзя понять,
 * что подавление съедает окончания слов или что порог режет начало фразы.
 * Здесь микрофон идёт через ровно тот же MicProcessor с теми же настройками
 * и возвращается в наушники — слышно именно то, что услышат в звонке.
 *
 * ПОБОЧНАЯ ПОЛЬЗА, ради которой это стоило сделать даже отдельно от звука:
 * пока тест идёт, шкала порога получает НАСТОЯЩИЙ уровень — тот самый, с
 * которым сравнивается порог, — а не оценку по сырому микрофону. Вне звонка
 * это единственный способ выставить порог точно.
 *
 * ПРО ОБРАТНУЮ СВЯЗЬ. Возврат звука в динамик телефона — это микрофон,
 * слушающий собственный динамик, то есть свист через полсекунды. Поэтому
 * вывод идёт голосовым трактом (в наушниках и в разговорном динамике всё
 * тихо), а в интерфейсе стоит прямое предупреждение про наушники. Эхоподавление
 * WebRTC здесь не работает: его тут попросту нет — цепочка своя.
 */
object MicTester {

    /** 48 кГц — как в звонке: лимитер считает упреждение от частоты. */
    private const val SAMPLE_RATE = 48000

    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile
    private var job: Job? = null

    @Volatile
    private var processor: MicProcessor? = null

    val isRunning: Boolean get() = job != null

    /**
     * Уровень последнего блока в дБFS — ТОТ ЖЕ, с которым сравнивается порог
     * активации. −100, когда тест не идёт.
     */
    val levelDb: Float get() = processor?.lastLevelDb ?: -100f

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start() {
        if (job != null) return
        // В разговоре микрофон занят звонком, и второй захват либо не
        // откроется, либо отберёт его у собеседников. Да и проверять нечего:
        // в звонке и так слышно, как ты звучишь.
        if (com.pismo.messenger.call.ActiveCall.engine != null) return

        job = scope.launch {
            var record: AudioRecord? = null
            var track: AudioTrack? = null
            try {
                val minIn = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(4096)

                val rec = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minIn * 2,
                )
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    rec.release()
                    return@launch
                }
                record = rec

                val minOut = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(4096)

                val out = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            // Голосовой тракт: в наушниках и разговорном
                            // динамике возврат не заводится в свист.
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
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
                track = out

                // Цепочка ровно та же, что вешается на дорожку в звонке, и с
                // теми же настройками — иначе тест проверял бы не то.
                val mic = MicProcessor(SAMPLE_RATE).apply {
                    strength = if (Prefs.noiseSuppression) Prefs.denoiseStrength else 0f
                    voiceGateAuto = Prefs.voiceAutoSensitivity
                    voiceThresholdDb = Prefs.voiceThresholdDb
                    outputGainPercent = Prefs.voiceOutputGain
                }
                processor = mic

                rec.startRecording()
                out.play()

                // Блок ~20 мс: у WebRTC он 10 мс, но на возврате в наушники
                // короче делать нечего — только лишние переключения потока.
                val bytes = ByteArray(SAMPLE_RATE / 50 * 2)
                // Порядок байт буферу не задаём: MicProcessor читает и пишет
                // отсчёты побайтно, как это делает WebRTC, — от настройки
                // ByteBuffer он не зависит.
                val buf = ByteBuffer.wrap(bytes)

                while (isActive) {
                    val read = runCatching { rec.read(bytes, 0, bytes.size) }.getOrDefault(-1)
                    if (read <= 0) break

                    // Настройки крутят прямо во время теста — в этом весь смысл.
                    mic.strength = if (Prefs.noiseSuppression) Prefs.denoiseStrength else 0f
                    mic.voiceGateAuto = Prefs.voiceAutoSensitivity
                    mic.voiceThresholdDb = Prefs.voiceThresholdDb
                    mic.outputGainPercent = Prefs.voiceOutputGain

                    runCatching { mic.process(buf, read, 1, SAMPLE_RATE) }
                    runCatching { out.write(bytes, 0, read) }
                }
            } catch (_: Throwable) {
                // Тест не критичен: не поднялся — значит не поднялся.
            } finally {
                processor = null
                runCatching { record?.stop() }
                runCatching { record?.release() }
                runCatching { track?.stop() }
                runCatching { track?.release() }
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        processor = null
    }
}
