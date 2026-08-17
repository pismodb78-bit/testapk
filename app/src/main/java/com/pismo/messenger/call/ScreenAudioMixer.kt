package com.pismo.messenger.call

import android.os.Build
import androidx.annotation.RequiresApi
import io.livekit.android.audio.AudioBufferCallback
import io.livekit.android.audio.ScreenAudioCapturer
import java.nio.ByteBuffer

/**
 * Звук демонстрации экрана, подмешанный в микрофонную дорожку.
 *
 * ПОЧЕМУ ВООБЩЕ ОБЁРТКА, а не голый ScreenAudioCapturer из SDK.
 *
 * На Android нельзя опубликовать вторую независимую аудиодорожку: у WebRTC
 * один AudioDeviceModule на процесс, все локальные аудиотреки читают из него
 * же. Поэтому звук демки едет ВНУТРИ микрофонного трека — так это устроено и
 * в самом SDK (MixerAudioBufferCallback). Отсюда неприятное следствие:
 * `setMicrophoneEnabled(false)` глушит дорожку целиком, и вместе с
 * микрофоном молча пропадает звук демонстрации.
 *
 * Обёртка разводит эти два «мьюта»: буфер микрофона обнуляется здесь,
 * программно, а дорожка остаётся живой, и подмешанный звук демки продолжает
 * идти. MixerAudioBufferCallback.onBuffer объявлен final, переопределить его
 * нельзя — поэтому именно обёртка с делегированием, а не наследник.
 *
 * Буфер читается микшером абсолютными индексами от нуля до capacity(),
 * поэтому и обнуляем ровно этот диапазон, не трогая position.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class ScreenAudioMixer(private val capturer: ScreenAudioCapturer) : AudioBufferCallback {

    /** true — микрофон замьючен пользователем; звук демки при этом остаётся. */
    @Volatile
    var micMuted: Boolean = false

    /** Громкость демки в исходящем миксе (ПК-версия шлёт её отдельной дорожкой). */
    var gain: Float
        get() = capturer.gain
        set(value) {
            capturer.gain = value
        }

    override fun onBuffer(
        buffer: ByteBuffer,
        audioFormat: Int,
        channelCount: Int,
        sampleRate: Int,
        bytesRead: Int,
        captureTimeNs: Long,
    ): Long {
        if (micMuted) {
            val size = buffer.capacity()
            var i = 0
            while (i < size) {
                buffer.put(i, 0)
                i++
            }
        }
        return capturer.onBuffer(
            buffer, audioFormat, channelCount, sampleRate, bytesRead, captureTimeNs
        )
    }

    /**
     * Освобождает AudioRecord захвата. SDK этого сам не делает — забудешь
     * вызвать, и запись системного звука продолжится после конца демки.
     */
    fun release() {
        runCatching { capturer.releaseAudioResources() }
    }
}
