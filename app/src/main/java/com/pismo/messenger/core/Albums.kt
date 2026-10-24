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

    /**
     * С какого момента пачки ходят со знаком (24 октября 2026, 11:00 UTC —
     * выпуск, в котором он появился).
     *
     * Всё, что ОТПРАВЛЕНО РАНЬШЕ, знака не несёт и никогда не понесёт: у
     * старых сообщений его взять неоткуда. Поэтому для них оставлено прежнее
     * узнавание по времени — иначе уже собранные когда-то плитки рассыпались
     * бы на одиночные снимки, как это и случилось. Для всего нового работает
     * только знак, и снимки, отправленные по одному, больше не слипаются.
     */
    private const val MARK_SINCE_MS = 1792839600000L

    /** Разрыв по времени — только для старых сообщений, до появления знака. */
    private const val GAP_MS = 120_000L

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
            // В плитку идёт то, что ОТМЕЧЕНО пачкой. Отправленное по одному
            // отметки не несёт и остаётся отдельным сообщением, даже если
            // ушло секунда в секунду.
            //
            // У старых сообщений знака нет и быть не может — для них
            // остаётся прежнее узнавание: подряд, один отправитель, без
            // подписи и не дальше двух минут друг от друга.
            val legacy = head != null && prev != null &&
                    m.createdAtMs < MARK_SINCE_MS &&
                    head.createdAtMs < MARK_SINCE_MS &&
                    m.text.isBlank() &&
                    m.createdAtMs - prev.createdAtMs <= GAP_MS
            val fits = head != null && prev != null &&
                    cur.size < MAX &&
                    (m.albumPart || legacy) &&
                    m.senderId == head.senderId &&
                    m.replyToId == 0
            if (!fits) flush()
            cur.add(m)
        }
        flush()
        return out
    }
}
