package com.pismo.messenger.data

import com.pismo.messenger.core.UserSession
import com.pismo.messenger.data.model.ServerChannel
import com.pismo.messenger.data.model.ServerPermissions
import com.pismo.messenger.data.model.ServerSummary
import com.pismo.messenger.data.repo.ServerRepository

/**
 * Последний показанный список серверов и их каналов.
 *
 * Зачем. Вкладка «Серверы» каждый раз собиралась с нуля: список серверов,
 * бейджи непрочитанного, каналы выбранного сервера и права в нём — четыре
 * отдельных запроса в удалённую базу, и всё это время на экране пустой рельс.
 * На мобильной сети пауза заметная, а переключаются между вкладками
 * постоянно. ПК этой паузы не знает: там дерево серверов живёт в окне и
 * никуда не девается, PollTick лишь дописывает изменения.
 *
 * Здесь экран уходит из композиции целиком, поэтому запомненное дерево
 * приходится держать отдельно — ровно тот же приём, что и для ленты чата
 * (см. MessageMemory). Показываем сохранённое сразу, свежее подтягиваем
 * фоном и подменяем, когда оно приедет.
 *
 * Что здесь НЕ хранится: присутствие в голосовых каналах. Оно живое и
 * протухает за секунды — показать вчерашних собеседников в голосовой комнате
 * хуже, чем не показать никого.
 *
 * Живёт до перезапуска процесса и чистится при смене пользователя: чужие
 * серверы после входа под другим логином не должны мелькнуть даже на кадр.
 */
object ServerMemory {

    /**
     * Сколько серверов помним по каналам. Открытых подряд серверов редко
     * бывает больше — а каждый это список каналов со всеми правами.
     */
    private const val MAX_SERVERS = 12

    private class Channels(
        val channels: List<ServerChannel>,
        val perms: ServerPermissions,
        val userId: Int,
    )

    private var servers: List<ServerSummary> = emptyList()
    private var badges: List<ServerRepository.Badge> = emptyList()
    private var ownerId: Int = 0

    /**
     * Какой сервер был открыт последним. Возврат на вкладку должен попадать
     * туда же, откуда ушли, — как на ПК, где выбор в дереве просто не
     * сбрасывается.
     */
    private var lastServerId: Int = 0

    // LinkedHashMap с порядком обращения — готовый LRU без своей возни.
    private val channelCache = object : LinkedHashMap<Int, Channels>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Channels>): Boolean =
            size > MAX_SERVERS
    }

    /** Что показать в рельсе сразу; пустой список — ничего не помним. */
    @Synchronized
    fun peekServers(): List<ServerSummary> =
        if (ownerId == UserSession.effectiveId) servers else emptyList()

    @Synchronized
    fun peekBadges(): List<ServerRepository.Badge> =
        if (ownerId == UserSession.effectiveId) badges else emptyList()

    /** Сервер, открытый в прошлый раз, если он ещё в списке. */
    @Synchronized
    fun peekSelected(list: List<ServerSummary>): ServerSummary? =
        if (ownerId == UserSession.effectiveId)
            list.firstOrNull { it.id == lastServerId } ?: list.firstOrNull()
        else list.firstOrNull()

    /** Каналы и права сервера; null — ничего не помним. */
    @Synchronized
    fun peekChannels(serverId: Int): Pair<List<ServerChannel>, ServerPermissions>? {
        val e = channelCache[serverId] ?: return null
        // Запись, снятая под другим пользователем, не наша.
        if (e.userId != UserSession.effectiveId) return null
        return e.channels to e.perms
    }

    @Synchronized
    fun putServers(list: List<ServerSummary>) {
        if (list.isEmpty()) return
        ownerId = UserSession.effectiveId
        servers = list
    }

    /**
     * Бейджи пишем даже пустыми: «всё прочитано» — такой же результат, как
     * «три непрочитанных», и если его не запомнить, точки будут возвращаться
     * на кадр при каждом заходе.
     */
    @Synchronized
    fun putBadges(list: List<ServerRepository.Badge>) {
        ownerId = UserSession.effectiveId
        badges = list
    }

    @Synchronized
    fun putSelected(serverId: Int) {
        lastServerId = serverId
    }

    @Synchronized
    fun putChannels(serverId: Int, channels: List<ServerChannel>, perms: ServerPermissions) {
        if (channels.isEmpty()) return
        channelCache[serverId] = Channels(channels, perms, UserSession.effectiveId)
    }

    /** Забыть один сервер — например, после выхода из него. */
    @Synchronized
    fun invalidate(serverId: Int) {
        channelCache.remove(serverId)
        servers = servers.filterNot { it.id == serverId }
    }

    @Synchronized
    fun clear() {
        servers = emptyList()
        badges = emptyList()
        lastServerId = 0
        ownerId = 0
        channelCache.clear()
    }
}
