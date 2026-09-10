package com.pismo.messenger.core

/**
 * Откуда ссылка: короткая пометка и цвет службы.
 *
 * Значков не качаем и в приложение не кладём. Настоящие иконки пришлось бы
 * либо тянуть с самих сайтов — а это сообщает им, что человек смотрел
 * сообщение, ещё до того, как он открыл ссылку, — либо носить с собой чужие
 * логотипы. Поэтому рисуем сами: цвет службы и одна-две буквы, ровно как
 * уже сделаны бейджи типов файлов.
 */
object LinkSources {

    /** Пометка и цвет. Цвет — в формате 0xAARRGGBB. */
    data class Source(val mark: String, val color: Long, val title: String)

    private val KNOWN = listOf(
        listOf("youtube.com", "youtu.be", "youtube-nocookie.com") to Source("YT", 0xFFFF0000, "YouTube"),
        listOf("steampowered.com", "steamcommunity.com") to Source("ST", 0xFF1B2838, "Steam"),
        listOf("instagram.com", "instagr.am") to Source("IG", 0xFFE1306C, "Instagram"),
        listOf("t.me", "telegram.org", "telegram.me") to Source("TG", 0xFF2AABEE, "Telegram"),
        listOf("vk.com", "vk.ru") to Source("VK", 0xFF0077FF, "ВКонтакте"),
        listOf("github.com", "gist.github.com") to Source("GH", 0xFF24292E, "GitHub"),
        listOf("x.com", "twitter.com") to Source("X", 0xFF14171A, "X"),
        listOf("tiktok.com") to Source("TT", 0xFF010101, "TikTok"),
        listOf("discord.com", "discord.gg", "discordapp.com") to Source("DC", 0xFF5865F2, "Discord"),
        listOf("twitch.tv") to Source("TW", 0xFF9146FF, "Twitch"),
        listOf("reddit.com", "redd.it") to Source("RD", 0xFFFF4500, "Reddit"),
        listOf("spotify.com") to Source("SP", 0xFF1DB954, "Spotify"),
        listOf("music.yandex.ru", "yandex.ru", "ya.ru") to Source("Я", 0xFFFC3F1D, "Яндекс"),
        listOf("google.com", "google.ru") to Source("G", 0xFF4285F4, "Google"),
        listOf("wikipedia.org", "wikimedia.org") to Source("W", 0xFF636466, "Википедия"),
        listOf("rutube.ru") to Source("RT", 0xFF14191F, "RUTUBE"),
        listOf("ok.ru") to Source("OK", 0xFFEE8208, "Одноклассники"),
        listOf("pikabu.ru") to Source("PK", 0xFF00A046, "Пикабу"),
        listOf("habr.com") to Source("H", 0xFF629FD0, "Хабр"),
        listOf("mail.ru") to Source("MR", 0xFF005FF9, "Mail.ru"),
        listOf("avito.ru") to Source("AV", 0xFF00AAFF, "Авито"),
        listOf("wildberries.ru") to Source("WB", 0xFF7B1FA2, "Wildberries"),
        listOf("ozon.ru") to Source("OZ", 0xFF005BFF, "Ozon"),
    )

    /** Домен без www — то, что показываем рядом со значком. */
    fun domainOf(url: String): String {
        val afterScheme = url.substringAfter("://", url)
        val host = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        return host.removePrefix("www.").lowercase()
    }

    fun of(url: String): Source {
        val host = domainOf(url)
        for ((hosts, src) in KNOWN) {
            if (hosts.any { host == it || host.endsWith(".$it") }) return src
        }
        // Незнакомый адрес — первая буква домена на сером. Так строка всё
        // равно читается как «источник», а не как безликая полоска.
        val letter = host.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
        return Source(letter, 0xFF6E7681, host)
    }
}
