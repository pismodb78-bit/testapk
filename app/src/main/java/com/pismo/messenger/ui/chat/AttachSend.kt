package com.pismo.messenger.ui.chat

import android.content.Context
import com.pismo.messenger.core.toPortableImage
import com.pismo.messenger.data.Transfers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Сколько вложений уходит одним сообщением-плиткой. Больше десяти Telegram
 * не кладёт в один альбом, и мы не кладём: одиннадцатое открывает следующую
 * плитку, двадцать третье — третью (10 + 10 + 3).
 */
private const val ALBUM_MAX = com.pismo.messenger.core.Albums.MAX

/**
 * Сколько вложений держим «в полёте» одновременно.
 *
 * Очередь передач хранит байты задачи в памяти до её конца. Пачка из десяти
 * роликов, прочитанная разом, кладёт приложение ещё до первого байта на
 * сервере, поэтому следующий файл читаем только когда предыдущие
 * разгрузились.
 */
private const val IN_FLIGHT_MAX = 2

/**
 * Отправка выбранной пачки: читает по одному, режет на плитки по десять и
 * ставит в очередь передач.
 *
 * Подпись достаётся ПЕРВОМУ вложению первой плитки — как в Telegram. У
 * остальных текст пустой, и именно по этому признаку лента потом собирает их
 * обратно в одну плитку (см. core/Albums).
 */
internal suspend fun sendPickedAttachments(
    context: Context,
    picked: List<Picked>,
    caption: String,
    replyToId: Int,
    where: String,
    enqueue: (bytes: ByteArray, fileName: String?, isImage: Boolean, text: String, replyTo: Int) -> Unit,
    note: (String) -> Unit,
) {
    if (picked.isEmpty()) return
    val tooBig = mutableListOf<String>()
    var index = 0

    for (p in picked) {
        // Ждём, пока очередь разгрузится: иначе вся пачка окажется в памяти.
        while (Transfers.active.value.count { it.where == where } >= IN_FLIGHT_MAX) {
            delay(250)
        }

        val prepared = withContext(Dispatchers.IO) {
            runCatching {
                val declared = fileSizeOf(context, p.uri)
                if (declared > MAX_ATTACH_BYTES) return@runCatching null
                val bytes = context.contentResolver.openInputStream(p.uri)?.use { it.readBytes() }
                    ?: return@runCatching null
                if (bytes.size > MAX_ATTACH_BYTES) return@runCatching null
                // Картинку приводим к формату, который прочтут все клиенты:
                // WebP и HEIC ПК не открывает.
                if (p.isImage) toPortableImage(bytes, p.name) else bytes to p.name
            }.getOrNull()
        }

        if (prepared == null) { tooBig += p.name; continue }
        val (bytes, name) = prepared

        val first = index == 0
        enqueue(
            bytes,
            // Имя пишем только не-картинкам: с именем ПК рисует под фото
            // вторую строку — карточку файла.
            if (p.isImage) null else name,
            p.isImage,
            if (first) caption else "",
            if (first) replyToId else 0,
        )
        index++
    }

    if (tooBig.isNotEmpty()) {
        note(
            "Не отправлено — больше ${MAX_ATTACH_BYTES / 1024 / 1024} МБ за файл: " +
                    tooBig.joinToString(", ")
        )
    }
}

/** Отправка идёт плитками по десять — само собой, по порядку выбора. */
internal fun albumChunks(n: Int): List<Int> {
    if (n <= 0) return emptyList()
    val out = ArrayList<Int>()
    var left = n
    while (left > 0) { out += minOf(left, ALBUM_MAX); left -= out.last() }
    return out
}
