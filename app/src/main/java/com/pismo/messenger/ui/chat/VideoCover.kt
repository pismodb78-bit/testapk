package com.pismo.messenger.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.launch

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

/** Кадр → лёгкий JPEG для обложки. */
internal fun coverJpegOf(frame: Bitmap, maxSide: Int = 640): ByteArray? = runCatching {
    val k = maxSide.toFloat() / maxOf(frame.width, frame.height)
    val small = if (k >= 1f) frame else Bitmap.createScaledBitmap(
        frame, (frame.width * k).toInt().coerceAtLeast(1),
        (frame.height * k).toInt().coerceAtLeast(1), true,
    )
    val out = ByteArrayOutputStream()
    small.compress(Bitmap.CompressFormat.JPEG, 72, out)
    if (small !== frame) small.recycle()
    out.toByteArray()
}.getOrNull()

/**
 * ОБЛОЖКИ СТАРЫХ РОЛИКОВ — БЕЗ СКАЧИВАНИЯ РОЛИКА.
 *
 * Новые ролики уходят с кадром сразу. У старых его нет, и раньше обложка
 * появлялась, только когда ролик кто-нибудь скачивал. Крупные вложения
 * лежат на файловом сервисе, а он отдаёт файл КУСКАМИ (Range): системный
 * разборщик видео читает по ссылке только заголовок ролика и данные вокруг
 * нужного кадра — сотни килобайт вместо десятков мегабайт.
 *
 * Готовый кадр кладётся в кеш картинок и дописывается в строку сообщения:
 * дальше его видят все — и телефон, и компьютер, — не повторяя работы.
 * Ролики разбираются по одному, и каждый пробуем один раз за запуск.
 */
internal object RemoteCovers {
    private val tried = java.util.Collections.synchronizedSet(HashSet<String>())
    private val gate = kotlinx.coroutines.sync.Semaphore(1)

    /** Кадр для ролика без обложки. null — не вышло (или уже пробовали). */
    suspend fun grab(
        msgId: Int,
        scope: com.pismo.messenger.data.model.Scope,
        fileName: String?,
    ): ByteArray? {
        if (msgId <= 0) return null
        if (!tried.add("${scope.name}:$msgId")) return null
        return gate.withPermitCompat {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val url = runCatching {
                    com.pismo.messenger.data.repo.ChatRepository.fileUrlOf(scope.table, msgId)
                }.getOrNull() ?: return@withContext null
                val target = com.pismo.messenger.data.FileService.resolve(url)
                // Разборщик блокирующий — ждём его не дольше двадцати секунд.
                val frame = kotlinx.coroutines.withTimeoutOrNull(20_000) {
                    kotlinx.coroutines.runInterruptible { frameAt(target) }
                } ?: return@withContext null
                val jpeg = coverJpegOf(frame)
                runCatching { frame.recycle() }
                if (jpeg == null || jpeg.isEmpty()) return@withContext null
                share(msgId, scope, fileName, jpeg)
                jpeg
            }
        }
    }

    /** Кадр, снятый с уже скачанного файла, — тоже в сообщение, для всех. */
    fun shareFrame(
        msgId: Int,
        scope: com.pismo.messenger.data.model.Scope,
        fileName: String?,
        frame: Bitmap,
    ) {
        if (msgId <= 0) return
        if (!tried.add("${scope.name}:$msgId:local")) return
        val jpeg = coverJpegOf(frame) ?: return
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            share(msgId, scope, fileName, jpeg)
        }
    }

    private suspend fun share(
        msgId: Int,
        scope: com.pismo.messenger.data.model.Scope,
        fileName: String?,
        jpeg: ByteArray,
    ) {
        runCatching { com.pismo.messenger.data.MediaCache.put(msgId, "img", jpeg, fileName) }
        runCatching { com.pismo.messenger.data.repo.ChatRepository.backfillCover(msgId, scope, jpeg) }
    }

    /** Кадр чуть дальше начала: первый у многих роликов чёрный. */
    private fun frameAt(url: String): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(url, HashMap())
            r.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { r.release() }
        }
    }
}

private suspend fun <T> kotlinx.coroutines.sync.Semaphore.withPermitCompat(block: suspend () -> T): T {
    acquire()
    try { return block() } finally { release() }
}
