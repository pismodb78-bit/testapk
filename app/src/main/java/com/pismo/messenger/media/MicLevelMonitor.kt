package com.pismo.messenger.media

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.pismo.messenger.call.ActiveCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
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
 * ДВА ИСТОЧНИКА, и это принципиально.
 *
 * Вне разговора мерить нечем, кроме собственного захвата: 16 кГц моно PCM-16,
 * источник VOICE_COMMUNICATION — как у голосовых.
 *
 * А вот В РАЗГОВОРЕ свой микрофон открывать нельзя и незачем. Нельзя —
 * потому что он занят звонком. Незачем — потому что это был бы ДРУГОЙ поток
 * с другой обработкой, и цифры на шкале начинали жить отдельно от порога:
 * шкала показывала −54 дБ там, где порог −20 уже резал речь. В разговоре
 * уровень берётся прямо из цепочки звонка — у той самой величины, с которой
 * гейт и сравнивает порог.
 *
 * Источник выбирается НА КАЖДОМ ТИКЕ, а не один раз при запуске: настройки
 * открывают и до звонка, и во время, и звонок начинается/кончается прямо при
 * открытом экране. Цикл один, он сам подхватывает переход в обе стороны и
 * закрывает свой захват, как только звонок поднялся.
 */
object MicLevelMonitor {

    private const val SAMPLE_RATE = 16000

    /** Тише этого не показываем: −60 дБ — левый край шкалы порога. */
    const val FLOOR_DB = -60f

    private val _levelDb = MutableStateFlow(FLOOR_DB)
    val levelDb: StateFlow<Float> = _levelDb.asStateFlow()

    @Volatile
    private var record: AudioRecord? = null
    private var job: Job? = null

    /**
     * Сколько экранов сейчас просят индикатор. Счётчик, а не флаг: настройки
     * могут пересобраться (поворот, смена темы), и на стыке старый экран
     * закрывается уже после того, как новый попросил захват.
     */
    private var users = 0

    val isRunning: Boolean get() = job != null

    @Synchronized
    fun acquire(scope: CoroutineScope) {
        users++
        if (job != null) return

        job = scope.launch(Dispatchers.IO) {
            // Кусок на ~64 мс при 16 кГц: чаще дёргать шкалу бессмысленно,
            // реже — она начинает «залипать» на слогах.
            val chunk = ShortArray(1024)
            try {
                while (isActive) {
                    val call = ActiveCall.engine
                    if (call != null) {
                        // Разговор идёт: свой захват отпускаем (микрофон нужен
                        // звонку) и читаем уровень из его же цепочки.
                        closeRecord()
                        _levelDb.value = smooth(_levelDb.value, call.micLevelDb)
                        delay(60)
                        continue
                    }

                    val rec = record ?: openRecord()
                    if (rec == null) {
                        _levelDb.value = FLOOR_DB
                        delay(300)
                        continue
                    }

                    val read = runCatching { rec.read(chunk, 0, chunk.size) }.getOrDefault(-1)
                    if (read <= 0) {
                        closeRecord()
                        delay(300)
                        continue
                    }

                    var sum = 0.0
                    for (i in 0 until read) {
                        val v = chunk[i].toDouble()
                        sum += v * v
                    }
                    val rms = sqrt(sum / read)
                    val db = if (rms > 1.0) (20.0 * log10(rms / 32768.0)).toFloat() else FLOOR_DB
                    _levelDb.value = smooth(_levelDb.value, db)
                }
            } finally {
                closeRecord()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openRecord(): AudioRecord? = runCatching {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2,
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return@runCatching null
        }
        rec.startRecording()
        record = rec
        rec
    }.getOrNull()

    private fun closeRecord() {
        val r = record ?: return
        record = null
        runCatching { r.stop() }
        runCatching { r.release() }
    }

    /**
     * Спад плавный, подъём мгновенный: иначе полоса дрожит и по ней
     * невозможно поймать, где именно проходит речь.
     */
    private fun smooth(prev: Float, db: Float): Float =
        if (db > prev) db.coerceIn(FLOOR_DB, 0f)
        else (prev + (db - prev) * 0.25f).coerceIn(FLOOR_DB, 0f)

    @Synchronized
    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users > 0) return
        job?.cancel()
        job = null
        closeRecord()
        _levelDb.value = FLOOR_DB
    }
}
