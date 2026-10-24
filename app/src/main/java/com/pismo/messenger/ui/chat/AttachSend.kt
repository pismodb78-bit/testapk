package com.pismo.messenger.ui.chat

import android.content.Context
import com.pismo.messenger.core.toPortableImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Сколько вложений уходит одним сообщением-плиткой. Больше десяти Telegram
 * не кладёт в один альбом, и мы не кладём: одиннадцатое открывает следующую
 * плитку, двадцать третье — третью (10 + 10 + 3).
 */
private const val ALBUM_MAX = com.pismo.messenger.core.Albums.MAX


/**
 * Выбранная пачка — В ПОЛЕ ВВОДА, а не сразу в чат.
 *
 * Байты читаются здесь: до этого лист держал только ссылки. Общий предел —
 * на всю пачку разом, потому что прикреплённое лежит в памяти до отправки, и
 * десяток роликов иначе просто уронит приложение. Что не поместилось —
 * называем поимённо, остальное прикрепляем: терять девять снимков из-за
 * одного тяжёлого обидно.
 *
 * Дальше всё как со скрепкой: подпись дописывается в поле, а отправка режет
 * пачку на плитки по десять (см. Albums) — порядок тот, в котором выбирали.
 */
internal suspend fun attachPicked(
    context: Context,
    picked: List<Picked>,
    used: Long,
    onAdd: (List<PendingFile>) -> Unit,
    note: (String) -> Unit,
) {
    if (picked.isEmpty()) return
    val skipped = mutableListOf<String>()
    val out = ArrayList<PendingFile>(picked.size)
    var busy = used

    for (p in picked) {
        val prepared = withContext(Dispatchers.IO) {
            runCatching {
                // Размер узнаём ДО чтения: файл на 300 МБ иначе успел бы
                // положить приложение ещё до проверки.
                val declared = fileSizeOf(context, p.uri)
                if (declared > 0 && busy + declared > MAX_ATTACH_BYTES) return@runCatching null
                val bytes = context.contentResolver.openInputStream(p.uri)?.use { it.readBytes() }
                    ?: return@runCatching null
                if (busy + bytes.size > MAX_ATTACH_BYTES) return@runCatching null
                // Картинку приводим к формату, который прочтут все клиенты:
                // WebP и HEIC ПК не открывает.
                if (p.isImage) toPortableImage(bytes, p.name) else bytes to p.name
            }.getOrNull()
        }

        if (prepared == null) { skipped += p.name; continue }
        busy += prepared.first.size
        // У ролика достаём кадр: он уйдёт обложкой в ту же строку сообщения
        // (см. VideoCover) и будет виден собеседнику до загрузки файла.
        val cover = if (p.isVideo) withContext(Dispatchers.IO) {
            videoCoverJpeg(prepared.first)
        } else null
        out += PendingFile(
            bytes = prepared.first,
            fileName = prepared.second,
            isImage = p.isImage,
            cover = cover,
        )
    }

    if (out.isNotEmpty()) onAdd(out)
    if (skipped.isNotEmpty()) {
        note(
            "Не приложено — всего можно ${MAX_ATTACH_BYTES / 1024 / 1024} МБ за раз: " +
                    skipped.joinToString(", ")
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
