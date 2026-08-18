package com.pismo.messenger.service

import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.pismo.messenger.core.Prefs
import com.pismo.messenger.core.UserSession
import com.pismo.messenger.data.repo.ChatRepository
import com.pismo.messenger.data.repo.FriendsRepository
import com.pismo.messenger.data.repo.ServerRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Фоновая проверка новых сообщений, когда приложение свёрнуто — замена
 * трею ПК-версии с его балунами.
 *
 * Push-инфраструктуры (FCM) в проекте нет, а база опрашивается напрямую,
 * поэтому единственный способ узнать о новом сообщении в фоне — тот же
 * опрос, что и на ПК. Интервал здесь больше (10 с против 2.5 с), чтобы
 * не сажать батарею.
 */
class PollingService : LifecycleService() {

    private val previousUnread = HashMap<Int, Int>()
    private val previousGroupMax = HashMap<Int, Int>()
    private val previousChannelMax = HashMap<Int, Int>()
    private var groupBaselineReady = false
    private var channelBaselineReady = false
    private val knownFriendRequests = HashSet<Int>()
    private var friendBaselineReady = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(Notifications.ID_SERVICE, Notifications.serviceNotification(this))

        lifecycleScope.launch {
            while (isActive) {
                if (UserSession.effectiveId > 0 && Prefs.backgroundPolling) {
                    runCatching { pollOnce() }
                }
                delay(10_000)
            }
        }
        return START_STICKY
    }

    private suspend fun pollOnce() {
        // Личные сообщения.
        val unread = ChatRepository.unreadBySender()

        for ((senderId, count) in unread) {
            val before = previousUnread[senderId] ?: 0
            if (count > before) {
                val name = runCatching {
                    com.pismo.messenger.data.repo.AuthRepository.loadUser(senderId)?.first
                }.getOrNull() ?: "Пользователь #$senderId"
                // Показываем, ЧТО прислали: текст, фото, кружок, документ,
                // архив. Раньше в шторке было безликое «Новых сообщений: N».
                val preview = ChatRepository.previewOfLatestFrom(senderId)
                val text = if (count > 1) "$preview  ·  ещё ${count - 1}" else preview
                Notifications.showMessage(this, senderId, name, text)
            }
        }
        previousUnread.clear()
        previousUnread.putAll(unread)

        // Групповые: у групп нет отметки прочтения на пользователя, поэтому
        // базовую точку держим в памяти — как _prevGroupMax на ПК.
        val groupMax = ChatRepository.groupMaxIncoming()
        if (!groupBaselineReady) {
            // Первый проход только запоминает состояние, иначе при каждом
            // запуске сервиса сыпались бы уведомления о давно прочитанном.
            // Раньше здесь стоял return, и он обрывал ВЕСЬ обход: каналы и
            // заявки в друзья на первом тике не опрашивались вовсе.
            groupMax.forEach { (gid, v) -> previousGroupMax[gid] = v.first }
            groupBaselineReady = true
        } else {
            for ((gid, value) in groupMax) {
                val (maxId, name) = value
                val before = previousGroupMax[gid] ?: 0
                if (maxId > before) {
                    val preview = ChatRepository.previewOfLatestInGroup(gid)
                    Notifications.showGroupMessage(this, gid, name, preview)
                }
                previousGroupMax[gid] = maxId
            }
        }

        pollChannels()
        pollFriendRequests()
    }

    /**
     * Заявки в друзья. Раньше о них не сообщалось вообще: узнать о заявке
     * можно было, только зайдя на вкладку «Друзья» и увидев там цифру.
     */
    private suspend fun pollFriendRequests() {
        val incoming = runCatching { FriendsRepository.incomingRequests() }
            .getOrDefault(emptyList())

        val ids = incoming.map { it.userId }.toSet()
        if (!friendBaselineReady) {
            knownFriendRequests.addAll(ids)
            friendBaselineReady = true
            return
        }

        for (entry in incoming) {
            if (!knownFriendRequests.add(entry.userId)) continue
            Notifications.showFriendRequest(this, entry.userId, entry.name)
        }
        // Отозванные и принятые заявки забываем, иначе повторная заявка от
        // того же человека уже не покажется.
        knownFriendRequests.retainAll(ids)
    }

    /**
     * Каналы серверов. Раньше их здесь не было вовсе — сообщение в канале
     * не давало уведомления ни в фоне, ни свёрнутым, хотя красная цифра в
     * списке серверов появлялась.
     *
     * Считаем по тем же бейджам, что рисует список серверов: отдельный
     * запрос «что нового» дал бы расхождение между цифрой и уведомлением.
     */
    private suspend fun pollChannels() {
        // Триггер — простой запрос максимальных id, а НЕ бейджи. Бейджи
        // собирают тяжёлый SQL с проверками необязательных колонок, и если он
        // падает, уведомления о каналах пропадают целиком и молча — ровно то,
        // что и наблюдалось: из личных чатов уведомления шли, из каналов нет.
        val maxIds = ServerRepository.maxIncomingPerChannel()
        if (maxIds.isEmpty()) return

        // Первый проход только запоминает состояние: иначе при каждом
        // запуске сервиса сыпались бы уведомления о давно прочитанном.
        if (!channelBaselineReady) {
            previousChannelMax.putAll(maxIds)
            channelBaselineReady = true
            return
        }

        val fresh = maxIds.filter { (channelId, maxId) ->
            maxId > (previousChannelMax[channelId] ?: 0)
        }
        previousChannelMax.putAll(maxIds)
        if (fresh.isEmpty()) return

        val muted = ServerRepository.mutedChannelIds()
        val names = runCatching { ServerRepository.channelNames() }.getOrDefault(emptyMap())
        val badges = runCatching { ServerRepository.badges() }
            .getOrDefault(emptyList())

        for ((channelId, _) in fresh) {
            if (channelId in muted) continue

            val badge = badges.firstOrNull { it.channelId == channelId }
            // Упоминания: сначала бейдж (таблица server_mentions), иначе —
            // разбор расшифрованного текста. Отдельно считаем ответы на мои
            // сообщения: на ПК это тоже упоминание, но повод другой, и в
            // шторке разница видна.
            val mentions = badge?.mentions?.takeIf { it > 0 }
                ?: runCatching { ServerRepository.mentionsAmongNew(channelId, 0) }.getOrDefault(0)
            val replies = runCatching { ServerRepository.repliesToMeAmongNew(channelId) }
                .getOrDefault(0)

            val preview = runCatching { ServerRepository.previewOfLatestInChannel(channelId) }
                .getOrDefault("Новое сообщение")

            Notifications.showChannelMessage(
                this,
                channelId = channelId,
                channelName = names[channelId] ?: "Канал",
                mentions = mentions,
                replies = replies,
                preview = preview,
            )
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    companion object {
        fun start(context: Context) {
            if (!Prefs.backgroundPolling) return
            runCatching {
                val intent = Intent(context, PollingService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, PollingService::class.java)) }
        }
    }
}
