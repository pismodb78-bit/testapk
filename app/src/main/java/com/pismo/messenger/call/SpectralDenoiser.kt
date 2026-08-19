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
class SpectralDenoiser {

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
    private val powSmooth = FloatArray(BINS)
    private val minRunning = FloatArray(BINS)
    private val minHeld = FloatArray(BINS)
    private val prevClean = FloatArray(BINS)
    private var minAge = 0
    private var frames = 0

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
    }
}
