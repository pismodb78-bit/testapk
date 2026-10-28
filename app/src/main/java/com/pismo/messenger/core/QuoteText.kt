package com.pismo.messenger.core

/**
 * Источник ответа и пересылки: как назвать сообщение в цитате и как
 * показать пометку «Переслано от…». Тот же разбор, что на ПК (QuoteText.cs).
 *
 * Раньше пометка пересылки шла строкой текста с переводом строки на конце,
 * и у пересланного снимка без подписи под ней оставалась пустая строка, а
 * цитата ответа на альбом называла его просто фото.
 */
object QuoteText {

    private const val FORWARD_MARK = "↪ Переслано"

    data class Forward(val header: String, val body: String)

    /**
     * «↪ Переслано от Пети:\nтекст» → пометка и текст; null — сообщение не
     * пересланное. Понимает и старую запись в одну строку.
     */
    fun splitForward(text: String?): Forward? {
        if (text.isNullOrEmpty() || !text.startsWith(FORWARD_MARK)) return null
        val nl = text.indexOf('\n')
        val first: String
        val body: String
        if (nl >= 0) {
            first = text.substring(0, nl)
            body = text.substring(nl + 1)
        } else {
            val colon = text.indexOf(':', FORWARD_MARK.length)
            first = if (colon > 0) text.substring(0, colon) else text
            body = if (colon > 0) text.substring(colon + 1) else ""
        }
        return Forward(first.trimEnd('\r', ' ', ':'), body.trim('\r', '\n', ' '))
    }

    /** Текст без пометки пересылки и без знака пачки. */
    fun bodyOf(text: String?): String {
        val t = Albums.strip(text)
        return (splitForward(t)?.body ?: t).trim()
    }

    /**
     * Строка цитаты: текст сообщения, а если его нет — чем оно было. У
     * вложения с подписью — и то и другое: «📷 Фото · подпись».
     */
    fun describe(
        text: String?, hasImage: Boolean, hasAudio: Boolean, hasVideo: Boolean,
        fileName: String?, album: Boolean = false, deleted: Boolean = false,
    ): String {
        if (deleted) return "🚫 Сообщение удалено"
        var t = bodyOf(text)
        val gif = t.startsWith("gif:", ignoreCase = true) || isGifName(fileName)
        if (gif) t = ""
        val kind = when {
            album -> "🖼 Альбом"
            gif -> "🎞 GIF"
            hasVideo -> "⭕ Кружок"
            MediaKinds.isVideo(fileName) -> "🎥 Видео"
            hasImage -> "📷 Фото"
            hasAudio -> "🎤 Голосовое сообщение"
            !fileName.isNullOrBlank() -> "📎 " + fileName.substringAfterLast('/').substringAfterLast('\\')
            else -> ""
        }
        // Цитата — одна строка: переводы строк в ней только мешают.
        t = t.replace('\r', ' ').replace('\n', ' ').trim()
        return when {
            kind.isEmpty() -> t
            t.isEmpty() -> kind
            else -> "$kind · $t"
        }
    }
}
