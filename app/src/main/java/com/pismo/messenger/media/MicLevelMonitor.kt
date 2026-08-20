package com.pismo.messenger.media

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.pismo.messenger.call.ActiveCall
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

    /**
     * Потолок автоусиления. 36 дБ, а не 30: на тихом микрофоне сырая речь
     * идёт около −55 дБ, и тридцати не хватало, чтобы дотянуть её до цели —
     * шкала упиралась в потолок ниже, чем показывает разговор.
     */
    private const val AGC_MAX_GAIN_DB = 36f

    /**
     * Насколько сигнал должен подняться над фоном комнаты, чтобы считаться
     * речью. Десять децибел — обычный разрыв между «человек молчит» и
     * «человек говорит»; ниже начинается шум вентилятора.
     */
    private const val SPEECH_OVER_NOISE_DB = 10f

    /** Ниже этого — цифровая тишина, усиливать нечего. */
    private const val HARD_FLOOR_DB = -75f

    private var agcGainDb = 0f

    /** Оценка фона комнаты, см. applyAgc. */
    private var noiseFloorDb = -60f

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
                        // ПРОВЕРКА МИКРОФОНА ШКАЛУ БОЛЬШЕ НЕ ВЕДЁТ.
                        //
                        // Раньше уровень на время проверки брался из её
                        // цепочки — казалось, что он «настоящий, а не
                        // оценка». На деле MicProcessor меряет СВОЙ вход, то
                        // есть сырой микрофон до автоусиления, а всё
                        // остальное время шкала показывает уровень уже с
                        // поправкой на него. Цифры расходились на два-три
                        // десятка децибел: стоило нажать «Проверить», и шкала
                        // проваливалась к −55 там, где только что было −20.
                        //
                        // Свой захват при этом не открываем: микрофон занят
                        // проверкой, и драться за него незачем. Шкала на это
                        // время просто не рисуется, см. MicLevelBar.
                        closeRecord()
                        _levelDb.value = FLOOR_DB
                        delay(120)
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
                    // Усиление микрофона в звонке применяется ДО замера уровня,
                    // значит и шкала обязана его учитывать: иначе выставленный
                    // по ней порог в разговоре окажется не там, где ставили.
                    _levelDb.value = smooth(_levelDb.value, applyAgc(raw + gainDb()))
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
        noiseFloorDb = -60f
        val r = record ?: return
        record = null
        runCatching { r.stop() }
        runCatching { r.release() }
    }

    /** Ползунок «усиление микрофона» в децибелах: ×2 это +6 дБ. */
    private fun gainDb(): Float {
        val g = Prefs.micGain
        if (g > 0.99f && g < 1.01f) return 0f
        return (20.0 * log10(g.coerceAtLeast(0.05f).toDouble())).toFloat()
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
        // ФОН СЧИТАЕМ САМИ, А НЕ БЕРЁМ КОНСТАНТОЙ.
        //
        // Раньше здесь стояла граница «тише −55 дБ не усиливаем». Задумана
        // она была как защита от накрутки тишины, но на тихом микрофоне под
        // неё попадала САМА РЕЧЬ: усиление не включалось вовсе, и шкала
        // показывала −55 там, где разговор показывает −35…−20. Порог,
        // выставленный по такой шкале, в звонке оказывался бессмысленным.
        //
        // Чувствительность микрофонов различается на десятки децибел, поэтому
        // судить надо не по абсолютной величине, а по тому, насколько сигнал
        // поднялся над фоном ЭТОЙ комнаты. Вниз фон падает сразу — тишина
        // наступила, вот она; вверх ползёт еле-еле, иначе длинная фраза
        // утянула бы фон за собой и речь перестала бы считаться речью.
        noiseFloorDb =
            if (rawDb < noiseFloorDb) rawDb
            else noiseFloorDb + (rawDb - noiseFloorDb) * 0.0015f

        val speech = rawDb > HARD_FLOOR_DB && rawDb > noiseFloorDb + SPEECH_OVER_NOISE_DB
        if (speech) {
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
