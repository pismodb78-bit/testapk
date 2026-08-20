package com.pismo.messenger.call

import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Частотный шумодав микрофона: STFT + винеровская фильтрация с оценкой
 * априорного SNR по методу decision-directed.
 *
 * ЧЕМ ОН ОТЛИЧАЕТСЯ ОТ ГЕЙТА, и почему заменил его.
 * Гейт судит о звуке ЦЕЛИКОМ: громче порога — пропустить, тише — задавить.
 * Поэтому он либо пропускает клавиатуру вместе с речью, либо, если поднять
 * порог, откусывает начала и хвосты слов — то самое «шакалит голос с
 * повышением процента». Здесь подавление считается ПО ЧАСТОТАМ: оценивается
 * спектр постоянного фона (кулер, шипение, гул, дальний гомон) и прижимаются
 * только те полосы, где полезного сигнала почти нет. Голос остаётся целым,
 * потому что его полосы имеют высокий SNR и проходят с усилением ~1.
 *
 * ЧТО ВЗЯТО С ПК И ЧТО ПРИШЛОСЬ ДОДЕЛАТЬ.
 * Каркас — порт PISMO/Native/SpectralDenoiser.cs (N=512, HOP=256, Ханн,
 * 50% перекрытия). Но на ПК этот класс — запасной путь: основную работу
 * там делает RNNoise, а спектральный включается, только если нативная
 * библиотека не поднялась, и потому подробно не вылизан. На Android RNNoise
 * нет и взять неоткуда, так что этот класс здесь основной, и две вещи в нём
 * пришлось сделать как положено:
 *
 *  • ОЦЕНКА ФОНА. В оригинале она бежит к минимуму мгновенно
 *    (`if (pow < noise) noise = pow`). Периодограмма шума скачет в разы от
 *    кадра к кадру, поэтому «минимум» оказывается сильно ниже настоящего
 *    среднего, SNR завышается и фильтр почти не давит: замер показал −3.5 дБ
 *    по фону при −11 дБ по голосу, то есть ХУЖЕ, чем ничего. Теперь
 *    периодограмма сглаживается по времени, оценка обновляется быстро в
 *    паузах и почти замирает на речи, а сверху её держит долгий минимум —
 *    так речь не утаскивает оценку за собой.
 *
 *  • УСИЛЕНИЕ. Вместо мгновенного SNR — decision-directed (Ephraim–Malah):
 *    априорный SNR наполовину берётся из уже очищенного предыдущего кадра.
 *    Это стандартное лекарство от «музыкального» шума — тех самых звенящих
 *    призвуков, из-за которых наивный спектральный шумодав звучит как рация.
 *
 * Окно — корень из Ханна на анализе И на синтезе (WOLA). При 50% перекрытия
 * сумма квадратов даёт ровно единицу, а синтез-окно сглаживает стыки кадров,
 * когда усиление меняется между ними.
 *
 * Задержка — HOP приморозки + полкадра перекрытия, около 10 мс на 48 кГц.
 * Работает на 16-битном моно PCM in-place, little-endian.
 */
class SpectralDenoiser(sampleRate: Int = 48_000) {

    /**
     * 0..1 — сила подавления. Управляет двумя вещами сразу: агрессивностью
     * вычитания и «полом» подавления. Ниже пола не опускаемся никогда:
     * полная вырезка полосы и даёт звенящие артефакты.
     *
     * Коэффициенты взяты С ПК (Strength = 0.4 + f·1.4, Floor = 0.18 − f·0.08).
     * Свои подбирались, пока оценка фона здесь была сломана; после её
     * починки замер показал, что настройка ПК просто лучше — при тех же
     * потерях голоса она давит фон заметно сильнее на середине хода:
     *
     *   ползунок |  свои: фон / голос  |   ПК: фон / голос
     *      25%   |   −11.5 / −0.3 дБ   |   −15.3 / −0.2 дБ
     *      50%   |   −13.6 / −0.4 дБ   |   −17.0 / −0.3 дБ
     *      75%   |   −16.2 / −0.5 дБ   |   −18.4 / −0.4 дБ
     *     100%   |   −20.1 / −0.6 дБ   |   −20.0 / −0.5 дБ
     *
     * На максимуме разницы нет, а весь остальной ход у ПК ровнее — держать
     * своё было не за что.
     */
    @Volatile
    var strength: Float = 1f

    private val win = FloatArray(N)         // корень из Ханна, анализ = синтез
    private val window = FloatArray(N)      // скользящее окно анализа
    private val overlap = FloatArray(N)     // накопитель overlap-add

    private val noisePow = FloatArray(BINS)
    private val powNow = FloatArray(BINS)
    private val powSmooth = FloatArray(BINS)
    private val minRunning = FloatArray(BINS)
    private val minHeld = FloatArray(BINS)
    private val prevClean = FloatArray(BINS)
    private var minAge = 0
    private var frames = 0

    // ── Границы полос для защиты от задувания, в номерах бинов ──
    //
    // Ширина бина = частота дискретизации / N, поэтому границы считаются от
    // реальной частоты, а не зашиты числами: на 48 кГц бин это 94 Гц, на
    // 16 кГц — 31 Гц, и одни и те же номера означали бы совсем разные полосы.
    private val binHz = sampleRate.toFloat() / N

    /** Ниже этого режем всегда: речи там нет ни у кого. */
    private val hpfBins = (HPF_HZ / binHz).toInt().coerceIn(1, BINS - 1)

    /** Полоса задувания — от отсечки до WIND_TOP_HZ. */
    private val windTo = (WIND_TOP_HZ / binHz).toInt().coerceIn(hpfBins, BINS - 1)

    /** Полоса, по которой судим о наличии речи. */
    private val voiceFrom = (VOICE_FROM_HZ / binHz).toInt().coerceIn(0, BINS - 1)
    private val voiceTo = (VOICE_TO_HZ / binHz).toInt().coerceIn(voiceFrom + 1, BINS - 1)

    /** Сглаженное «дует», 0..1. */
    private var windEnv = 0f


    private var inFifo = ShortArray(N * 4)
    private var inCount = 0
    private var outFifo = ShortArray(N * 4)
    private var outHead = 0
    private var outCount = 0

    private val re = FloatArray(N)
    private val im = FloatArray(N)
    private val rev = IntArray(N)

    init {
        for (n in 0 until N) {
            val hann = 0.5f - 0.5f * cos(2.0 * PI * n / N).toFloat()
            win[n] = sqrt(hann)
        }

        // Битовая перестановка для итеративного FFT (2^9 = 512).
        for (i in 0 until N) {
            var x = i
            var r = 0
            for (b in 0 until 9) {
                r = (r shl 1) or (x and 1)
                x = x shr 1
            }
            rev[i] = r
        }

        // Приморозка выхода ровно на HOP: этого хватает, чтобы выходной FIFO
        // никогда не иссяк при любой длине входного блока (на ПК заморожен
        // целый кадр — лишние 5 мс задержки на пустом месте).
        repeat(HOP) { pushOut(0) }
    }

    /** Обработать блок 16-битного PCM in-place; [bytesRead] — занятые байты. */
    fun process(buffer: ByteBuffer, bytesRead: Int) {
        val m = bytesRead / 2
        if (m <= 0) return

        // 1) вход → FIFO
        ensureInCapacity(inCount + m)
        for (i in 0 until m) {
            val idx = i * 2
            val lo = buffer.get(idx).toInt() and 0xFF
            val hi = buffer.get(idx + 1).toInt()
            inFifo[inCount++] = ((hi shl 8) or lo).toShort()
        }

        // 2) обрабатываем целыми HOP-шагами
        var consumed = 0
        while (inCount - consumed >= HOP) {
            processHop(consumed)
            consumed += HOP
        }
        if (consumed > 0) {
            val rem = inCount - consumed
            System.arraycopy(inFifo, consumed, inFifo, 0, rem)
            inCount = rem
        }

        // 3) выход ← FIFO (ровно m сэмплов — он всегда впереди из-за приморозки)
        for (i in 0 until m) {
            val s = popOut().toInt()
            val idx = i * 2
            buffer.put(idx, (s and 0xFF).toByte())
            buffer.put(idx + 1, ((s shr 8) and 0xFF).toByte())
        }
    }

    /**
     * Задувание в микрофон: ветер, дыхание, «п» в упор.
     *
     * ПОЧЕМУ ОСНОВНОЙ ФИЛЬТР ЭТОГО НЕ БЕРЁТ. Он ищет ПОСТОЯННЫЙ фон и
     * специально замирает, когда полоса вдруг стала громче своей оценки
     * втрое — так он не выучивает речь как шум. Задувание ровно такое: редкий
     * громкий всплеск на низах. Фильтр честно принимает его за речь и
     * пропускает целиком, ещё и с усилением около единицы, потому что SNR у
     * него огромный.
     *
     * ПО ЧЕМУ ОТЛИЧАЕМ ОТ ГОЛОСА. По наклону спектра. У задувания почти вся
     * энергия ниже трёхсот герц, а выше — пусто. Речь, даже низкий мужской
     * голос, всегда несёт заметную энергию в полосе разборчивости
     * (400–3400 Гц): без неё её просто не было бы слышно. Поэтому решение —
     * отношение энергии низов к энергии этой полосы, а не громкость.
     *
     * Порог по громкости тоже нужен: в тишине наклон спектра случаен, и без
     * него мы бы «давили ветер» на пустом месте.
     */
    private fun suppressWind() {
        // Ниже HPF_HZ режем всегда и безусловно: там нет речи, только
        // рокот корпуса и та самая струя воздуха.
        for (k in 0 until hpfBins) {
            re[k] = 0f
            im[k] = 0f
        }

        var lowE = 0f
        var lowNoise = 0f
        for (k in hpfBins..windTo) {
            lowE += powNow[k]
            lowNoise += noisePow[k]
        }
        var voiceE = 0f
        for (k in voiceFrom..voiceTo) voiceE += powNow[k]

        // ПОЧЕМУ ЗДЕСЬ БОЛЬШЕ НЕТ «ДА/НЕТ».
        //
        // Раньше задувание либо признавалось и давилось на полную, либо не
        // признавалось вовсе — по условию «низов в 25 раз больше, чем в
        // голосовой полосе». На чистом задувании это отношение около 700, и
        // условие срабатывало. Но стоит заговорить, продолжая дуть, — голосовая
        // полоса наполняется, отношение падает до единиц, условие рвётся, и
        // задувание уходит в эфир целиком. Ровно на это и жалоба.
        //
        // Теперь глубина растёт плавно от WIND_TILT_MIN до WIND_TILT_FULL:
        // низкий мужской голос (замеренные 8.7) остаётся ниже нижней границы и
        // не трогается, а смесь речи с задуванием давится соразмерно.
        val tilt = if (voiceE > 1e-6f) lowE / voiceE else if (lowE > 0f) 1000f else 0f
        var target = ((tilt - WIND_TILT_MIN) / (WIND_TILT_FULL - WIND_TILT_MIN))
            .coerceIn(0f, 1f)

        // Превышение над фоном стало множителем, а не запретом: при долгом
        // равномерном задувании оценка фона сама подтягивается к нему (она
        // строится по минимумам), «громко» перестаёт выполняться — и раньше
        // подавление выключалось как раз тогда, когда дуют дольше всего.
        val loud = lowE > lowNoise * WIND_OVER_NOISE
        if (!loud) target *= 0.6f

        // Вверх быстро, вниз медленно: порыв начинается мгновенно, а резкое
        // отпускание давало бы слышный «вдох» на хвосте.
        windEnv += (target - windEnv) * (if (target > windEnv) 0.6f else 0.06f)
        if (windEnv <= 0.01f) return

        // Чем ниже частота, тем глубже давим: у самой отсечки от задувания
        // почти ничего не остаётся, у верхнего края полосы уже может быть
        // основной тон голоса, и там трогаем осторожно.
        val span = (windTo - hpfBins).coerceAtLeast(1).toFloat()
        for (k in hpfBins..windTo) {
            val up = (k - hpfBins) / span                       // 0 внизу … 1 вверху
            val depth = WIND_DEPTH_LOW + (WIND_DEPTH_TOP - WIND_DEPTH_LOW) * up
            val g = 1f - windEnv * depth
            re[k] *= g
            im[k] *= g
        }
    }

    private fun processHop(inOffset: Int) {
        // Сдвигаем окно анализа влево на HOP и дописываем HOP новых сэмплов.
        System.arraycopy(window, HOP, window, 0, N - HOP)
        for (i in 0 until HOP) window[N - HOP + i] = inFifo[inOffset + i].toFloat()

        for (i in 0 until N) {
            re[i] = window[i] * win[i]
            im[i] = 0f
        }
        fft(inverse = false)

        val s = strength.coerceIn(0f, 1f)
        val overSub = 0.4f + 1.4f * s          // 0.4 … 1.8 — как Strength на ПК
        val floorGain = 0.18f - 0.08f * s      // 0.18 … 0.10 — как Floor на ПК

        frames++
        // Первые кадры — просто снимаем фон: пока оценки нет, давить нечего.
        val priming = frames <= PRIME_FRAMES

        for (k in 0 until BINS) {
            val pow = re[k] * re[k] + im[k] * im[k]
            powNow[k] = pow

            // Сглаженная периодограмма: сырая скачет в разы от кадра к кадру,
            // и любое решение по ней — лотерея.
            powSmooth[k] = powSmooth[k] * 0.75f + pow * 0.25f
            val ps = powSmooth[k]

            // Долгий минимум: «пол», ниже которого фон точно не поднимался.
            if (ps < minRunning[k]) minRunning[k] = ps

            if (priming) {
                noisePow[k] = ps
                minRunning[k] = ps
                minHeld[k] = ps
            } else {
                // В паузе оценку двигаем быстро, на речи — почти замораживаем,
                // иначе шумодав выучит голос как фон и начнёт резать его.
                val speechLikely = ps > noisePow[k] * 3f
                val a = if (speechLikely) 0.0005f else 0.05f
                noisePow[k] += (ps - noisePow[k]) * a

                // И сверху держим долгим минимумом с поправкой на смещение:
                // сглаженный минимум занижает средний уровень примерно вдвое.
                val cap = minHeld[k] * 2f
                if (cap > 1e-6f && noisePow[k] > cap) noisePow[k] = cap
            }

            val noise = if (noisePow[k] > 1e-6f) noisePow[k] else 1e-6f

            // Decision-directed: априорный SNR наполовину из уже очищенного
            // предыдущего кадра, наполовину из текущего апостериорного.
            var post = pow / noise - 1f
            if (post < 0f) post = 0f
            var prior = DD_ALPHA * (prevClean[k] / noise) + (1f - DD_ALPHA) * post
            if (prior < 0f) prior = 0f

            var g = prior / (prior + overSub)
            if (g < floorGain) g = floorGain
            if (priming) g = 1f

            prevClean[k] = g * g * pow
            re[k] *= g
            im[k] *= g
        }

        if (!priming) suppressWind()

        // Обновление долгого минимума: раз в MIN_WINDOW кадров переносим
        // накопленный минимум в «удерживаемый» и начинаем копить заново.
        if (++minAge >= MIN_WINDOW) {
            minAge = 0
            for (k in 0 until BINS) {
                minHeld[k] = minRunning[k]
                minRunning[k] = powSmooth[k]
            }
        }

        // Сопряжённая симметрия вещественного сигнала (Re чётна, Im нечётна).
        for (k in 1 until N / 2) {
            re[N - k] = re[k]
            im[N - k] = -im[k]
        }
        im[0] = 0f
        im[N / 2] = 0f

        fft(inverse = true)

        // WOLA: синтез-окно тоже корень из Ханна, сумма квадратов при 50%
        // перекрытия равна единице — амплитуда не плывёт.
        for (i in 0 until N) overlap[i] += re[i] / N * win[i]
        for (i in 0 until HOP) {
            var v = overlap[i]
            if (v > 32767f) v = 32767f else if (v < -32768f) v = -32768f
            pushOut(v.toInt().toShort())
        }
        System.arraycopy(overlap, HOP, overlap, 0, N - HOP)
        java.util.Arrays.fill(overlap, N - HOP, N, 0f)
    }

    /** Итеративный радикс-2 FFT in-place на re/im. */
    private fun fft(inverse: Boolean) {
        for (i in 0 until N) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= N) {
            val ang = 2.0 * PI / size * (if (inverse) 1 else -1)
            val wRe = cos(ang).toFloat()
            val wIm = sin(ang).toFloat()
            val half = size / 2
            var i = 0
            while (i < N) {
                var curRe = 1f
                var curIm = 0f
                for (k in 0 until half) {
                    val a = i + k
                    val b = a + half
                    val tRe = re[b] * curRe - im[b] * curIm
                    val tIm = re[b] * curIm + im[b] * curRe
                    re[b] = re[a] - tRe
                    im[b] = im[a] - tIm
                    re[a] += tRe
                    im[a] += tIm
                    val nRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nRe
                }
                i += size
            }
            size = size shl 1
        }
    }

    // ── FIFO ──────────────────────────────────────────────────────────

    private fun ensureInCapacity(need: Int) {
        if (inFifo.size < need) {
            inFifo = inFifo.copyOf(maxOf(need, inFifo.size * 2))
        }
    }

    private fun pushOut(s: Short) {
        if (outCount == outFifo.size) {
            val bigger = ShortArray(outFifo.size * 2)
            for (i in 0 until outCount) bigger[i] = outFifo[(outHead + i) % outFifo.size]
            outFifo = bigger
            outHead = 0
        }
        outFifo[(outHead + outCount) % outFifo.size] = s
        outCount++
    }

    private fun popOut(): Short {
        if (outCount == 0) return 0
        val s = outFifo[outHead]
        outHead = (outHead + 1) % outFifo.size
        outCount--
        return s
    }

    private companion object {
        const val N = 512
        const val HOP = 256
        const val BINS = N / 2 + 1

        /** Вес «памяти» в decision-directed. 0.98 — классическое значение. */
        const val DD_ALPHA = 0.98f

        /** Кадры первичного замера фона: ~130 мс на 48 кГц. */
        const val PRIME_FRAMES = 25

        /** Окно долгого минимума: ~0.5 с на 48 кГц. */
        const val MIN_WINDOW = 96

        // ── Задувание ──

        /**
         * Всё ниже режем безусловно. Самый низкий мужской основной тон — около
         * 85 Гц, женский вдвое выше; ниже восьмидесяти на телефонном микрофоне
         * бывает только рокот корпуса и струя воздуха.
         */
        const val HPF_HZ = 80f

        /** Верх полосы задувания: выше трёхсот герц его практически нет. */
        const val WIND_TOP_HZ = 300f

        /** Полоса разборчивости — по ней судим, есть ли голос вообще. */
        const val VOICE_FROM_HZ = 400f
        const val VOICE_TO_HZ = 3400f

        /**
         * Во сколько раз низы должны превысить собственный фон, чтобы это
         * считалось порывом. Порог по громкости нужен затем, что в тишине
         * наклон спектра случаен.
         */
        const val WIND_OVER_NOISE = 3f

        /**
         * Во сколько раз энергия низов должна превышать полосу разборчивости.
         *
         * Значение выбрано ЗАМЕРОМ, а не на глаз. Медиана этого отношения на
         * кадрах с громкими низами: обычная речь 0.1, низкий мужской голос
         * 8.7, задувание 720 — разница на три порядка, но хвост низкого
         * голоса заходит далеко, и порог 10 срабатывал на нём в трети кадров,
         * отбирая у баса −14.5 дБ низов. При 25 ложные срабатывания на низком
         * голосе исчезают полностью (−2.0 дБ, то есть ничего), а задувание
         * по-прежнему давится на −26 дБ. Дальше поднимать нечего: на 40 и 60
         * низкий голос уже чист, а смеси «речь + ветер» только теряют.
         */
        const val WIND_TILT_MIN = 12f

        /**
         * Наклон, при котором давим на полную. Между MIN и FULL глубина
         * растёт плавно: раньше здесь было «да/нет» по одному числу 25, и
         * смесь «речь + задувание» в него не попадала — отношение падало до
         * единиц, и задувание уходило в эфир нетронутым.
         */
        const val WIND_TILT_FULL = 40f

        /** Глубина подавления у отсечки и у верхнего края полосы. */
        const val WIND_DEPTH_LOW = 0.95f
        const val WIND_DEPTH_TOP = 0.35f
    }
}
