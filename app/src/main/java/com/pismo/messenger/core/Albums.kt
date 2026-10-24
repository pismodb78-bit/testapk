package com.pismo.messenger.core

import com.pismo.messenger.data.model.ChatMessage

/**
 * Альбомы — несколько фотографий и видео, отправленных разом, показываются
 * ОДНОЙ плиткой, как в Telegram.
 *
 * Почему без отдельного столбца в базе. Прав на ALTER у приложения на этом
 * хостинге нет (см. DbMigrator), и завести «album_id» означало бы ждать
 * администратора. Поэтому пачка помечает САМА СЕБЯ: все вложения, кроме
 * первого, уходят с невидимым знаком вместо текста (U+2063, «невидимый
 * разделитель»). Он ничего не весит, ничего не показывает и ничего не ломает
 * у клиента, который о нём не знает, — там это просто сообщение без подписи.
 *
 * Раньше пачка угадывалась по времени: подряд, один отправитель, без подписи
 * и не дальше двух минут друг от друга. Угадывание ошибалось в обе стороны —
 * снимки, отправленные ПО ОДНОМУ, слипались в плитку, а пачка с медленным
 * роликом распадалась. Теперь догадок нет.
 */
object Albums {

    /** Больше десяти в одну плитку не кладём — как в Telegram. */
    const val MAX = 10

    /**
     * Знак «это продолжение пачки» — невидимый разделитель U+2063. Стоит
     * вместо текста у всех вложений пачки, кроме первого.
     */
    const val MARK = "\u2063"

    /** Пришло ли сообщение продолжением пачки. */
    fun isPart(text: String?): Boolean = text != null && text.trim() == MARK

    /** Текст для показа: знак пачки — это не текст. */
    fun strip(text: String?): String = if (isPart(text)) "" else (text ?: "")

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
            // В плитку идёт только то, что ОТМЕЧЕНО пачкой. Отправленное по
            // одному отметки не несёт и остаётся отдельным сообщением, даже
            // если ушло секунда в секунду.
            val fits = head != null && prev != null &&
                    cur.size < MAX &&
                    m.albumPart &&
                    m.senderId == head.senderId &&
                    m.replyToId == 0
            if (!fits) flush()
            cur.add(m)
        }
        flush()
        return out
    }
}
