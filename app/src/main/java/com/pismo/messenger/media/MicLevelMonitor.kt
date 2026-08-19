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
 * Источников три, по убыванию точности: идущий разговор, проверка микрофона
 * (та же цепочка, тот же уровень) и, если ничего из этого нет, собственный
 * сырой захват с поправкой ниже.
 *
 * Источник выбирается НА КАЖДОМ ТИКЕ, а не один раз при запуске: настройки
 * открывают и до звонка, и во время, и звонок начинается/кончается прямо при
 * открытом экране. Цикл один, он сам подхватывает переход в обе стороны и
 * закрывает свой захват, как только звонок поднялся.
 *
 * ПОЧЕМУ ВНЕ ЗВОНКА УРОВЕНЬ ПРИХОДИТСЯ ПОДНИМАТЬ. Два источника меряют один
 * голос, но в разных точках тракта. В разговоре буфер приходит от WebRTC уже
 * ПОСЛЕ его обработки, а там работает автоусиление (AGC), которое подтягивает
 * речь к своему целевому уровню. Свой же захват — это сырой микрофон до всякой
 * автоматики, и та же речь в нём тише на два десятка децибел: шкала показывала
 * −50 дБ там, где в звонке было бы −20, и выставленный по ней порог в разговоре
 * оказывался бессмысленным.
 *
 * Поэтому вне звонка к сырому уровню применяется та же автоматика — медленное
 * усиление к целевому уровню WebRTC с тем же потолком. Это не «накрутка
 * красивых цифр»: без неё шкала врёт ровно на величину AGC.
 */
object MicLevelMonitor {

    private const val SAMPLE_RATE = 16000

    /** Тише этого не показываем: −60 дБ — левый край шкалы порога. */
    const val FLOOR_DB = -60f

    /**
     * Целевой уровень речи у автоусиления WebRTC. Именно к нему AGC тянет
     * голос в разговоре, поэтому к нему же тянем и свой замер.
     */
    private const val AGC_TARGET_DB = -18f

    /** Потолок автоусиления — столько же, сколько отдаёт цифровой AGC. */
    private const val AGC_MAX_GAIN_DB = 30f

    /**
     * Тише этого усиление не трогаем. Иначе в тишине оно доехало бы до
     * потолка, и шумок комнаты показывался бы как громкая речь.
     */
    private const val AGC_SPEECH_FLOOR_DB = -55f

    private var agcGainDb = 0f

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

                    if (MicTester.isRunning) {
                        // Идёт проверка микрофона — она гоняет ровно ту же
                        // цепочку, что и звонок. Значит, уровень настоящий, а
                        // не оценка по сырому микрофону: порог здесь можно
                        // выставлять точно.
                        closeRecord()
                        _levelDb.value = smooth(_levelDb.value, MicTester.levelDb)
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
                    val raw = if (rms > 1.0) (20.0 * log10(rms / 32768.0)).toFloat() else -100f
                    _levelDb.value = smooth(_levelDb.value, applyAgc(raw))
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
        agcGainDb = 0f
        val r = record ?: return
        record = null
        runCatching { r.stop() }
        runCatching { r.release() }
    }

    /**
     * Эмуляция автоусиления WebRTC поверх сырого замера.
     *
     * Усиление ползёт к нужному, а не прыгает: настоящий AGC тоже
     * инерционный, и мгновенный скачок на первом же слоге дал бы шкале
     * дёргаться сильнее, чем сам голос. Вверх быстрее, чем вниз, — чтобы
     * начало фразы не оставалось за кадром.
     */
    private fun applyAgc(rawDb: Float): Float {
        if (rawDb > AGC_SPEECH_FLOOR_DB) {
            val want = (AGC_TARGET_DB - rawDb).coerceIn(0f, AGC_MAX_GAIN_DB)
            agcGainDb += (want - agcGainDb) * (if (want > agcGainDb) 0.15f else 0.03f)
        } else {
            // В долгой тишине усиление отпускаем, иначе шкала так и стояла бы
            // задранной после последней фразы и шум комнаты выглядел бы речью.
            // Спад медленный: паузу между слогами он почти не замечает.
            agcGainDb *= 0.98f
        }
        return rawDb + agcGainDb
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
