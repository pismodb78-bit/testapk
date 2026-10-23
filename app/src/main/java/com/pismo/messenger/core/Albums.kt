package com.pismo.messenger.core

import com.pismo.messenger.data.model.ChatMessage

/**
 * Альбомы — несколько фотографий и видео, отправленных разом, показываются
 * ОДНОЙ плиткой, как в Telegram.
 *
 * Почему без отдельного столбца в базе. Прав на ALTER у приложения на этом
 * хостинге нет (см. DbMigrator), и завести «album_id» означало бы ждать
 * администратора, а до тех пор плиток бы не было вовсе. Поэтому альбом
 * УЗНАЁТСЯ по тем же признакам, по которым его узнал бы человек: подряд
 * идущие вложения одного отправителя, отправленные одной пачкой, где подпись
 * есть только у первого. Ровно так пачка и уходит из нашего листа выбора, а
 * заодно в плитки собираются и старые сообщения, отправленные когда-то
 * россыпью.
 *
 * Разрыв по времени нужен для больших видео: пока стомегабайтный ролик
 * уезжает на сервер, соседний по пачке уже лежит в базе, и строки получают
 * разное время. Две минуты — с запасом больше любой паузы внутри пачки и
 * заметно меньше обычной паузы между двумя разными отправками.
 */
object Albums {

    /** Больше десяти в одну плитку не кладём — как в Telegram. */
    const val MAX = 10

    private const val GAP_MS = 120_000L

    /** Фото или видеофайл — то, из чего собирается плитка. */
    fun isTile(m: ChatMessage): Boolean =
        !m.isDeleted && (m.hasImage || (m.hasFile && MediaKinds.isVideo(m.fileName)))

    /**
     * Разбить ХРОНОЛОГИЧЕСКИЙ список на группы: одиночные сообщения идут
     * группами по одному, альбомы — по сколько набралось.
     *
     * [solo] — сообщения, которые нельзя прятать в плитку: закреплённые и те,
     * на которых висят реакции (в плитке их негде показать).
     */
    fun group(
        list: List<ChatMessage>,
        solo: (ChatMessage) -> Boolean = { false },
    ): List<List<ChatMessage>> {
        if (list.isEmpty()) return emptyList()
        val out = ArrayList<List<ChatMessage>>(list.size)
        var cur = ArrayList<ChatMessage>(MAX)

        fun flush() {
            if (cur.isNotEmpty()) { out += cur; cur = ArrayList(MAX) }
        }

        for (m in list) {
            val joinable = isTile(m) && !solo(m) && !m.isPinned
            if (!joinable) { flush(); out += listOf(m); continue }
            val head = cur.firstOrNull()
            val prev = cur.lastOrNull()
            val fits = head != null && prev != null &&
                    cur.size < MAX &&
                    m.senderId == head.senderId &&
                    // Подпись бывает только у первого: своя подпись — это
                    // уже отдельное сообщение, а не продолжение пачки.
                    m.text.isBlank() &&
                    m.replyToId == 0 &&
                    m.createdAtMs - prev.createdAtMs <= GAP_MS
            if (!fits) flush()
            cur.add(m)
        }
        flush()
        return out
    }
}
