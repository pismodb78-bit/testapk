package com.pismo.messenger.media

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Живой уровень микрофона в дБFS — для шкалы порога активации голоса.
 *
 * Порт индикатора из MicTestForm ПК-версии. Там под ползунком порога живёт
 * полоса, которая дёргается в такт голосу: без неё выставить порог можно
 * только наугад — «−31 дБ» само по себе ничего не говорит, надо ВИДЕТЬ, где
 * относительно этой черты оказывается собственная речь и где фон комнаты.
 *
 * Захват тот же, что у голосовых: 16 кГц моно PCM-16, источник
 * VOICE_COMMUNICATION. Уровень считается по RMS блока и отдаётся в дБ
 * относительно полной шкалы, как и порог, — иначе сравнивать было бы нечего.
 */
object MicLevelMonitor {

    private const val SAMPLE_RATE = 16000

    /** Тише этого не показываем: −60 дБ — левый край шкалы порога. */
    const val FLOOR_DB = -60f

    private val _levelDb = MutableStateFlow(FLOOR_DB)
    val levelDb: StateFlow<Float> = _levelDb.asStateFlow()

    private var record: AudioRecord? = null
    private var job: Job? = null

    /**
     * Сколько экранов сейчас просят индикатор. Счётчик, а не флаг: настройки
     * могут пересобраться (поворот, смена темы), и на стыке старый экран
     * закрывается уже после того, как новый попросил захват.
     */
    private var users = 0

    val isRunning: Boolean get() = job != null

    @SuppressLint("MissingPermission")
    @Synchronized
    fun acquire(scope: CoroutineScope) {
        users++
        if (job != null) return

        runCatching {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(2048)

            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return
            }
            record = rec
            rec.startRecording()

            job = scope.launch(Dispatchers.IO) {
                val chunk = ShortArray(minBuf / 2)
                while (record != null) {
                    val read = runCatching { rec.read(chunk, 0, chunk.size) }.getOrDefault(-1)
                    if (read <= 0) break
                    var sum = 0.0
                    for (i in 0 until read) {
                        val v = chunk[i].toDouble()
                        sum += v * v
                    }
                    val rms = sqrt(sum / read)
                    val db = if (rms > 1.0) (20.0 * log10(rms / 32768.0)).toFloat() else FLOOR_DB
                    // Спад плавный, подъём мгновенный: иначе полоса дрожит и
                    // по ней невозможно поймать, где именно проходит речь.
                    val prev = _levelDb.value
                    _levelDb.value =
                        if (db > prev) db.coerceIn(FLOOR_DB, 0f)
                        else (prev + (db - prev) * 0.25f).coerceIn(FLOOR_DB, 0f)
                }
            }
        }.onFailure { release() }
    }

    @Synchronized
    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users > 0) return
        job?.cancel()
        job = null
        val r = record
        record = null
        runCatching { r?.stop() }
        runCatching { r?.release() }
        _levelDb.value = FLOOR_DB
    }
}
