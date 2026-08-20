package com.pismo.messenger.call

import java.nio.ByteBuffer
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Вся обработка микрофона — порт цепочки NativeCallTransport из ПК-версии.
 *
 * ЧТО ЗДЕСЬ БЫЛО РАНЬШЕ И ПОЧЕМУ ЭТО ЗАМЕНЕНО.
 * Первый андроидный шумодав был портом СТАРОГО MicDenoiser.cs: фильтр + гейт
 * по шумовому полу + де-кликер, и «сила» просто задирала пороги гейта. Отсюда
 * ровно то, на что жалоба: чем выше процент, тем сильнее рвётся голос —
 * гейт откусывал начала и хвосты слов, а клики клавиатуры всё равно
 * проходили, потому что по громкости они выше речи. На ПК этот путь давно
 * пройден, и в коде оставлен прямой вывод: наслоение гейта/HPF поверх
 * шумодава давало «рацию из Второй мировой», поэтому его убрали.
 *
 * Текущая цепочка ПК: порог активации → RNNoise → транзиент-лимитер →
 * makeup-усиление. RNNoise — нативная библиотека, на Android её нет и собрать
 * её здесь нечем, поэтому её место занимает спектральный винеровский шумодав
 * (та же вторая ветка, что и на ПК, когда RNNoise не поднялся, — но доведённая
 * до рабочего состояния, подробности в SpectralDenoiser). Он давит постоянный
 * фон по частотам, а не по громкости, поэтому голос остаётся целым даже на
 * максимуме силы.
 *
 * ОТЛИЧИЕ ОТ ПК, сознательное: транзиент-лимитер оставлен и на спектральной
 * ветке (на ПК он идёт только за RNNoise). Клики клавиатуры и мыши —
 * основная жалоба, спектральный фильтр их не берёт в принципе (щелчок
 * широкополосный и короткий, для фильтра он выглядит полезным сигналом), а
 * лимитер сделан именно под них и речь не трогает.
 *
 * ПОРЯДОК:
 *   1) замер уровня СЫРОГО блока — по нему судит порог активации;
 *   2) спектральный шумодав;
 *   3) транзиент-лимитер;
 *   4) makeup-усиление (шумодав приглушает голос — здесь добираем);
 *   5) усиление порога активации: тише порога — тишина.
 *
 * Порог измеряется на сыром сигнале, как на ПК (там он вообще стоит первым),
 * но ПРИМЕНЯЕТСЯ последним. Если занулять вход шумодава, тот выучит нулевой
 * фон, и после каждой паузы шум секунду лез бы обратно, пока оценка
 * восстанавливается. Смысл настройки при этом не меняется: звук тише порога
 * в эфир не уходит.
 */
class MicProcessor(sampleRate: Int) {

    /**
     * Сила шумоподавления 0..1 — тот же ползунок, что NoiseSuppressionStrength
     * (0..100 %) на ПК. Регулируется на лету.
     */
    @Volatile
    var strength: Float = 1f

    /**
     * Автоопределение чувствительности (как в Discord): true — звук
     * передаётся всегда, порог не действует. Порт VoiceAutoSensitivity.
     */
    @Volatile
    var voiceGateAuto: Boolean = false

    /**
     * Ручной порог активации голоса в дБFS, −60..0. Звук тише порога не
     * передаётся. Порт VoiceThreshold; действует при voiceGateAuto = false.
     */
    @Volatile
    var voiceThresholdDb: Int = -40

    /**
     * Усиление на ВХОДЕ цепочки — порт MicrophoneGain из devices.ini.
     *
     * На ПК его ставит сам транспорт (SetMicGain), то есть ДО всякой
     * обработки, и меняется оно прямо в разговоре. Здесь так же — и по той же
     * причине: тихий микрофон надо поднять раньше, чем его увидят порог
     * активации и шумодав. Если поднимать после, гейт успеет посчитать речь
     * фоном и вырезать её, а шумодав будет чистить сигнал, которого почти нет.
     *
     * Этим он и отличается от outputGainPercent: тот добирает громкость уже
     * ПОСЛЕ чистки и на решения цепочки не влияет.
     */
    @Volatile
    var inputGain: Float = 1f

    /**
     * Makeup-усиление на выходе цепи, 0..300 %. Порт VoiceOutputGain: шумодав
     * неизбежно приглушает голос, и этим ползунком громкость добирают обратно.
     */
    @Volatile
    var outputGainPercent: Int = 100

    /**
     * Уровень последнего блока в дБFS — ровно та величина, с которой
     * сравнивается порог активации.
     *
     * Нужна шкале в настройках. Своим микрофоном она мерить не может: во
     * время разговора он занят, а вне разговора это ДРУГОЙ поток с другой
     * обработкой — отсюда и расхождение, когда шкала показывала −54 дБ, а
     * порог −20 уже начинал резать речь.
     */
    @Volatile
    var lastLevelDb: Float = -100f
        private set

    private var sr = sampleRate
    private var spectral = SpectralDenoiser(sampleRate)
    private var limiter = TransientLimiter(sampleRate)

    /** Сколько сэмплов ещё держать гейт открытым после падения ниже порога. */
    private var gateHangSamples = 0
    private var gateGain = 1f

    /**
     * Обрабатывает блок 16-битного PCM на месте.
     *
     * [bytesRead] — сколько байт реально занято; хвост буфера не трогаем.
     * [channelCount] и [sampleRate] приходят из WebRTC: устройство может
     * открыть микрофон не на 48 кГц, и линия задержки лимитера должна быть
     * пересчитана, иначе упреждение перестанет быть пятимиллисекундным.
     */
    fun process(buffer: ByteBuffer, bytesRead: Int, channelCount: Int, sampleRate: Int) {
        val n = bytesRead / 2
        if (n <= 0) return

        if (sampleRate > 0 && sampleRate != sr) {
            sr = sampleRate
            spectral = SpectralDenoiser(sampleRate)
            limiter = TransientLimiter(sampleRate)
        }

        // ── 0) Усиление на входе ──
        //
        // Строго до замера уровня: порог активации обязан судить по тому же
        // сигналу, который уйдёт в эфир.
        val ig = inputGain.coerceIn(0.1f, 4f)
        if (ig < 0.99f || ig > 1.01f) {
            for (i in 0 until n) {
                val idx = i * 2
                writeShort(buffer, idx, softGain(readShort(buffer, idx), ig))
            }
        }

        // ── 1) Уровень блока в дБFS ──
        var sum = 0.0
        for (i in 0 until n) {
            val s = readShort(buffer, i * 2).toDouble()
            sum += s * s
        }
        val rms = sqrt(sum / n)
        val db = if (rms > 1.0) 20.0 * log10(rms / 32768.0) else -100.0
        lastLevelDb = db.toFloat()

        val f = strength.coerceIn(0f, 1f)

        // ── 2) Спектральный шумодав ──
        //
        // STFT рассчитан на моно. Стерео-микрофон отдал бы перемежённые
        // отсчёты, и фильтр считал бы их одним сигналом — это не «хуже
        // почистит», это каша. WebRTC на Android пишет моно, но если
        // устройство отдало два канала, стадию честно пропускаем: гейт,
        // лимитер и усиление на перемежённом потоке работают корректно.
        if (f > 0f && channelCount <= 1) {
            spectral.strength = f
            spectral.process(buffer, bytesRead)
        }

        // ── 3) Транзиент-лимитер: клавиатура, мышь, стуки ──
        //
        // ЧЕСТНО ПРО ГРАНИЦЫ. Он ловит НАСТОЯЩИЕ импульсы — стук по столу,
        // щелчок мыши, удар по корпусу. Клавиатуру берёт хуже: замер показал,
        // что щелчок клавиши это не импульс, а резонанс корпуса длиной 10–15 мс,
        // и по времени нарастания он неотличим от слога. Переносить лимитер
        // вперёд шумодава пробовали — не помогло (замер: 67% против 74%
        // оставшейся амплитуды, то есть стало даже хуже), поэтому он остался
        // на прежнем месте.
        if (f > 0f) {
            limiter.strength = 0.5f + f * 0.5f           // 0.5 … 1.0
            limiter.process(buffer, bytesRead)
        }

        // ── 4) Порог активации: открыт/закрыт + удержание ──
        //
        // Удержание 150 мс, как VoiceGateHangFrames на ПК: без него гейт
        // захлопывался бы между слогами.
        val open = voiceGateAuto || db >= voiceThresholdDb
        if (open) {
            gateHangSamples = sr * HANG_MS / 1000
        } else if (gateHangSamples > 0) {
            gateHangSamples = (gateHangSamples - n).coerceAtLeast(0)
        }
        val gateTarget = if (voiceGateAuto || gateHangSamples > 0) 1f else 0f

        // ── 5) Makeup-усиление и гейт одним проходом ──
        val g = (outputGainPercent.coerceIn(0, 300)) / 100f
        val flat = gateTarget == 1f && gateGain > 0.999f && g > 0.99f && g < 1.01f
        if (flat) return

        for (i in 0 until n) {
            // Плавное закрытие: ПК режет паузу «снапово», но там кадр ровно
            // 10 мс и обрыв приходится на тишину. Здесь длина блока не
            // фиксирована, и мгновенный ноль посреди блока даёт щелчок.
            gateGain += (gateTarget - gateGain) * (if (gateTarget > gateGain) 0.4f else 0.08f)

            val idx = i * 2
            writeShort(buffer, idx, softGain(readShort(buffer, idx), g) * gateGain)
        }
    }

    /**
     * Makeup-усиление одного отсчёта — порт SoftGainSample.
     *
     * ЛИНЕЙНОЕ усиление до 0.9 полной шкалы (громкость растёт по-настоящему,
     * 300 % ≈ ×3), мягкое ограничение только у самого потолка. Жёсткий клип
     * на его месте громкости не добавлял бы — он только срезает пики, из-за
     * чего ползунок и казался бы бесполезным.
     */
    private fun softGain(sample: Short, g: Float): Float {
        if (g > 0.99f && g < 1.01f) return sample.toFloat()
        var x = sample * g / 32768f
        var a = if (x < 0f) -x else x
        if (a > 0.9f) {
            a = 0.9f + 0.1f * tanh((a - 0.9f) / 0.1f)
            x = if (x < 0f) -a else a
        }
        return x * 32767f
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

    private companion object {
        const val HANG_MS = 150
    }
}
