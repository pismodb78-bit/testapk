package com.pismo.messenger.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.pismo.messenger.core.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * Короткие звуки событий — порт Sounds.cs.
 *
 * Ровно те же «бупы», что на ПК, и посчитаны они той же формулой: частота
 * скользит по экспоненте f1 → f2, атака ~6 мс, экспоненциальное затухание,
 * основной тон плюс тихая нижняя октава для теплоты. Вверх — включили,
 * вниз — выключили. Это не украшение: половина кнопок в звонке меняет то,
 * чего на экране не видно (микрофон, «наушники»), и без отклика непонятно,
 * сработало нажатие или нет.
 *
 * Звуки СИНТЕЗИРУЮТСЯ, файлов в ресурсах нет — как на ПК. Заодно они не
 * зависят от системной темы звуков и звучат одинаково на любом телефоне.
 *
 * ОТЛИЧИЕ ОТ ПК: маршрут вывода выбирается по обстановке. Вне разговора —
 * обычный системный тракт. В разговоре — VOICE_COMMUNICATION_SIGNALLING,
 * назначение, придуманное системой ровно для сигналов ПОВЕРХ звонка.
 *
 * ПОЧЕМУ НЕ VOICE_COMMUNICATION, как было сперва. Это назначение открывает
 * полноценный поток голосового тракта, и его создание заставляет систему
 * пересобрать маршрутизацию. Беда в том, что «звонок подключился» звучит
 * ровно в тот момент, когда телефон договаривается с bluetooth-гарнитурой о
 * профиле разговора (SCO): переключение посреди этих переговоров сбивает их,
 * а нестойкие гарнитуры на этом просто отваливаются от телефона.
 * SIGNALLING такого потока не открывает — он для того и заведён.
 */
object Sounds {

    private const val SAMPLE_RATE = 44100

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Готовые дорожки: считаются один раз, дальше только проигрываются. */
    private val cache = HashMap<String, ShortArray>()

    // ── Публичные события (частоты один в один с ПК) ────────────────────

    fun micOn() = play("micOn") { glide(300.0, 560.0, 130, 0.2) }
    fun micOff() = play("micOff") { glide(560.0, 280.0, 150, 0.2) }
    fun cameraOn() = play("camOn") { glide(340.0, 620.0, 130, 0.2) }
    fun cameraOff() = play("camOff") { glide(620.0, 320.0, 150, 0.2) }
    fun screenOn() = play("scrOn") { glide(280.0, 500.0, 130, 0.2) }
    fun screenOff() = play("scrOff") { glide(500.0, 260.0, 150, 0.2) }

    /** «Плип» капли — новое сообщение. */
    fun message() = play("msg") { glide(500.0, 880.0, 90, 0.15) }

    /** «Бу-бум» вниз — трубка положена. */
    fun hangup() = play("hangup") { doubleGlide(450.0, 260.0, 300.0, 170.0, 140, 0.2) }

    /**
     * Дважды вверх — связь установлена.
     *
     * С задержкой: подключение к комнате и договор телефона с гарнитурой о
     * профиле разговора приходятся на один и тот же момент, и лезть туда со
     * своим звуком — верный способ этот договор сорвать. Полторы секунды
     * маршрут успевает устояться, а «связь установлена» ничего не теряет от
     * того, что прозвучит чуть позже.
     */
    fun callConnected() {
        scope.launch {
            delay(1500)
            play("conn") { doubleGlide(300.0, 520.0, 420.0, 660.0, 150, 0.2) }
        }
    }

    fun userJoined() = play("join") { glide(330.0, 640.0, 160, 0.18) }
    fun userLeft() = play("left") { glide(640.0, 300.0, 170, 0.18) }

    // ── Внутреннее ──────────────────────────────────────────────────────

    private fun play(key: String, build: () -> ShortArray) {
        if (!Prefs.soundsEnabled) return
        val pcm = synchronized(cache) { cache.getOrPut(key, build) }
        scope.launch { runCatching { blast(pcm) } }
    }

    private suspend fun blast(pcm: ShortArray) {
        // В разговоре — сигнальным назначением поверх звонка, а не потоком
        // самого голосового тракта: см. заголовок класса.
        val inCall = com.pismo.messenger.call.ActiveCall.engine != null
        val attrs = AudioAttributes.Builder()
            .setUsage(
                if (inCall) AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING
                else AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
            )
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        runCatching {
            track.write(pcm, 0, pcm.size)
            track.play()
            // Освобождаем по длительности: STATIC-дорожка коротка, а держать
            // на неё слушателя ради одного «бупа» дороже самого «бупа».
            delay(pcm.size * 1000L / SAMPLE_RATE + 250L)
        }
        runCatching { track.stop() }
        runCatching { track.release() }
    }

    /** «Буп» со скольжением частоты — порт GlideWav + FillGlide. */
    private fun glide(f1: Double, f2: Double, ms: Int, vol: Double): ShortArray {
        val out = ShortArray((ms + 40) * SAMPLE_RATE / 1000)
        fillGlide(out, f1, f2, 0, ms, vol)
        return out
    }

    /** Два «бупа» подряд с паузой-переливом — порт DoubleGlide. */
    private fun doubleGlide(
        a1: Double, a2: Double, b1: Double, b2: Double, eachMs: Int, vol: Double,
    ): ShortArray {
        val gap = eachMs / 2
        val total = eachMs * 2 + gap + 60
        val out = ShortArray(total * SAMPLE_RATE / 1000)
        fillGlide(out, a1, a2, 0, eachMs, vol)
        fillGlide(out, b1, b2, eachMs + gap, eachMs, vol)
        return out
    }

    /**
     * Фазовое накопление, а не sin(2π·f·t): при скользящей частоте второй
     * способ рвёт фазу на каждом отсчёте и вместо «бупа» получается треск.
     */
    private fun fillGlide(
        out: ShortArray, f1: Double, f2: Double, startMs: Int, durMs: Int, vol: Double,
    ) {
        val start = startMs * SAMPLE_RATE / 1000
        val len = durMs * SAMPLE_RATE / 1000
        if (len <= 0) return
        val attack = SAMPLE_RATE * 6 / 1000
        val decay = 3.0 / len
        var phase = 0.0
        var phaseLow = 0.0
        for (i in 0 until len) {
            val idx = start + i
            if (idx < 0 || idx >= out.size) continue
            val k = i.toDouble() / len
            val f = f1 * (f2 / f1).pow(k)
            phase += 2 * PI * f / SAMPLE_RATE
            phaseLow += PI * f / SAMPLE_RATE
            val env = (if (i < attack) i.toDouble() / attack else 1.0) *
                exp(-decay * (i - attack).coerceAtLeast(0))
            val s = (sin(phase) * 0.8 + sin(phaseLow) * 0.35) * vol * env
            val v = (s * Short.MAX_VALUE).toInt() + out[idx]
            out[idx] = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }
}
