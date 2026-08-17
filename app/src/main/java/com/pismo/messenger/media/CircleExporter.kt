package com.pismo.messenger.media

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * Сборка видео-кружочка в обычный MP4 — чтобы его можно было сохранить в
 * галерею и открыть чем угодно.
 *
 * Зачем это вообще нужно. Кружочек внутри мессенджера — это контейнер
 * PSMOVID1: JPEG-кадры плюс WAV одним куском (формат общий с ПК, менять его
 * нельзя). Сохранить такой файл «как есть» бессмысленно — открыть его не
 * сможет ничего, кроме самого PISMO. Поэтому при сохранении кадры кодируются
 * в H.264, звук — в AAC, и всё сшивается в MP4.
 *
 * Кодеки берём системные (MediaCodec): на кружочек 240×240 при 10 кадрах в
 * секунду этого с запасом хватает, а тянуть в проект внешний энкодер ради
 * одной кнопки «сохранить» незачем.
 *
 * Обе дорожки сначала кодируются в память и только потом сшиваются. Так
 * заметно проще: MediaMuxer требует, чтобы ВСЕ дорожки были объявлены до
 * start(), а реальный формат дорожки MediaCodec отдаёт только после первых
 * кадров. Трёхминутный кружочек — это порядка двадцати мегабайт, столько
 * подержать в памяти не проблема.
 */
object CircleExporter {

    /** Кадр AAC/H.264 вместе с меткой времени. */
    private class Sample(val data: ByteArray, val ptsUs: Long, val flags: Int)

    /**
     * Кодирует кружочек в [out]. Возвращает false, если устройство не смогло
     * поднять кодеки — вызывающий покажет это как «не удалось сохранить», а
     * не как падение.
     */
    suspend fun toMp4(video: VideoCircleCodec.DecodedVideo, out: File): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { encode(video, out) }.getOrElse {
                runCatching { out.delete() }
                false
            }
        }

    private fun encode(video: VideoCircleCodec.DecodedVideo, out: File): Boolean {
        val frames = video.frames
        if (frames.isEmpty()) return false

        // H.264 требует чётных сторон; кружочки квадратные и кратны 16,
        // но кадр мог прийти и с ПК, где размер задан иначе.
        val w = frames[0].width and 1.inv()
        val h = frames[0].height and 1.inv()
        if (w <= 0 || h <= 0) return false

        val fps = video.fps.coerceIn(1, 60)

        val videoTrack = encodeVideo(frames, w, h, fps) ?: return false
        val audioTrack = if (video.audioWav.isNotEmpty()) encodeAudio(video.audioWav) else null

        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val vIndex = muxer.addTrack(videoTrack.first)
            val aIndex = audioTrack?.let { muxer.addTrack(it.first) } ?: -1
            muxer.start()

            // Пишем в порядке меток времени: муксер складывает несортированные
            // сэмплы в свой буфер, и на длинной записи это лишние мегабайты.
            val info = MediaCodec.BufferInfo()
            var vi = 0
            var ai = 0
            val vList = videoTrack.second
            val aList = audioTrack?.second ?: emptyList()
            while (vi < vList.size || ai < aList.size) {
                val takeVideo = ai >= aList.size ||
                    (vi < vList.size && vList[vi].ptsUs <= aList[ai].ptsUs)
                val s = if (takeVideo) vList[vi++] else aList[ai++]
                info.set(0, s.data.size, s.ptsUs, s.flags)
                muxer.writeSampleData(
                    if (takeVideo) vIndex else aIndex,
                    ByteBuffer.wrap(s.data),
                    info,
                )
            }
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
        }
        return out.length() > 0
    }

    // ── Видео ─────────────────────────────────────────────────────────

    private fun encodeVideo(
        frames: List<Bitmap>,
        w: Int,
        h: Int,
        fps: Int,
    ): Pair<MediaFormat, List<Sample>>? {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            // Flexible, а не конкретный NV12/I420: тогда кадр отдаётся через
            // getInputImage, и о шагах строк заботится сам кодек. С жёстко
            // выбранным форматом на части устройств получалась «каша» из-за
            // выравнивания.
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, (w * h * fps * 0.15).toInt().coerceAtLeast(400_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val samples = ArrayList<Sample>(frames.size + 8)
        var outFormat: MediaFormat? = null
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var frameIndex = 0
            var sawEos = false

            while (!sawEos) {
                if (frameIndex <= frames.size) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        if (frameIndex == frames.size) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, ptsOf(frameIndex, fps),
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            frameIndex++
                        } else {
                            val image = codec.getInputImage(inIndex)
                            if (image == null) {
                                // Кодек не отдал Image — работать с ним вслепую
                                // мы не будем, честнее отказаться.
                                return null
                            }
                            fillYuv(image, frames[frameIndex], w, h)
                            codec.queueInputBuffer(
                                inIndex, 0, w * h * 3 / 2, ptsOf(frameIndex, fps), 0
                            )
                            frameIndex++
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outFormat = codec.outputFormat
                    }
                    outIndex >= 0 -> {
                        val buf = codec.getOutputBuffer(outIndex)
                        // CODEC_CONFIG — это csd, он уже внутри outputFormat;
                        // отдельным сэмплом его писать в MP4 нельзя.
                        if (buf != null && info.size > 0 &&
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            samples.add(readSample(buf, info))
                        }
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) sawEos = true
                        codec.releaseOutputBuffer(outIndex, false)
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }

        val f = outFormat ?: return null
        return if (samples.isEmpty()) null else f to samples
    }

    private fun ptsOf(frameIndex: Int, fps: Int): Long =
        frameIndex.toLong() * 1_000_000L / fps

    /**
     * Bitmap → YUV420 в буфер кодека.
     *
     * Коэффициенты BT.601 — те же, что использует и сам Android при съёмке;
     * с BT.709 картинка уехала бы по цвету. Цветность берём из левого
     * верхнего пикселя каждого блока 2×2: усреднять для кружочка 240×240
     * смысла нет, разница не видна, а времени на кадр уходит вдвое больше.
     */
    private fun fillYuv(image: android.media.Image, frame: Bitmap, w: Int, h: Int) {
        val pixels = IntArray(w * h)
        // Кадр мог быть на пиксель-другой больше после округления сторон.
        frame.getPixels(pixels, 0, w, 0, 0, w, h)

        val planes = image.planes
        val yBuf = planes[0].buffer
        val uBuf = planes[1].buffer
        val vBuf = planes[2].buffer
        val yRow = planes[0].rowStride
        val yPix = planes[0].pixelStride
        val uRow = planes[1].rowStride
        val uPix = planes[1].pixelStride
        val vRow = planes[2].rowStride
        val vPix = planes[2].pixelStride

        for (row in 0 until h) {
            for (col in 0 until w) {
                val p = pixels[row * w + col]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yBuf.put(row * yRow + col * yPix, y.coerceIn(0, 255).toByte())

                if (row and 1 == 0 && col and 1 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    val cRow = row / 2
                    val cCol = col / 2
                    uBuf.put(cRow * uRow + cCol * uPix, u.coerceIn(0, 255).toByte())
                    vBuf.put(cRow * vRow + cCol * vPix, v.coerceIn(0, 255).toByte())
                }
            }
        }
    }

    // ── Звук ──────────────────────────────────────────────────────────

    private fun encodeAudio(wav: ByteArray): Pair<MediaFormat, List<Sample>>? {
        val pcm = WavData.parse(wav) ?: return null
        if (pcm.data.isEmpty()) return null

        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, pcm.sampleRate, pcm.channels
        ).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, if (pcm.channels > 1) 128_000 else 64_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val samples = ArrayList<Sample>()
        var outFormat: MediaFormat? = null
        val bytesPerFrame = 2 * pcm.channels

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var offset = 0
            var queuedFrames = 0L
            var sawEos = false

            while (!sawEos) {
                if (offset <= pcm.data.size) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)
                        val ptsUs = queuedFrames * 1_000_000L / pcm.sampleRate
                        if (buf == null || offset == pcm.data.size) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            offset = pcm.data.size + 1
                        } else {
                            buf.clear()
                            val chunk = minOf(buf.capacity(), pcm.data.size - offset)
                            buf.put(pcm.data, offset, chunk)
                            codec.queueInputBuffer(inIndex, 0, chunk, ptsUs, 0)
                            offset += chunk
                            queuedFrames += chunk / bytesPerFrame
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outFormat = codec.outputFormat
                    }
                    outIndex >= 0 -> {
                        val buf = codec.getOutputBuffer(outIndex)
                        if (buf != null && info.size > 0 &&
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            samples.add(readSample(buf, info))
                        }
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) sawEos = true
                        codec.releaseOutputBuffer(outIndex, false)
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }

        val f = outFormat ?: return null
        return if (samples.isEmpty()) null else f to samples
    }

    private fun readSample(buf: ByteBuffer, info: MediaCodec.BufferInfo): Sample {
        val data = ByteArray(info.size)
        buf.position(info.offset)
        buf.limit(info.offset + info.size)
        buf.get(data)
        return Sample(data, info.presentationTimeUs, info.flags)
    }

    private const val TIMEOUT_US = 10_000L
}

/**
 * Разбор WAV-заголовка. Свой, а не через MediaExtractor: файл лежит в памяти
 * куском, и ради трёх полей раскладывать его во временный файл ни к чему.
 * Чанки ищем по имени, а не по фиксированному смещению — между «fmt » и
 * «data» вполне может стоять «LIST» с метаданными.
 */
internal object WavData {

    class Pcm(val data: ByteArray, val sampleRate: Int, val channels: Int)

    fun parse(wav: ByteArray): Pcm? {
        if (wav.size < 44) return null
        if (String(wav, 0, 4, Charsets.US_ASCII) != "RIFF") return null
        if (String(wav, 8, 4, Charsets.US_ASCII) != "WAVE") return null

        var pos = 12
        var channels = 1
        var sampleRate = 0
        var bits = 16

        while (pos + 8 <= wav.size) {
            val id = String(wav, pos, 4, Charsets.US_ASCII)
            val size = le32(wav, pos + 4)
            val body = pos + 8
            if (size < 0 || body + size > wav.size) {
                // Битый или обрезанный чанк — дальше идти небезопасно.
                if (id == "data" && body < wav.size) {
                    return finish(wav.copyOfRange(body, wav.size), sampleRate, channels, bits)
                }
                return null
            }
            when (id) {
                "fmt " -> {
                    channels = le16(wav, body + 2)
                    sampleRate = le32(wav, body + 4)
                    bits = le16(wav, body + 14)
                }
                "data" -> return finish(
                    wav.copyOfRange(body, body + size), sampleRate, channels, bits
                )
            }
            pos = body + size + (size and 1)   // чанки выровнены по чётному
        }
        return null
    }

    private fun finish(data: ByteArray, rate: Int, channels: Int, bits: Int): Pcm? {
        if (rate <= 0 || channels <= 0) return null
        // Кодировщик AAC принимает только 16-битный PCM; другого мы и не пишем.
        if (bits != 16) return null
        return Pcm(data, rate, channels)
    }

    private fun le16(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or
            ((b[i + 1].toInt() and 0xFF) shl 8) or
            ((b[i + 2].toInt() and 0xFF) shl 16) or
            ((b[i + 3].toInt() and 0xFF) shl 24)
}
