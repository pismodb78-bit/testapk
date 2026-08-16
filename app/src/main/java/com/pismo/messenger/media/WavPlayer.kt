package com.pismo.messenger.media

import android.media.AudioAttributes
import android.media.MediaPlayer
import com.pismo.messenger.PismoApp
import java.io.File

/**
 * Проигрывание голосовых сообщений. Одновременно звучит только одно —
 * повторный тап по играющему сообщению останавливает его, как на ПК.
 */
object WavPlayer {

    private var player: MediaPlayer? = null

    /** id сообщения, которое сейчас играет (для подсветки кнопки). */
    @Volatile var playingId: Int = -1
        private set

    private var onStateChange: ((Int) -> Unit)? = null

    fun setListener(listener: ((Int) -> Unit)?) {
        onStateChange = listener
    }

    /** Тумблер: играет это же — остановит, иначе начнёт заново. */
    fun toggle(messageId: Int, wav: ByteArray) {
        if (playingId == messageId) {
            stop()
            return
        }
        stop()

        runCatching {
            val file = File(PismoApp.appContext.cacheDir, "voice_play.wav")
            file.writeBytes(wav)

            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener { stop() }
                setOnErrorListener { _, _, _ -> stop(); true }
                prepare()
                start()
            }
            playingId = messageId
            onStateChange?.invoke(playingId)
        }.onFailure { stop() }
    }

    fun stop() {
        runCatching {
            player?.stop()
            player?.release()
        }
        player = null
        playingId = -1
        onStateChange?.invoke(-1)
    }

    val positionMs: Int get() = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
    val durationMs: Int get() = runCatching { player?.duration ?: 0 }.getOrDefault(0)

    /** Длительность WAV по шапке — без раскодирования, для подписи в пузыре. */
    fun durationSecondsOf(wav: ByteArray): Int {
        if (wav.size < 44) return 0
        val dataSize = wav.size - 44
        val byteRate = WavRecorder.SAMPLE_RATE * 2   // моно, 16 бит
        return (dataSize / byteRate).coerceAtLeast(0)
    }
}
