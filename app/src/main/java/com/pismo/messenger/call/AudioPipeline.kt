package com.pismo.messenger.call

import io.livekit.android.audio.AudioBufferCallback
import io.livekit.android.audio.MixerAudioBufferCallback
import java.nio.ByteBuffer

/**
 * Единственная точка обработки исходящего звука.
 *
 * Всё, что мы делаем с микрофоном, обязано жить здесь: LiveKit отдаёт ровно
 * один слот setAudioBufferCallback на дорожку, и два независимых обработчика
 * в него просто не помещаются. Раньше слот занимал микшер звука демонстрации,
 * а шумодава не было вовсе.
 *
 * Порядок операций не произвольный:
 *  1) шумодав — чистим микрофон, пока в буфере только он;
 *  2) мьют — обнуляем микрофон уже после чистки;
 *  3) звук демонстрации — подмешиваем последним, чтобы шумодав его не
 *     трогал и мьют микрофона его не убивал.
 *
 * Поменять 1 и 3 местами — значит пропустить музыку из демки через
 * шумовой гейт, который посчитает её фоном и задавит.
 */
class AudioPipeline : AudioBufferCallback {

    /**
     * Обработка микрофона: порог активации, шумодав, лимитер, makeup-усиление.
     * null — вся цепочка выключена в настройках.
     */
    @Volatile
    var mic: MicProcessor? = null

    /** true — микрофон замьючен пользователем; звук демки при этом остаётся. */
    @Volatile
    var micMuted: Boolean = false

    /**
     * Микшер звука демонстрации экрана (ScreenAudioCapturer).
     *
     * Тип namеренно общий, MixerAudioBufferCallback, а не сам
     * ScreenAudioCapturer: тот помечен @RequiresApi(Q), и ссылка на него в
     * поле заставляла бы загружать класс там, где захвата системного звука
     * нет в принципе.
     */
    @Volatile
    var screenMixer: MixerAudioBufferCallback? = null

    override fun onBuffer(
        buffer: ByteBuffer,
        audioFormat: Int,
        channelCount: Int,
        sampleRate: Int,
        bytesRead: Int,
        captureTimeNs: Long,
    ): Long {
        // Цепочка рассчитана на 16-битный PCM; для других форматов WebRTC её
        // просто пропускаем, вместо того чтобы портить буфер.
        if (!micMuted && isPcm16(audioFormat)) {
            mic?.let {
                runCatching { it.process(buffer, bytesRead, channelCount, sampleRate) }
            }
        }

        if (micMuted) zero(buffer)

        val mixer = screenMixer ?: return captureTimeNs
        return mixer.onBuffer(
            buffer, audioFormat, channelCount, sampleRate, bytesRead, captureTimeNs
        )
    }

    /**
     * ENCODING_PCM_16BIT = 2, ENCODING_DEFAULT = 1 — оба означают 16 бит.
     * Константы взяты числами, чтобы класс не зависел от android.media в
     * юнит-проверках.
     */
    private fun isPcm16(audioFormat: Int): Boolean = audioFormat == 2 || audioFormat == 1

    private fun zero(buffer: ByteBuffer) {
        val size = buffer.capacity()
        var i = 0
        while (i < size) {
            buffer.put(i, 0)
            i++
        }
    }
}
