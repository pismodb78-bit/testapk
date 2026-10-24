package com.pismo.messenger.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import java.io.ByteArrayOutputStream

/**
 * Обложка ролика — кадр, который уходит ВМЕСТЕ с видео.
 *
 * В строке сообщения есть место и под картинку, и под файл, поэтому кадр
 * кладётся туда же, куда обычная фотография. Так собеседник видит, что за
 * ролик, ещё до того как скачает его, и пузырь сразу встаёт правильной формы
 * — ровно как в телеграме. Менять базу для этого не нужно: нового столбца не
 * требуется.
 *
 * Кадр достаём ПРЯМО ИЗ БАЙТОВ, без временного файла: MediaMetadataRetriever
 * умеет читать из источника в памяти.
 */
internal fun videoCoverJpeg(bytes: ByteArray?, maxSide: Int = 640): ByteArray? {
    if (bytes == null || bytes.isEmpty()) return null
    val frame = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(object : MediaDataSource() {
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                    if (position >= bytes.size) return -1
                    val n = minOf(size.toLong(), bytes.size - position).toInt()
                    System.arraycopy(bytes, position.toInt(), buffer, offset, n)
                    return n
                }

                override fun getSize(): Long = bytes.size.toLong()
                override fun close() = Unit
            })
            r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } finally {
            runCatching { r.release() }
        }
    }.getOrNull() ?: return null

    return runCatching {
        // Обложка должна быть лёгкой: она едет в каждой ленте и в каждом
        // списке, а разглядывать её никто не будет — для этого есть сам ролик.
        val k = maxSide.toFloat() / maxOf(frame.width, frame.height)
        val small = if (k >= 1f) frame else Bitmap.createScaledBitmap(
            frame, (frame.width * k).toInt().coerceAtLeast(1),
            (frame.height * k).toInt().coerceAtLeast(1), true,
        )
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 72, out)
        if (small !== frame) small.recycle()
        frame.recycle()
        out.toByteArray()
    }.getOrNull()
}

/** Размеры обложки — по ним лента ставит коробку ролика до загрузки файла. */
internal fun coverSize(jpeg: ByteArray?): Pair<Int, Int>? {
    if (jpeg == null || jpeg.isEmpty()) return null
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
    if (o.outWidth <= 0 || o.outHeight <= 0) return null
    return o.outWidth to o.outHeight
}
