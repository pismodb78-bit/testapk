package com.pismo.messenger.call

import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Программный шумодав микрофона — построчный порт PISMO/Native/MicDenoiser.cs.
 *
 * ЗАЧЕМ ОН ВООБЩЕ НУЖЕН, если у WebRTC есть свой NoiseSuppression.
 * Затем же, зачем он появился на ПК. Комментарий в оригинале прямой:
 * «libwebrtc APM фоновый шум практически не убирает, поэтому чистим PCM
 * сами». Галочка noiseSuppression в LocalAudioTrackOptions включает ровно
 * тот самый APM — то есть сама по себе она почти ничего не даёт, и на
 * Android «шумодав» до сих пор был только на бумаге.
 *
 * Каскады те же, что на ПК, с теми же константами — расходиться в
 * обработке звука двум клиентам одного мессенджера незачем:
 *   1) высокочастотный фильтр (два каскада ~120 Гц) — гул, рокот, задувание;
 *   2) де-кликер и адаптивный шумовой гейт по оценке фона в паузах;
 *   3) look-ahead лимитер — давит щелчки ДО того, как они выйдут в эфир.
 *
 * Работает на 16-битном PCM in-place. Буфер приходит из WebRTC уже
 * в native-порядке байтов, но здесь, как и в оригинале, всё считается
 * явным little-endian: так одинаково на любом устройстве.
 */
class MicDenoiser(sampleRate: Int) {

    /**
     * Жёсткий режим: де-кликер, подтверждение речи и лимитер. Соответствует
     * TransientGuard на ПК. Выключенным остаётся только фильтр и мягкий гейт.
     */
    @Volatile
    var transientGuard: Boolean = true

    /**
     * Сила подавления, 0..1. Влияет на то, насколько высоко над шумовым
     * полом должен подняться звук, чтобы считаться речью.
     *
     * Зачем регулировка. Пороги ПК подобраны под гарнитуру у рта: клики
     * клавиатуры и голос из другой комнаты они пропускают, потому что по
     * громкости те попадают в ту же зону, что и тихая речь. На телефоне
     * микрофон всенаправленный, и это слышно сильнее. Поднимая силу, мы
     * поднимаем порог открытия гейта — дальние источники остаются под ним.
     * Платой идёт собственный тихий голос, поэтому значение отдано
     * пользователю, а не зашито.
     */
    @Volatile
    var strength: Float = 0.5f

    private val sr = sampleRate

    // Высокочастотный фильтр — два однополюсных каскада ~120 Гц. Один полюс
    // задувание почти не срезал: его энергия лежит ниже 150 Гц.
    private var hpPrevIn = 0f
    private var hpPrevOut = 0f
    private var hpPrevIn2 = 0f
    private var hpPrevOut2 = 0f
    private val hpCoef: Float

    private var noiseFloor = 200f
    private var envelope = 0f
    private var gain = 1f

    // Де-кликер: быстрая и медленная огибающие. Клик — это когда быстрая
    // кратно выше медленной.
    private var dcFast = 0f
    private var dcSlow = 0f

    private var loudRun = 0
    private var hangBlocks = 0

    // Линия задержки лимитера — те самые «будущие» отсчёты.
    private val la: Int
    private val laBuf: FloatArray
    private var laPos = 0
    private var laGain = 1f
    private var voiceRef = 0f
    private var laMax = 0f
    private var laMaxAge = 0

    init {
        val rc = 1f / (2f * PI.toFloat() * 120f)
        val dt = 1f / sampleRate
        hpCoef = rc / (rc + dt)
        la = max(64, sampleRate / 200)   // ~5 мс упреждения
        laBuf = FloatArray(la)
    }

    private fun rescanMax(): Float {
        var m = 0f
        for (v in laBuf) {
            val a = abs(v)
            if (a > m) m = a
        }
        return m
    }

    /**
     * Обрабатывает блок 16-битного PCM на месте.
     *
     * [bytesRead] — сколько байт в буфере реально занято; хвост не трогаем.
     */
    fun process(buffer: ByteBuffer, bytesRead: Int) {
        val n = bytesRead / 2
        if (n <= 0) return

        // ── 1) Фильтр, де-кликер и RMS блока ──
        var sumSq = 0.0
        var sumSqRaw = 0.0
        for (i in 0 until n) {
            val idx = i * 2
            val s = readShort(buffer, idx)
            val x = s.toFloat()
            sumSqRaw += x.toDouble() * x

            var y = hpCoef * (hpPrevOut + x - hpPrevIn)
            hpPrevIn = x
            hpPrevOut = y

            val y2 = hpCoef * (hpPrevOut2 + y - hpPrevIn2)
            hpPrevIn2 = y
            hpPrevOut2 = y2
            y = y2

            if (transientGuard) {
                val a = abs(y)
                dcFast += (a - dcFast) * (if (a > dcFast) 0.6f else 0.05f)
                dcSlow += (a - dcSlow) * (if (a > dcSlow) 0.002f else 0.0008f)
                // Порог де-кликера опускаем с силой: на максимуме ловим и
                // негромкие щелчки мыши, которые раньше проходили.
                val k0 = strength.coerceIn(0f, 1f)
                if (dcFast > (350f - 180f * k0) && dcFast > dcSlow * (4f - 1.5f * k0)) {
                    // Тянем пик к устойчивому уровню: голос почти не меняется,
                    // а щелчок срезается даже посреди фразы.
                    var duck = (dcSlow * 1.5f) / (a + 1f)
                    if (duck < 0.12f) duck = 0.12f
                    if (duck < 1f) y *= duck
                }
            }

            writeShort(buffer, idx, y)
            sumSq += y.toDouble() * y
        }

        val rms = sqrt(sumSq / n).toFloat()
        val rmsRaw = sqrt(sumSqRaw / n).toFloat()

        // У выдува почти вся энергия ниже 120 Гц, поэтому после фильтра она
        // обваливается. У голоса отношение близко к единице.
        val blowing = rmsRaw > 1500f && rms < rmsRaw * 0.5f

        // ── 2) Огибающая: быстрый подъём, медленный спад ──
        envelope = if (rms > envelope) rms * 0.5f + envelope * 0.5f
        else rms * 0.05f + envelope * 0.95f

        // ── 3) Шумовой пол учим только в тишине, иначе речь научит гейт
        //       резать саму себя ──
        if (envelope < noiseFloor * 2.5f) {
            noiseFloor = envelope * 0.05f + noiseFloor * 0.95f
        }
        if (noiseFloor < 30f) noiseFloor = 30f

        // ── 4) Целевое усиление ──
        //
        // Пороги растут вместе с силой: на 0 это исходные значения ПК
        // (4.0 и 2.2 шумового пола), на 1 — примерно вдвое выше, и всё,
        // что не громче фона в 8 раз, гейт держит закрытым. Именно сюда
        // попадают клики клавиатуры и голоса из соседней комнаты.
        val k = strength.coerceIn(0f, 1f)
        val openLevel = noiseFloor * (4.0f + 4.0f * k)
        val closeLevel = noiseFloor * (2.2f + 2.0f * k)
        var target = when {
            envelope >= openLevel -> 1f
            envelope <= closeLevel -> 0.008f
            else -> 0.008f + 0.992f * (envelope - closeLevel) / (openLevel - closeLevel)
        }

        if (transientGuard) {
            val msPerBlock = max(1, n * 1000 / sr)
            // Чем выше сила, тем дольше должен держаться громкий звук,
            // прежде чем гейт признает его речью. Клик клавиатуры короткий
            // (единицы миллисекунд) и до этого порога не дотягивает.
            val needMs = (30 + 40 * k).toInt()
            val needBlocks = max(1, needMs / msPerBlock)
            val hangTotal = max(1, 180 / msPerBlock)     // ~180 мс удержания

            if (envelope >= openLevel) loudRun++ else loudRun = 0
            if (loudRun >= needBlocks) hangBlocks = hangTotal
            val speech = hangBlocks > 0
            if (hangBlocks > 0) hangBlocks--

            // Одиночный громкий всплеск без подтверждения речи — это клик.
            if (!speech) target = 0.02f
            // Выдув маскируется под речь сустейном, поэтому давим отдельно и
            // сбрасываем счётчик, чтобы он не открыл гейт.
            if (blowing) {
                target = 0.02f
                loudRun = 0
                hangBlocks = 0
            }
        }

        // ── 5) Сглаживание усиления и лимитер ──
        val coef = if (target > gain) 0.4f else 0.12f
        for (i in 0 until n) {
            gain += (target - gain) * coef
            val idx = i * 2
            val s = readShort(buffer, idx).toFloat()

            val outSample: Float
            if (transientGuard) {
                val a = abs(s)
                voiceRef += (a - voiceRef) * (if (a > voiceRef) 0.0015f else 0.0006f)
                // Порог всплеска для лимитера тоже ужимаем: ниже порог —
                // раньше срабатывает подавление щелчка.
                val thr = voiceRef * (2.5f - 1.0f * k) + (450f - 200f * k)

                val delayed = laBuf[laPos]
                laBuf[laPos] = s
                if (++laPos >= la) laPos = 0

                // Усиление считаем по максимуму ВСЕЙ линии задержки: пик виден
                // заранее и приглушается до выхода в эфир, а приглушение
                // держится, пока пик не покинет буфер.
                if (a >= laMax) {
                    laMax = a
                    laMaxAge = 0
                } else if (++laMaxAge >= la) {
                    laMax = rescanMax()
                    laMaxAge = 0
                }
                val laTarget = if (laMax > thr) thr / laMax else 1f
                laGain += (laTarget - laGain) * (if (laTarget < laGain) 0.6f else 0.01f)

                outSample = delayed * gain * laGain
            } else {
                outSample = s * gain
            }

            writeShort(buffer, idx, outSample)
        }
    }

    private fun readShort(buffer: ByteBuffer, index: Int): Short {
        val lo = buffer.get(index).toInt() and 0xFF
        val hi = buffer.get(index + 1).toInt()
        return ((hi shl 8) or lo).toShort()
    }

    private fun writeShort(buffer: ByteBuffer, index: Int, value: Float) {
        val v = value.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        buffer.put(index, (v and 0xFF).toByte())
        buffer.put(index + 1, ((v shr 8) and 0xFF).toByte())
    }
}
