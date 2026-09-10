package com.pismo.messenger.core

/**
 * Какие ссылки можно проиграть, не уходя из приложения.
 *
 * Два разных случая, и путать их нельзя. Прямой файл (.mp4 и подобные) —
 * это просто видео, его играет тот же проигрыватель, что и вложения. А
 * YouTube и RUTUBE отдают не файл, а страницу: играть их можно только их
 * собственным встроенным проигрывателем. Это не обход, а ровно тот способ,
 * который они для встраивания и предлагают, — иначе пришлось бы разбирать
 * их внутренние потоки, чего их правила не разрешают.
 */
object VideoLinks {

    sealed interface Playable {
        /** Обычный видеофайл — играем сами. */
        data class Direct(val url: String) : Playable

        /**
         * Страница службы — играем её встроенным проигрывателем.
         *
         * [origin] — домен службы. Он обязателен: проигрыватель отказывается
         * работать, если не видит, с какой страницы его открыли (YouTube
         * отвечает на это ошибкой 153). Значит, страницу с рамкой надо
         * собрать самим и отдать её от имени этого домена.
         */
        data class Embed(val url: String, val origin: String, val title: String) : Playable
    }

    private val FILE_EXT = setOf("mp4", "webm", "m4v", "mov", "mkv", "m3u8", "3gp")

    fun of(url: String): Playable? {
        val host = LinkSources.domainOf(url)

        youtubeId(url, host)?.let {
            return Playable.Embed(
                "https://www.youtube.com/embed/$it?autoplay=1&playsinline=1&rel=0&enablejsapi=1",
                "https://www.youtube.com",
                "YouTube",
            )
        }
        rutubeId(url, host)?.let {
            return Playable.Embed(
                "https://rutube.ru/play/embed/$it", "https://rutube.ru", "RUTUBE",
            )
        }
        // Instagram отсюда УБРАН намеренно. Их адрес встраивания теперь
        // требует входа: вместо ролика окно показывало бы «зарегистрируйтесь».
        // Обойти это можно только обойдя их же ограничение доступа — этого мы
        // не делаем. Такая ссылка открывается снаружи, где вход уже есть:
        // в приложении Instagram или в браузере.
        tiktokId(url, host)?.let {
            return Playable.Embed(
                "https://www.tiktok.com/embed/v2/$it", "https://www.tiktok.com", "TikTok",
            )
        }

        // Прямой файл: смотрим на расширение в пути, не задевая параметры
        // после «?» — там расширение может встретиться случайно.
        val path = url.substringAfter("://", url).substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext in FILE_EXT) return Playable.Direct(url)

        return null
    }

    private fun youtubeId(url: String, host: String): String? {
        val id = when {
            host == "youtu.be" ->
                url.substringAfter("youtu.be/", "").substringBefore('?').substringBefore('&')
            host.endsWith("youtube.com") || host.endsWith("youtube-nocookie.com") -> when {
                "/watch" in url -> Regex("[?&]v=([^&#]+)").find(url)?.groupValues?.get(1)
                "/shorts/" in url ->
                    url.substringAfter("/shorts/").substringBefore('?').substringBefore('/')
                "/embed/" in url ->
                    url.substringAfter("/embed/").substringBefore('?').substringBefore('/')
                else -> null
            }
            else -> null
        }
        // Идентификатор у YouTube — одиннадцать знаков из ограниченного набора.
        // Проверка нужна, чтобы не собрать проигрыватель из случайного мусора.
        return id?.takeIf { it.length in 8..16 && it.all { c -> c.isLetterOrDigit() || c == '_' || c == '-' } }
    }

    private fun tiktokId(url: String, host: String): String? {
        if (!host.endsWith("tiktok.com")) return null
        val id = when {
            "/video/" in url -> url.substringAfter("/video/").substringBefore('?').substringBefore('/')
            else -> null
        }
        return id?.takeIf { it.length >= 8 && it.all { c -> c.isDigit() } }
    }

    private fun rutubeId(url: String, host: String): String? {
        if (!host.endsWith("rutube.ru")) return null
        val id = when {
            "/video/" in url -> url.substringAfter("/video/").substringBefore('?').substringBefore('/')
            "/play/embed/" in url -> url.substringAfter("/play/embed/").substringBefore('?')
            else -> null
        }
        return id?.takeIf { it.length >= 8 && it.all { c -> c.isLetterOrDigit() } }
    }
}
