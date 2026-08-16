package com.pismo.messenger.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Контейнер видео-кружочков PSMOVID1 — побайтовый порт VideoCircleCodec.cs.
 * Формат обязан совпадать, иначе кружочки с ПК не откроются и наоборот.
 *
 *   8 байт  — magic "PSMOVID1"
 *   4 байта — Int32 длина блока аудио (WAV)
 *   4 байта — Int32 количество кадров
 *   4 байта — Int32 FPS
 *   ...     — блок аудио (WAV, может быть пустым)
 *   на каждый кадр:
 *     4 байта — Int32 длина JPEG
 *     ...     — JPEG
 *
 * Порядок байт — little-endian: BinaryWriter в .NET пишет именно так,
 * а ByteBuffer в Java по умолчанию big-endian, отсюда явный order().
 */
object VideoCircleCodec {

    private val MAGIC = "PSMOVID1".toByteArray(Charsets.US_ASCII)
    private const val JPEG_QUALITY = 70

    class DecodedVideo(
        val frames: List<Bitmap>,
        val audioWav: ByteArray,
        val fps: Int,
    )

    fun encode(frames: List<Bitmap>, wavAudio: ByteArray?, fps: Int): ByteArray {
        val audio = wavAudio ?: ByteArray(0)
        val out = ByteArrayOutputStream()

        out.write(MAGIC)
        out.write(int32(audio.size))
        out.write(int32(frames.size))
        out.write(int32(fps))
        out.write(audio)

        for (frame in frames) {
            val jpeg = ByteArrayOutputStream().also {
                frame.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
            }.toByteArray()
            out.write(int32(jpeg.size))
            out.write(jpeg)
        }
        return out.toByteArray()
    }

    fun decode(data: ByteArray): DecodedVideo {
        require(data.size > 20) { "Слишком короткий блок видео-кружочка." }
        for (i in MAGIC.indices) {
            require(data[i] == MAGIC[i]) { "Неизвестный формат видео-кружочка." }
        }

        var pos = MAGIC.size
        val audioLen = readInt32(data, pos); pos += 4
        val frameCount = readInt32(data, pos); pos += 4
        val fps = readInt32(data, pos); pos += 4

        val audio = if (audioLen > 0 && pos + audioLen <= data.size) {
            data.copyOfRange(pos, pos + audioLen).also { pos += audioLen }
        } else ByteArray(0)

        val frames = ArrayList<Bitmap>(frameCount.coerceAtMost(600))
        repeat(frameCount) {
            if (pos + 4 > data.size) return@repeat
            val len = readInt32(data, pos); pos += 4
            if (len <= 0 || pos + len > data.size) return@repeat
            BitmapFactory.decodeByteArray(data, pos, len)?.let { frames.add(it) }
            pos += len
        }

        return DecodedVideo(frames, audio, if (fps > 0) fps else 10)
    }

    /** Первый кадр — превью в пузыре без раскодирования всего кружочка. */
    fun decodeFirstFrame(data: ByteArray): Bitmap? = runCatching {
        var pos = MAGIC.size
        val audioLen = readInt32(data, pos); pos += 12   // audioLen + frameCount + fps
        pos += audioLen
        val len = readInt32(data, pos); pos += 4
        BitmapFactory.decodeByteArray(data, pos, len)
    }.getOrNull()

    fun isVideoCircle(data: ByteArray?): Boolean {
        if (data == null || data.size < MAGIC.size) return false
        for (i in MAGIC.indices) if (data[i] != MAGIC[i]) return false
        return true
    }

    private fun int32(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    private fun readInt32(data: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
}
