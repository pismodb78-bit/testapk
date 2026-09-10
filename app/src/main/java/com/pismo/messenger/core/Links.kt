package com.pismo.messenger.core

/**
 * Поиск ссылок в тексте сообщения.
 *
 * Отдельно от разметки упоминаний: там правило простое (@слово), здесь же
 * важно не захватить лишнего. Хвостовые точки, запятые и закрывающие скобки
 * почти всегда принадлежат предложению, а не адресу — «зайди на example.com,
 * там всё есть» не должно вести на «example.com,».
 */
object Links {

    private val PATTERN = Regex(
        """(?i)\b(?:https?://|www\.)[^\s<>"']+""",
    )

    /** Знаки, которые в конце адреса почти наверняка принадлежат фразе. */
    private const val TRAILING = ".,;:!?)]}»\"'"

    /** Найденная ссылка: где стоит в тексте и куда ведёт. */
    data class Found(val range: IntRange, val url: String)

    fun find(text: String): List<Found> {
        if (text.isEmpty() || ("http" !in text && "www." !in text)) return emptyList()
        return PATTERN.findAll(text).mapNotNull { m ->
            var end = m.range.last
            while (end > m.range.first && text[end] in TRAILING) end--
            // Незакрытая скобка в конце — тоже часть фразы, а не адреса.
            val raw = text.substring(m.range.first, end + 1)
            if (raw.length < 4) return@mapNotNull null
            val url = if (raw.startsWith("www.", ignoreCase = true)) "https://$raw" else raw
            Found(m.range.first..end, url)
        }.toList()
    }
}
