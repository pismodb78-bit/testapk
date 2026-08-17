package com.pismo.messenger.ui.chat

import com.pismo.messenger.data.model.Scope
import com.pismo.messenger.data.model.ReplyQuote

/**
 * Память цитат «в ответ на …».
 *
 * Цитата — отдельный запрос к удалённой базе на каждый пузырь с ответом.
 * Без этого кэша прокрутка вверх и обратно заставляла перезапрашивать их
 * все: LazyColumn выбрасывает ушедшие с экрана элементы вместе с их
 * состоянием. Текст цитаты меняется разве что при редактировании, поэтому
 * держать его в памяти сеанса безопасно.
 *
 * Размер ограничен: в длинной переписке из тысяч ответов карта иначе
 * растёт без предела.
 */
object QuoteMemory {

    private const val MAX_ENTRIES = 400

    private val cache = object : LinkedHashMap<String, ReplyQuote>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ReplyQuote>) =
            size > MAX_ENTRIES
    }

    private fun key(messageId: Int, scope: Scope) = "${scope.name}/$messageId"

    @Synchronized
    fun get(messageId: Int, scope: Scope): ReplyQuote? {
        if (messageId <= 0) return null
        return cache[key(messageId, scope)]
    }

    @Synchronized
    fun put(messageId: Int, scope: Scope, quote: ReplyQuote) {
        if (messageId <= 0) return
        cache[key(messageId, scope)] = quote
    }

    /** Сообщение отредактировали или удалили — цитата больше не актуальна. */
    @Synchronized
    fun invalidate(messageId: Int) {
        cache.keys.removeAll { it.endsWith("/$messageId") }
    }

    @Synchronized
    fun clear() = cache.clear()
}
