package com.pismo.messenger.data

import com.pismo.messenger.core.UserSession
import com.pismo.messenger.data.model.ChannelType
import com.pismo.messenger.data.model.ServerChannel
import com.pismo.messenger.data.model.ServerPermissions
import com.pismo.messenger.data.model.ServerSummary
import com.pismo.messenger.data.repo.ServerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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
 * За пределами процесса продолжается на диске — тем же приёмом, что у
 * переписок: после закрытия приложения вкладка иначе снова встречала бы
 * пустым рельсом. Файл один и маленький, вся раскладка серверов в нём.
 *
 * Чистится при смене пользователя вместе с диском: чужие серверы после входа
 * под другим логином не должны мелькнуть даже на кадр.
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

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var file: File? = null

    /**
     * Под каким пользователем диск уже прочитан. Именно id, а не флаг: на
     * старте процесса сессии ещё нет, и простой «уже читали» навсегда
     * запомнил бы неудачную попытку до логина — раскладка не поднялась бы
     * никогда.
     */
    private var loadedFor = -1

    fun init(context: android.content.Context) {
        file = File(context.filesDir, "servers.json")
    }

    /**
     * Поднимает раскладку с диска при первом обращении.
     *
     * Синхронно и один раз за запуск: файл на десяток серверов читается
     * быстрее, чем рисуется первый кадр, а асинхронная загрузка вернула бы
     * рельс кадром позже — с тем самым миганием, ради которого всё и затеяно.
     */
    private fun ensureLoaded() {
        val me = UserSession.effectiveId
        if (me <= 0 || loadedFor == me) return
        loadedFor = me
        runCatching {
            val f = file ?: return
            if (!f.exists()) return
            val root = JSONObject(f.readText())
            if (root.optInt("uid") != me) return

            ownerId = root.optInt("uid")
            lastServerId = root.optInt("last")

            root.optJSONArray("servers")?.let { arr ->
                val list = ArrayList<ServerSummary>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        // unread и mentions намеренно не восстанавливаем:
                        // счётчики непрочитанного протухают за минуты, и
                        // вчерашние цифры хуже, чем никаких — свежие
                        // приедут вместе с бейджами.
                        ServerSummary(
                            id = o.optInt("id"),
                            name = o.optString("name"),
                            ownerId = o.optInt("owner"),
                        )
                    )
                }
                servers = list
            }

            root.optJSONObject("channels")?.let { obj ->
                obj.keys().forEach { k ->
                    val sid = k.toIntOrNull() ?: return@forEach
                    val arr = obj.optJSONArray(k) ?: return@forEach
                    val list = ArrayList<ServerChannel>(arr.length())
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val type = runCatching { ChannelType.valueOf(o.optString("type")) }
                            .getOrNull() ?: continue
                        list.add(
                            ServerChannel(
                                id = o.optInt("id"),
                                serverId = sid,
                                name = o.optString("name"),
                                type = type,
                                position = o.optInt("pos"),
                                userLimit = o.optInt("lim"),
                            )
                        )
                    }
                    // Права намеренно не храним: они меняются на сервере без
                    // нашего ведома, и показать по кеша́м кнопку, которой у
                    // человека уже нет, хуже, чем не показать её на секунду.
                    if (list.isNotEmpty()) {
                        channelCache[sid] = Channels(list, ServerPermissions(), ownerId)
                    }
                }
            }
        }
    }

    private fun persist() {
        val f = file ?: return
        val snapshotServers = servers
        val snapshotLast = lastServerId
        val snapshotChannels = channelCache.mapValues { it.value.channels }
        val uid = UserSession.effectiveId

        io.launch {
            runCatching {
                val arr = JSONArray()
                snapshotServers.forEach { s ->
                    arr.put(
                        JSONObject()
                            .put("id", s.id)
                            .put("name", s.name)
                            .put("owner", s.ownerId)
                    )
                }

                val ch = JSONObject()
                snapshotChannels.forEach { (sid, list) ->
                    val a = JSONArray()
                    list.forEach { c ->
                        a.put(
                            JSONObject()
                                .put("id", c.id)
                                .put("name", c.name)
                                .put("type", c.type.name)
                                .put("pos", c.position)
                                .put("lim", c.userLimit)
                        )
                    }
                    ch.put(sid.toString(), a)
                }

                val root = JSONObject()
                    .put("uid", uid)
                    .put("last", snapshotLast)
                    .put("servers", arr)
                    .put("channels", ch)

                val tmp = File(f.absolutePath + ".tmp")
                tmp.writeText(root.toString())
                if (!tmp.renameTo(f)) {
                    f.writeText(root.toString())
                    tmp.delete()
                }
            }
        }
    }

    /** Что показать в рельсе сразу; пустой список — ничего не помним. */
    @Synchronized
    fun peekServers(): List<ServerSummary> {
        ensureLoaded()
        return if (ownerId == UserSession.effectiveId) servers else emptyList()
    }

    @Synchronized
    fun peekBadges(): List<ServerRepository.Badge> {
        // Бейджи на диск не пишем: непрочитанное протухает быстрее, чем
        // человек успевает закрыть приложение, и показать вчерашние точки
        // хуже, чем не показать никаких.
        ensureLoaded()
        return if (ownerId == UserSession.effectiveId) badges else emptyList()
    }

    /** Сервер, открытый в прошлый раз, если он ещё в списке. */
    @Synchronized
    fun peekSelected(list: List<ServerSummary>): ServerSummary? {
        ensureLoaded()
        return if (ownerId == UserSession.effectiveId)
            list.firstOrNull { it.id == lastServerId } ?: list.firstOrNull()
        else list.firstOrNull()
    }

    /** Каналы и права сервера; null — ничего не помним. */
    @Synchronized
    fun peekChannels(serverId: Int): Pair<List<ServerChannel>, ServerPermissions>? {
        ensureLoaded()
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
        persist()
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
        if (lastServerId == serverId) return
        lastServerId = serverId
        persist()
    }

    @Synchronized
    fun putChannels(serverId: Int, channels: List<ServerChannel>, perms: ServerPermissions) {
        if (channels.isEmpty()) return
        channelCache[serverId] = Channels(channels, perms, UserSession.effectiveId)
        persist()
    }

    /** Забыть один сервер — например, после выхода из него. */
    @Synchronized
    fun invalidate(serverId: Int) {
        channelCache.remove(serverId)
        servers = servers.filterNot { it.id == serverId }
        persist()
    }

    @Synchronized
    fun clear() {
        servers = emptyList()
        badges = emptyList()
        lastServerId = 0
        ownerId = 0
        channelCache.clear()
        loadedFor = -1
        runCatching { file?.delete() }
    }
}
