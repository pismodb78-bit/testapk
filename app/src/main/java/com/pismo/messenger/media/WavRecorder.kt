package com.pismo.messenger.media

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.pismo.messenger.core.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Запись голосовых сообщений в WAV 16 кГц / моно / PCM-16.
 *
 * Формат выбран не случайно: ПК-версия пишет ровно такой WAV через
 * NAudio (`new WaveFormat(16000, 1)`) и кладёт его в БД как есть. Любой
 * другой контейнер (например, AAC из MediaRecorder) десктоп проиграть не
 * сможет — поэтому здесь именно сырой AudioRecord и ручная шапка WAV.
 */
class WavRecorder(private val scope: CoroutineScope) {

    companion object {
        const val SAMPLE_RATE = 16000
        private const val CHANNELS = 1
        private const val BITS = 16

        /** Минимальная длина, ниже которой запись считается случайным тапом. */
        const val MIN_BYTES = 4000

        /**
         * Целевой средний уровень записи, доля полной шкалы. 0.14 — это
         * примерно −17 dBFS: обычная громкость речи в мессенджерах, ещё
         * далеко от потолка, так что запас на пики остаётся.
         */
        private const val TARGET_RMS = 0.14

        /**
         * Потолок выравнивания. Без него совсем тихая запись вытянулась бы
         * вместе с шумом комнаты и шипением микрофона.
         */
        private const val MAX_NORMALIZE_GAIN = 6f
    }

    private var record: AudioRecord? = null
    private var job: Job? = null
    private val buffer = ByteArrayOutputStream()

    @Volatile var isRecording = false
        private set

    /** Текущий уровень сигнала 0..1 — для анимации волны при записи. */
    @Volatile var level: Float = 0f
        private set

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (isRecording) return true
        return try {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)

            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return false
            }

            buffer.reset()
            record = rec
            isRecording = true
            rec.startRecording()

            val gain = Prefs.micGain
            job = scope.launch(Dispatchers.IO) {
                val chunk = ByteArray(minBuf)
                while (isRecording) {
                    val read = rec.read(chunk, 0, chunk.size)
                    if (read > 0) {
                        val processed = if (abs(gain - 1f) > 0.01f) applyGain(chunk, read, gain) else chunk
                        buffer.write(processed, 0, read)
                        level = peakLevel(processed, read)
                    }
                }
            }
            true
        } catch (_: Exception) {
            isRecording = false
            record = null
            false
        }
    }

    /** Останавливает запись и отдаёт готовый WAV, либо null если слишком коротко. */
    fun stop(): ByteArray? {
        if (!isRecording) return null
        isRecording = false
        level = 0f

        runCatching {
            record?.stop()
            record?.release()
        }
        record = null
        job = null

        val pcm = buffer.toByteArray()
        buffer.reset()
        if (pcm.size < MIN_BYTES) return null
        return wrapInWavHeader(normalize(pcm))
    }

    /** Отмена без сохранения. */
    fun cancel() {
        isRecording = false
        level = 0f
        runCatching {
            record?.stop()
            record?.release()
        }
        record = null
        job = null
        buffer.reset()
    }

    /** Длительность записанного на данный момент, в секундах. */
    val elapsedSeconds: Float
        get() = buffer.size() / (SAMPLE_RATE * 2f)

    /**
     * Множитель по 16-битным сэмплам — порт SoftGainSample из ПК-версии.
     *
     * Линейно до 0.9 полной шкалы и мягкое ограничение только у самого
     * потолка. Раньше здесь стоял жёсткий клип, и он ГРОМКОСТИ НЕ ДОБАВЛЯЛ:
     * пики срезались в прямую линию, средний уровень оставался прежним, зато
     * появлялся хрип. На ПК это уже поняли и заменили — заменяем и здесь.
     */
    private fun applyGain(data: ByteArray, length: Int, gain: Float): ByteArray {
        val out = data.copyOf(length)
        var i = 0
        while (i + 1 < length) {
            val sample = ((out[i + 1].toInt() shl 8) or (out[i].toInt() and 0xFF)).toShort()
            val amplified = softGain(sample, gain)
            out[i] = (amplified.toInt() and 0xFF).toByte()
            out[i + 1] = ((amplified.toInt() shr 8) and 0xFF).toByte()
            i += 2
        }
        return out
    }

    private fun softGain(sample: Short, g: Float): Short {
        var x = sample * g / 32768f
        var a = if (x < 0f) -x else x
        if (a > 0.9f) {
            a = 0.9f + 0.1f * tanh((a - 0.9f) / 0.1f)
            x = if (x < 0f) -a else a
        }
        return (x * 32767f).toInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    /**
     * Выравнивание громкости записи.
     *
     * Голосовые и кружочки приходили тихими, и дело не в микрофоне: телефон
     * держат в руке, а не у рта, и уровень пляшет от записи к записи. Слушать
     * приходилось, каждый раз подкручивая громкость.
     *
     * Запись целиком лежит в памяти, поэтому уровень можно измерить и
     * поднять ТОЧНО — в отличие от звонка, где сигнал идёт потоком и
     * приходится угадывать заранее. Считаем по RMS, а не по пику: один стук
     * по корпусу не должен решать за всю запись. Потолок усиления есть
     * обязательно, иначе тишина превратилась бы в громкое шипение.
     */
    private fun normalize(pcm: ByteArray): ByteArray {
        val n = pcm.size / 2
        if (n < 16) return pcm

        var sum = 0.0
        var i = 0
        while (i + 1 < pcm.size) {
            val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
            sum += s.toDouble() * s
            i += 2
        }
        val rms = sqrt(sum / n)
        if (rms < 1.0) return pcm            // тишина — усиливать нечего

        val target = TARGET_RMS * 32768.0
        val gain = (target / rms).toFloat().coerceIn(1f, MAX_NORMALIZE_GAIN)
        if (gain <= 1.01f) return pcm        // и так достаточно громко

        return applyGain(pcm, pcm.size, gain)
    }

    private fun peakLevel(data: ByteArray, length: Int): Float {
        var peak = 0
        var i = 0
        while (i + 1 < length) {
            val sample = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF)).toShort()
            val a = abs(sample.toInt())
            if (a > peak) peak = a
            i += 32   // прореживаем — для индикатора точность не нужна
        }
        return min(1f, peak / 32768f)
    }

    /** Шапка RIFF/WAVE поверх сырого PCM. */
    private fun wrapInWavHeader(pcm: ByteArray): ByteArray {
        val byteRate = SAMPLE_RATE * CHANNELS * BITS / 8
        val blockAlign = CHANNELS * BITS / 8
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)                       // размер fmt-чанка
        header.putShort(1)                      // PCM
        header.putShort(CHANNELS.toShort())
        header.putInt(SAMPLE_RATE)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(BITS.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcm.size)

        return header.array() + pcm
    }
}
