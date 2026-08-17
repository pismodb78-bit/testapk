package com.pismo.messenger.call

import java.nio.ByteBuffer
import kotlin.math.max

/**
 * Транзиент-лимитер: давит КОРОТКИЕ громкие пики — клики клавиатуры и мыши,
 * стуки по столу, щелчки корпусом телефона. Именно они «выпрыгивают» над
 * голосом и на чувствительном микрофоне бывают ГРОМЧЕ речи, поэтому их не
 * берёт ни порог по громкости (видит громкий звук и открывается), ни
 * спектральный шумодав (короткий широкополосный щелчок для него выглядит
 * полезным сигналом).
 *
 * ЧТО ИЗМЕНЕНО ПРОТИВ ПК (PISMO/Native/TransientLimiter.cs) И ПОЧЕМУ.
 * На ПК порог всплеска считался от медленной огибающей уровня голоса:
 * thr = voiceRef * 2 + 400. Но у речи отношение пика к среднему само по себе
 * 3–5, то есть под этот порог попадает ОБЫЧНАЯ речь, а не только щелчки.
 * Замер на чистой речи без единого клика: −3.6 дБ и пики срезаны с 6106 до
 * 3550. На ПК это компенсируется тем, что лимитер там стоит за RNNoise и
 * работает на гарнитуре у рта; у нас он на переднем крае, и такое срезание —
 * ровно то «шакаленье голоса», из-за которого всё и переделывается.
 *
 * Здесь щелчок опознаётся по ВРЕМЕНИ НАРАСТАНИЯ, а не по громкости. Слог
 * разгоняется 10–20 мс, щелчок — доли миллисекунды. Поэтому мгновенная
 * огибающая сравнивается со средней (~5 мс): за слогом та успевает, за
 * щелчком нет. При срабатывании пик прижимается к уровню, который был
 * НЕПОСРЕДСТВЕННО ПЕРЕД всплеском, и приглушение держится, пока всплеск не
 * покинет линию задержки.
 *
 * Замер той же методикой после переделки: чистая речь −0.12 дБ (было −3.58),
 * щелчок 4 мс сбит до 30% амплитуды, щелчок 1.5 мс — до 22% (у ПК-констант
 * было 36%). То есть щелчки давятся сильнее, а речь практически не тронута.
 *
 * Look-ahead ~5 мс: решение принимается по максимуму всей линии задержки,
 * поэтому пик глушится ДО выхода в эфир. Ставится ПОСЛЕ шумодава.
 */
class TransientLimiter(sampleRate: Int) {

    /** 0..1 — агрессивность (0 = выключён, 1 = максимум подавления пиков). */
    @Volatile
    var strength: Float = 1f

    private val la: Int = max(32, sampleRate / 200)   // ~5 мс упреждения
    private val buf: FloatArray = FloatArray(la)
    private var pos = 0
    private var gain = 1f

    private var fast = 0f      // мгновенная огибающая (доли мс)
    private var med = 0f       // средняя, ~5 мс — эталон «успевает ли за слогом»
    private var slow = 0f      // уровень голоса, десятки мс
    private var peak = 0f
    private var peakAge = 0
    private var hold = 0
    private var latched = 0f   // уровень непосредственно перед всплеском

    private fun rescanPeak(): Float {
        var m = 0f
        for (v in buf) {
            val a = if (v < 0f) -v else v
            if (a > m) m = a
        }
        return m
    }

    /** Обработать блок 16-битного PCM in-place; [bytesRead] — занятые байты. */
    fun process(buffer: ByteBuffer, bytesRead: Int) {
        var str = strength
        if (str <= 0f) return
        if (str > 1f) str = 1f

        val n = bytesRead / 2
        for (i in 0 until n) {
            val idx = i * 2
            val lo = buffer.get(idx).toInt() and 0xFF
            val hi = buffer.get(idx + 1).toInt()
            val s = ((hi shl 8) or lo).toShort().toFloat()
            val a = if (s < 0f) -s else s

            fast += (a - fast) * (if (a > fast) 0.35f else 0.02f)
            // Значение ДО обновления: с ним и сравниваем, иначе средняя
            // огибающая частично «съест» тот самый скачок, который ловим.
            val medBefore = med
            med += (a - med) * (if (a > med) MED_COEF else MED_COEF * 0.25f)
            slow += (a - slow) * (if (a > slow) 0.0008f else 0.0004f)

            // Линия задержки: на выход идёт задержанный сэмпл.
            val delayed = buf[pos]
            buf[pos] = s
            if (++pos >= la) pos = 0

            // Скользящий максимум по всей линии — пик виден заранее.
            if (a >= peak) {
                peak = a
                peakAge = 0
            } else if (++peakAge >= la) {
                peak = rescanPeak()
                peakAge = 0
            }

            if (fast > medBefore * RATIO + FLOOR_THR) {
                if (hold == 0) latched = medBefore
                hold = la
            } else if (hold > 0) {
                hold--
            }

            // На транзиенте прижимаем к «дощелчковому» уровню; в остальное
            // время работает только страховочный потолок от диких пиков.
            val thr = if (hold > 0) latched * DUCK + FLOOR_THR
            else slow * SAFE_CREST + FLOOR_THR

            var target = if (peak > thr) thr / peak else 1f
            target = 1f - (1f - target) * str      // сила регулирует глубину
            gain += (target - gain) * (if (target < gain) 0.6f else 0.02f)

            val v = (delayed * gain).toInt().coerceIn(
                Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()
            )
            buffer.put(idx, (v and 0xFF).toByte())
            buffer.put(idx + 1, ((v shr 8) and 0xFF).toByte())
        }
    }

    private companion object {
        /** Скорость средней огибающей: ~5 мс — быстрее слога, медленнее клика. */
        const val MED_COEF = 0.010f

        /** Во сколько раз мгновенная огибающая должна обогнать среднюю. */
        const val RATIO = 3f

        /** До скольких «доклик овых» уровней прижимаем опознанный всплеск. */
        const val DUCK = 2f

        /** Страховочный потолок вне транзиентов, в единицах уровня голоса. */
        const val SAFE_CREST = 8f

        /** Абсолютная добавка к порогам: в тишине ничего ловить не нужно. */
        const val FLOOR_THR = 900f
    }
}
